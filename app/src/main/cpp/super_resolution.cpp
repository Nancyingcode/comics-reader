#include <jni.h>

#if defined(KAI_HAS_NCNN)
#include <android/asset_manager_jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <net.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <memory>

namespace {
constexpr int kScale = 2;
constexpr int kContext = 7;
constexpr int kTileSize = 64;
constexpr int kMaxInputEdge = 1024;
constexpr int kMaxInputPixels = 262144;
constexpr int kChannels = 3;
constexpr int kPixelBytes = 4;
constexpr int kAlpha = 3;
constexpr char kTag[] = "KaiSuperResolution";

// Kotlin serializes model setup and tile inference on one dispatcher for the process.
std::unique_ptr<ncnn::Net> model;

class BitmapPixels {
public:
    BitmapPixels(JNIEnv* env, jobject bitmap) : env_(env), bitmap_(bitmap) {}
    ~BitmapPixels() {
        if (pixels_ != nullptr) AndroidBitmap_unlockPixels(env_, bitmap_);
    }

    bool lock() {
        if (AndroidBitmap_getInfo(env_, bitmap_, &info) != ANDROID_BITMAP_RESULT_SUCCESS) return false;
        if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return false;
        return AndroidBitmap_lockPixels(env_, bitmap_, &pixels_) == ANDROID_BITMAP_RESULT_SUCCESS;
    }

    uint8_t* row(int y) const {
        return static_cast<uint8_t*>(pixels_) + static_cast<size_t>(y) * info.stride;
    }

    AndroidBitmapInfo info{};

    BitmapPixels(const BitmapPixels&) = delete;
    BitmapPixels& operator=(const BitmapPixels&) = delete;

private:
    JNIEnv* env_;
    jobject bitmap_;
    void* pixels_ = nullptr;
};

struct Tile {
    int x;
    int y;
    int width;
    int height;
};

int nearestSourceIndex(int index, uint32_t size) {
    return std::clamp(index, 0, static_cast<int>(size) - 1);
}

void prepareInput(const BitmapPixels& source, const Tile& tile, ncnn::Mat& input) {
    float* red = input.channel(0);
    float* green = input.channel(1);
    float* blue = input.channel(2);
    constexpr float toUnitRange = 1.f / 255.f;

    // The valid convolutions consume the overlap. Replication is only used at input edges.
    for (int y = 0; y < input.h; ++y) {
        const auto* row = source.row(nearestSourceIndex(tile.y + y - kContext, source.info.height));
        for (int x = 0; x < input.w; ++x) {
            const int sourceX = nearestSourceIndex(tile.x + x - kContext, source.info.width);
            const auto* pixel = row + sourceX * kPixelBytes;
            *red++ = pixel[0] * toUnitRange;
            *green++ = pixel[1] * toUnitRange;
            *blue++ = pixel[2] * toUnitRange;
        }
    }
}

uint8_t alphaAt(const BitmapPixels& source, int x, int y) {
    const int column = nearestSourceIndex(x, source.info.width);
    const int row = nearestSourceIndex(y, source.info.height);
    return source.row(row)[column * kPixelBytes + kAlpha];
}

uint8_t interpolateAlpha(const BitmapPixels& source, int targetX, int targetY) {
    const int x = targetX / kScale;
    const int y = targetY / kScale;
    const int neighborX = x + (targetX % kScale == 0 ? -1 : 1);
    const int neighborY = y + (targetY % kScale == 0 ? -1 : 1);
    const int center = alphaAt(source, x, y);
    const int horizontal = alphaAt(source, neighborX, y);
    const int vertical = alphaAt(source, x, neighborY);
    const int diagonal = alphaAt(source, neighborX, neighborY);
    // Pixel-center interpolation uses the same source coordinates on both sides of a tile boundary.
    return static_cast<uint8_t>((9 * center + 3 * horizontal + 3 * vertical + diagonal + 8) / 16);
}

uint8_t premultipliedChannel(float channel, uint8_t alpha) {
    const float bounded = std::clamp(channel * 255.f, 0.f, static_cast<float>(alpha));
    return static_cast<uint8_t>(bounded + 0.5f);
}

bool writeOutput(
    const ncnn::Mat& output,
    const BitmapPixels& source,
    const Tile& tile,
    const BitmapPixels& destination,
    int destinationTop,
    bool hasAlpha
) {
    const float* red = output.channel(0);
    const float* green = output.channel(1);
    const float* blue = output.channel(2);

    for (int y = 0; y < output.h; ++y) {
        const int targetY = tile.y * kScale + y;
        auto* row = destination.row(targetY - destinationTop * kScale);
        for (int x = 0; x < output.w; ++x) {
            const int targetX = tile.x * kScale + x;
            auto* pixel = row + targetX * kPixelBytes;
            const uint8_t alpha = hasAlpha ? interpolateAlpha(source, targetX, targetY) : 255;
            if (!std::isfinite(*red) || !std::isfinite(*green) || !std::isfinite(*blue)) return false;
            pixel[0] = premultipliedChannel(*red++, alpha);
            pixel[1] = premultipliedChannel(*green++, alpha);
            pixel[2] = premultipliedChannel(*blue++, alpha);
            pixel[kAlpha] = alpha;
        }
    }
    return true;
}

bool enhanceTile(
    const BitmapPixels& source,
    const Tile& tile,
    const BitmapPixels& destination,
    int destinationTop,
    bool hasAlpha
) {
    const int width = source.info.width;
    const int height = source.info.height;
    const bool fitsBudget = width > 0 && height > 0 && width <= kMaxInputEdge && height <= kMaxInputEdge &&
        int64_t{width} * height <= kMaxInputPixels;
    const bool isValidTile = tile.x >= 0 && tile.y >= 0 && tile.width > 0 && tile.height > 0 &&
        tile.width <= kTileSize && tile.height <= kTileSize &&
        tile.x <= width - tile.width && tile.y <= height - tile.height;
    const int destinationHeight = destination.info.height / kScale;
    const bool hasExpectedDestination = destination.info.width == source.info.width * kScale &&
        destination.info.height % kScale == 0 && destinationTop >= 0 &&
        destinationTop <= height - destinationHeight &&
        tile.y >= destinationTop && tile.y - destinationTop <= destinationHeight - tile.height;
    if (!fitsBudget || !isValidTile || !hasExpectedDestination) return false;

    ncnn::Mat input(tile.width + 2 * kContext, tile.height + 2 * kContext, kChannels);
    if (input.empty()) return false;
    prepareInput(source, tile, input);

    ncnn::Mat output;
    auto extractor = model->create_extractor();
    const int inputResult = extractor.input("Input1", input);
    if (inputResult != 0) return false;
    const int outputResult = extractor.extract("Eltwise4", output);
    if (outputResult != 0) {
        __android_log_print(ANDROID_LOG_WARN, kTag, "ncnn extraction failed (%d)", outputResult);
        return false;
    }
    const bool hasExpectedOutput = output.dims == 3 && output.c == kChannels &&
        output.elempack == 1 && output.elemsize == sizeof(float) &&
        output.w == tile.width * kScale && output.h == tile.height * kScale;
    return hasExpectedOutput && writeOutput(output, source, tile, destination, destinationTop, hasAlpha);
}
}

