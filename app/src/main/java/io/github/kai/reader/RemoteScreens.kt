package io.github.kai.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import java.util.Locale

@Composable
internal fun RemoteSourceDialog(
    webDav: Boolean,
    onDismiss: () -> Unit,
    onConnect: (String, String, String, String) -> Unit,
    onLink: (String) -> Unit,
) {
    // 签名链接、口令可能含凭据，不写入 Activity 的状态备份。
    var url by remember { mutableStateOf("") }
    var rootPath by rememberSaveable { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (webDav) "连接 WebDAV" else "HTTPS 压缩包直链") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(url, { url = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(if (webDav) "服务地址" else "压缩包链接") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                if (webDav) {
                    OutlinedTextField(rootPath, { rootPath = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        label = { Text("根目录（可选）") })
                    OutlinedTextField(username, { username = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        label = { Text("用户名（可选）") })
                    OutlinedTextField(password, { password = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        label = { Text("密码（可选）") }, visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                    if (url.trimStart().startsWith("http://", ignoreCase = true)) {
                        Text("HTTP 会以明文传输漫画和登录信息。", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }, confirmButton = {
            Button(enabled = url.isNotBlank(), onClick = {
                if (webDav) onConnect(url.trim(), rootPath.trim(), username, password) else onLink(url.trim())
            }) { Text(if (webDav) "连接" else "导入") }
        }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
internal fun WebDavScreen(
    browser: WebDavBrowser,
    onBack: () -> Unit,
    onClose: () -> Unit,
    onBrowse: (RemoteDavEntry) -> Unit,
    onImport: (RemoteDavEntry?) -> Unit,
    onRetry: () -> Unit,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 840.dp).fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onClose) { Text("返回书架") }
                if (browser.canGoBack) TextButton(onClick = onBack) { Text("上一级") }
            }
            Text("WebDAV 书库", style = MaterialTheme.typography.headlineMedium)
            Text(browser.path, style = MaterialTheme.typography.bodyMedium)
            if (browser.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            val error = browser.error
            if (error != null) {
                Text(error, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onRetry) { Text("重试") }
            } else if (!browser.loading) {
                Button(onClick = { onImport(null) }) { Text("导入此目录") }
                LazyColumn {
                    items(browser.entries, key = { it.location.toString() }) { entry ->
                        val selectable = entry.isDirectory || entry.isArchive
                        Row(Modifier.fillMaxWidth().clickable(enabled = selectable) {
                            if (entry.isDirectory) onBrowse(entry) else onImport(entry)
                        }.padding(vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(entry.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                            if (selectable) Text(if (entry.isDirectory) "进入目录" else "导入压缩包",
                                color = MaterialTheme.colorScheme.primary)
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_048_576 -> String.format(Locale.ROOT, "%.2f MiB", bytes / 1_048_576.0)
    bytes >= 1024 -> String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0)
    else -> "$bytes B"
}

@Composable
internal fun OperationScreen(operation: LibraryOperation?, onCancel: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 420.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val progress = operation?.progress
            Text(operation?.label ?: "正在读取漫画…", style = MaterialTheme.typography.titleMedium)
            if (progress == null) CircularProgressIndicator() else {
                val bytes = progress.downloadedBytes
                val total = progress.totalBytes
                if (total != null && total > 0L) {
                    val fraction = (bytes.toDouble() / total).coerceIn(0.0, 1.0)
                    LinearProgressIndicator(progress = { fraction.toFloat() }, modifier = Modifier.fillMaxWidth())
                    Text("${(fraction * 100).toInt()}% · ${formatBytes(bytes)} / ${formatBytes(total)}")
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("已下载 ${formatBytes(bytes)}")
                }
            }
            TextButton(onClick = onCancel) { Text("取消") }
        }
    }
}
