package io.github.kai.reader

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.IOException
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private val Context.readerDisplayStore by preferencesDataStore("reader-display")
private val contrastKey = floatPreferencesKey("contrast")
private val enhancedKey = booleanPreferencesKey("enhanced")
private val superResolutionKey = booleanPreferencesKey("super-resolution")
private val originalOnlyKey = booleanPreferencesKey("original-only")
private const val DEFAULT_ENHANCEMENT = 1.35f
private val CONTRAST_RANGE = 0.6f..2f

internal data class ReaderAppearance(
    val contrast: Float = DEFAULT_ENHANCEMENT,
    val enhanced: Boolean = false,
    val superResolution: Boolean = false,
    val originalOnly: Boolean = false,
) {
    val showContrast: Boolean get() = enhanced && !originalOnly
    val showSuperResolution: Boolean get() = superResolution && !originalOnly
    val showEnhancements: Boolean get() = showContrast || showSuperResolution

    fun adjustContrast(value: Float) = copy(contrast = value, enhanced = true, originalOnly = false)

    fun toggleOriginal(): ReaderAppearance = when {
        originalOnly -> copy(originalOnly = false)
        showEnhancements -> copy(originalOnly = true)
        else -> copy(enhanced = true)
    }

    fun setSuperResolution(enabled: Boolean) = copy(
        superResolution = enabled,
        originalOnly = originalOnly && !enabled && enhanced,
    )
}

internal class AppearanceViewModel(application: Application) : AndroidViewModel(application) {
    private val store = application.readerDisplayStore
    var error by mutableStateOf<String?>(null)
        private set
    val appearance = store.data.map(Preferences::appearance).catch { failure ->
        if (failure !is IOException) throw failure
        error = "无法读取画质偏好。"
        emit(ReaderAppearance())
    }

    fun update(change: (ReaderAppearance) -> ReaderAppearance) {
        viewModelScope.launch {
            try {
                store.edit {
                    val value = change(it.appearance())
                    it[contrastKey] = value.contrast.coerceIn(CONTRAST_RANGE)
                    it[enhancedKey] = value.enhanced
                    it[superResolutionKey] = value.superResolution
                    it[originalOnlyKey] = value.originalOnly
                }
                error = null
            } catch (_: IOException) {
                error = "无法保存画质偏好，请重试。"
            }
        }
    }
}

private fun Preferences.appearance() = ReaderAppearance(
    contrast = this[contrastKey]?.coerceIn(CONTRAST_RANGE) ?: DEFAULT_ENHANCEMENT,
    enhanced = this[enhancedKey] ?: false,
    superResolution = this[superResolutionKey] ?: false,
    originalOnly = this[originalOnlyKey] ?: false,
)

internal fun contrastFilter(contrast: Float): ColorFilter? {
    if (contrast == 1f) return null
    val shift = 128f * (1f - contrast)
    return ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(
        contrast, 0f, 0f, 0f, shift,
        0f, contrast, 0f, 0f, shift,
        0f, 0f, contrast, 0f, shift,
        0f, 0f, 0f, 1f, 0f,
    )))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppearanceSheet(
    value: ReaderAppearance,
    error: String?,
    superResolutionSupported: Boolean,
    superResolutionStatus: String?,
    onPreview: (Float) -> Unit,
    onCommit: () -> Unit,
    onSuperResolution: (Boolean) -> Unit,
    onToggle: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("画质", style = MaterialTheme.typography.titleLarge)
            val label = if (value.showContrast) "对比度 ${(value.contrast * 100).roundToInt()}%" else "对比度 · 原图"
            Text(label)
            Slider(
                value = value.contrast, valueRange = CONTRAST_RANGE,
                onValueChange = onPreview, onValueChangeFinished = onCommit,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "对比度" },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("2× 超分")
                Spacer(Modifier.weight(1f))
                Switch(
                    checked = value.superResolution,
                    enabled = superResolutionSupported || value.superResolution,
                    onCheckedChange = onSuperResolution,
                    modifier = Modifier.semantics { contentDescription = "2× 超分" },
                )
            }
            superResolutionStatus?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            TextButton(onClick = onToggle) { Text(if (value.showEnhancements) "查看原图" else "恢复增强") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            TextButton(onClick = onDismiss) { Text("完成") }
        }
    }
}
