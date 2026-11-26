# 2× 超分第三方许可与来源

## waifu2x UpConv7 插画 RGB 模型

原始模型来自 nagadomi 的 waifu2x。原作者确认仓库 `models/` 下的权重适用 MIT 许可。MIT 允许再分发，并要求在副本或主要部分中随附版权声明和许可文本。此目录随 APK 附带 [nagadomi 的 MIT 许可](waifu2x-LICENSE.txt)和[原仓库 NOTICE](waifu2x-NOTICE.txt)。[原始模型](https://github.com/nagadomi/waifu2x/blob/master/models/upconv_7/art/scale2.0x_model.t7) · [原作者关于权重的确认](https://github.com/nagadomi/waifu2x/issues/478#issuecomment-5003816684)

这里的 ncnn 文件来自 nihui 的 waifu2x-ncnn-vulkan `20250915`，提交 `a86cfb043e6482b5f08778a114df9b5faf6e73b7`，目录 `models/models-upconv_7_anime_style_art_rgb`，并随附[转换项目的 MIT 许可](waifu2x-ncnn-vulkan-LICENSE.txt)。[固定版本的模型目录](https://github.com/nihui/waifu2x-ncnn-vulkan/tree/a86cfb043e6482b5f08778a114df9b5faf6e73b7/models/models-upconv_7_anime_style_art_rgb)

| 文件 | SHA-256 |
| --- | --- |
| `scale2.0x_model.param` | `413195ffde05b4d43807792c6c020c916cecdf25dcf002ee83f5e28d5cc246c6` |
| `scale2.0x_model.bin` | `27d880903ff81390a3b4a900b67af3381c465279e34297546c9a58781855f7ef` |

## Tencent ncnn

使用 Tencent 发布的 `ncnn-20250916-android.zip` 中的 arm64-v8a 和 x86_64 静态库。二进制按 BSD 3-Clause 许可分发，版权声明、条款、免责条款及上游依赖声明一并包含在 [ncnn-LICENSE.txt](ncnn-LICENSE.txt)。[ncnn Android 发布](https://github.com/Tencent/ncnn/releases/tag/20250916) · [发布资产 SHA-256](https://github.com/Tencent/ncnn/releases/expanded_assets/20250916)

| 文件 | SHA-256 |
| --- | --- |
| `ncnn-20250916-android.zip` | `29bee787a120f2410708bca4e9450975ce084861ff92d9e79d0e39c92291248e` |
| `arm64-v8a/lib/libncnn.a` | `0abd67662358104239232d51439831419ac85224c0b299f422f426298ea09bf4` |
| `x86_64/lib/libncnn.a` | `8972df8a43efaf9dd96136366e15edfca866d0c57940227067573a66072f10c3` |

## Android NDK

JNI 库静态链接 Android NDK r27b 的 C++ 和 OpenMP 运行库。随 APK 附 [Android NDK 自带的工具链许可声明](android-ndk-NOTICE.toolchain.txt)。[Android NDK 版本记录](https://developer.android.com/ndk/downloads/revision_history)