extern "C" JNIEXPORT jboolean JNICALL Java_io_github_kai_reader_NativeSuperResolution_loadModel(
    JNIEnv* env, jobject, jobject assetManager
) {
    if (model) return JNI_TRUE;
    auto* assets = AAssetManager_fromJava(env, assetManager);
    if (assets == nullptr) return JNI_FALSE;

    try {
        auto net = std::make_unique<ncnn::Net>();
        net->opt.num_threads = 2;
        // Returning OpenMP workers promptly between tiles leaves CPU time for scrolling and decoding.
        net->opt.openmp_blocktime = 0;
        net->opt.use_fp16_arithmetic = false;
        const int graphResult = net->load_param(assets, "super-resolution/scale2.0x_model.param");
        if (graphResult != 0) {
            __android_log_print(ANDROID_LOG_WARN, kTag, "waifu2x graph load failed (%d)", graphResult);
            return JNI_FALSE;
        }
        const int weightResult = net->load_model(assets, "super-resolution/scale2.0x_model.bin");
        if (weightResult != 0) {
            __android_log_print(ANDROID_LOG_WARN, kTag, "waifu2x weights load failed (%d)", weightResult);
            return JNI_FALSE;
        }
        model = std::move(net);
        return JNI_TRUE;
    } catch (const std::bad_alloc&) {
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jboolean JNICALL Java_io_github_kai_reader_NativeSuperResolution_inferTile(
    JNIEnv* env,
    jobject,
    jobject source,
    jobject destination,
    jint destinationTop,
    jint x,
    jint y,
    jint width,
    jint height,
    jboolean hasAlpha
) {
    if (!model) return JNI_FALSE;

    BitmapPixels sourcePixels(env, source);
    BitmapPixels destinationPixels(env, destination);
    if (!sourcePixels.lock() || !destinationPixels.lock()) return JNI_FALSE;

    try {
        return enhanceTile(sourcePixels, {x, y, width, height}, destinationPixels, destinationTop, hasAlpha == JNI_TRUE)
            ? JNI_TRUE : JNI_FALSE;
    } catch (const std::bad_alloc&) {
        return JNI_FALSE;
    }
}
#else
// Keep the APK installable on 32-bit Android. Kotlin gates the optional inference by process ABI.
extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM*, void*) {
    return JNI_VERSION_1_6;
}
#endif
