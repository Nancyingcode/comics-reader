package io.github.kai.reader

import android.app.ActivityManager
import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.util.Log
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext

internal const val SUPER_RESOLUTION_MAX_EDGE = 1024
internal const val SUPER_RESOLUTION_MAX_INPUT_PIXELS = 262144
internal const val SUPER_RESOLUTION_CONTEXT = 8
internal const val SUPER_RESOLUTION_SCALE = 2
internal const val SUPER_RESOLUTION_TILE_SIZE = 64

private const val NATIVE_WORKSPACE_HEADROOM = 24L * 1024 * 1024
private const val TAG = "KaiSuperResolution"
private val supportedAbis = setOf("arm64-v8a", "x86_64")
private val inferenceDispatcher = Dispatchers.Default.limitedParallelism(1)

internal class ReaderSuperResolution(context: Context) {
    private val appContext = context.applicationContext
    private val activityManager = appContext.getSystemService(ActivityManager::class.java)
    private val powerManager = appContext.getSystemService(PowerManager::class.java)

    val isSupported = Process.is64Bit() &&
        Build.SUPPORTED_ABIS.any { it in supportedAbis } &&
        Runtime.getRuntime().availableProcessors() >= 2 &&
        activityManager?.isLowRamDevice == false

    val ready: Flow<Boolean> = when {
        !isSupported -> flowOf(false)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
            powerManager?.let(::thermalReadiness) ?: flowOf(false)
        else -> flowOf(true)
    }

    suspend fun enhance(source: Bitmap, rows: SuperResolutionRows): Bitmap? {
        currentCoroutineContext().ensureActive()
        val rowsFitSource = rows.top >= 0 && rows.bottom <= source.height && rows.height > 0
        if (!isSupported || !source.supportsSuperResolution() || !rowsFitSource) return null

        var allocated: Bitmap? = null
        try {
            val result = withContext(inferenceDispatcher) {
                val canStart = canUseCpu() && hasMemoryForOutput(source.width, rows.height)
                if (!canStart || !NativeSuperResolution.initialize(appContext.assets)) return@withContext null
                ensureActive()

                val hasAlpha = source.hasAlpha()
                val colorSpace = source.colorSpace ?: ColorSpace.get(ColorSpace.Named.SRGB)
                val output = Bitmap.createBitmap(
                    source.width * SUPER_RESOLUTION_SCALE,
                    rows.height * SUPER_RESOLUTION_SCALE,
                    Bitmap.Config.ARGB_8888,
                    hasAlpha,
                    colorSpace,
                ).also { allocated = it }
                output.density = source.density

                // withContext keeps the caller's input alive until the active native tile exits.
                for (y in rows.top until rows.bottom step SUPER_RESOLUTION_TILE_SIZE) {
                    val height = minOf(SUPER_RESOLUTION_TILE_SIZE, rows.bottom - y)
                    for (x in 0 until source.width step SUPER_RESOLUTION_TILE_SIZE) {
                        ensureActive()
                        if (!canUseCpu()) return@withContext null
                        val width = minOf(SUPER_RESOLUTION_TILE_SIZE, source.width - x)
                        val finished = NativeSuperResolution.inferTile(source, output, rows.top, x, y, width, height, hasAlpha)
                        if (!finished) return@withContext null
                    }
                }
                ensureActive()
                output.takeIf { canUseCpu() }
            }
            if (result != null) allocated = null
            return result
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: LinkageError) {
            Log.w(TAG, "无法加载超分引擎", failure)
            return null
        } catch (failure: OutOfMemoryError) {
            Log.w(TAG, "超分内存不足")
            return null
        } catch (failure: RuntimeException) {
            Log.w(TAG, "超分处理失败", failure)
            return null
        } finally {
            // withContext may discard its return value if cancellation races with delivery.
            allocated?.recycle()
        }
    }

    private fun canUseCpu(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        val manager = powerManager ?: return false
        return manager.isThermallyReady()
    }

    private fun hasMemoryForOutput(width: Int, height: Int): Boolean {
        val manager = activityManager ?: return false
        val memory = ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
        val outputBytes = width.toLong() * height * SUPER_RESOLUTION_SCALE * SUPER_RESOLUTION_SCALE * 4
        return !memory.lowMemory && memory.availMem - memory.threshold >= outputBytes + NATIVE_WORKSPACE_HEADROOM
    }
}

internal fun fitsSuperResolutionInput(width: Int, height: Int): Boolean {
    val fitsEdge = width in 1..SUPER_RESOLUTION_MAX_EDGE && height in 1..SUPER_RESOLUTION_MAX_EDGE
    return fitsEdge && width.toLong() * height <= SUPER_RESOLUTION_MAX_INPUT_PIXELS
}

private fun Bitmap.supportsSuperResolution(): Boolean {
    val hasReadablePixels = !isRecycled && config == Bitmap.Config.ARGB_8888 && (!hasAlpha() || isPremultiplied)
    return hasReadablePixels && fitsSuperResolutionInput(width, height)
}

@RequiresApi(Build.VERSION_CODES.Q)
private fun thermalReadiness(manager: PowerManager): Flow<Boolean> = callbackFlow {
    val listener = PowerManager.OnThermalStatusChangedListener { trySend(manager.isThermallyReady()) }
    try {
        manager.addThermalStatusListener(listener)
    } catch (failure: RuntimeException) {
        Log.w(TAG, "无法监听设备热状态", failure)
        trySend(false)
        close()
        return@callbackFlow
    }
    trySend(manager.isThermallyReady())
    awaitClose { manager.removeThermalStatusListener(listener) }
}.distinctUntilChanged()

@RequiresApi(Build.VERSION_CODES.Q)
private fun PowerManager.isThermallyReady(): Boolean = try {
    currentThermalStatus < PowerManager.THERMAL_STATUS_SEVERE
} catch (failure: RuntimeException) {
    Log.w(TAG, "无法读取设备热状态", failure)
    false
}

@Keep
private object NativeSuperResolution {
    private val libraryLoaded by lazy {
        try {
            System.loadLibrary("kai_upscale")
            true
        } catch (failure: LinkageError) {
            Log.w(TAG, "无法加载超分动态库", failure)
            false
        }
    }

    fun initialize(assets: AssetManager): Boolean = libraryLoaded && loadModel(assets)

    private external fun loadModel(assets: AssetManager): Boolean

    external fun inferTile(
        source: Bitmap,
        destination: Bitmap,
        destinationTop: Int,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        hasAlpha: Boolean,
    ): Boolean
}
