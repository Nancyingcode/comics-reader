package io.github.kai.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import kotlin.math.roundToInt

@Composable
internal fun ShelfScreen(
    books: List<BookSummary>,
    loading: Boolean,
    covers: ImageLoader,
    onDirectory: () -> Unit,
    onArchive: () -> Unit,
    onWebDav: () -> Unit,
    onHttps: () -> Unit,
    onClearCache: () -> Unit,
    onSelect: (String) -> Unit,
    onResume: (String) -> Unit,
) {
    val recent = books.filter { it.lastReadAt != null }.maxByOrNull { it.lastReadAt ?: 0L }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(140.dp),
            modifier = Modifier.widthIn(max = 1000.dp).fillMaxSize(),
            contentPadding = PaddingValues(20.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("我的书架", style = MaterialTheme.typography.headlineLarge)
                        TextButton(onClick = onClearCache) { Text("清理阅读缓存") }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Button(onClick = onDirectory) { Text("导入目录") }
                        OutlinedButton(onClick = onArchive) { Text("导入压缩包") }
                        OutlinedButton(onClick = onWebDav) { Text("连接 WebDAV") }
                        OutlinedButton(onClick = onHttps) { Text("HTTPS 直链") }
                    }
                    if (books.isEmpty()) {
                        val message = if (loading) "正在读取书架…" else "导入本地漫画，或从远程书库添加作品。"
                        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (recent != null) item(span = { GridItemSpan(maxLineSpan) }) {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp)) {
                    Row(
                        Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(recent.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            ReadingCaption(recent)
                        }
                        Button(onClick = { onResume(recent.id) }) { Text("继续阅读") }
                    }
                }
            }
            items(books, key = { it.id }) { book ->
                Column(
                    Modifier.fillMaxWidth().clickable { onSelect(book.id) },
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    BookCover(book, covers, Modifier.fillMaxWidth().aspectRatio(0.72f))
                    Text(book.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    MutedText(book.pageCaption())
                    ReadingCaption(book)
                }
            }
        }
    }
}

private fun BookSummary.pageCaption(): String = if (unindexedChapterCount == 0) {
    "$chapterCount 章 · $pageCount 页"
} else "$chapterCount 章 · $unindexedChapterCount 章页数待统计"

@Composable
private fun ReadingCaption(book: BookSummary) {
    val chapter = book.lastChapterName ?: return
    val page = book.lastPageNumber?.let { " · 第 $it 页" }.orEmpty()
    val offset = book.lastPageFraction?.takeIf { it > 0.0 }?.let { " · 页内 ${(it * 100).roundToInt()}%" }.orEmpty()
    MutedText(chapter + page + offset)
}

@Composable
private fun MutedText(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2, overflow = TextOverflow.Ellipsis)
}

@Composable
internal fun BookScreen(
    details: BookDetails,
    covers: ImageLoader,
    onBack: () -> Unit,
    onResume: () -> Unit,
    onChapter: (String, Boolean) -> Unit,
    onRefresh: () -> Unit,
    onOffline: (ChapterSummary) -> Unit,
) {
    val book = details.book
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(Modifier.widthIn(max = 760.dp).fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = onBack) { Text("返回书架") }
                        if (book.isRemote) TextButton(onClick = onRefresh) { Text("刷新") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        BookCover(book, covers, Modifier.width(112.dp).aspectRatio(0.72f))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(book.name, style = MaterialTheme.typography.headlineSmall)
                            Text(book.pageCaption())
                            ReadingCaption(book)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = onResume) { Text(if (book.lastReadAt == null) "开始阅读" else "继续阅读") }
                        if (book.lastReadAt != null) TextButton(onClick = {
                            details.chapters.firstOrNull()?.let { onChapter(it.id, true) }
                        }) { Text("从头阅读") }
                    }
                    Text("章节", modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleLarge)
                }
            }
            items(details.chapters, key = { it.id }) { chapter ->
                ChapterRow(chapter, false, if (chapter.isRemote) ({ onOffline(chapter) }) else null) {
                    onChapter(chapter.id, false)
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun ChapterRow(chapter: ChapterSummary, current: Boolean, onOffline: (() -> Unit)? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(chapter.name, style = MaterialTheme.typography.titleMedium,
                color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            if (chapter.isOffline) MutedText("可离线阅读")
            chapter.lastPageNumber?.let { page ->
                val fraction = chapter.lastPageFraction
                val position = if (chapter.pageCount == 1 && fraction != null) {
                    "页内 ${(fraction * 100).roundToInt()}%"
                } else "第 $page 页"
                MutedText("已读到$position")
            }
        }
        if (current) Text("当前章", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
        Text(if (chapter.isIndexed) "${chapter.pageCount} 页" else "待统计", style = MaterialTheme.typography.bodyMedium)
        if (onOffline != null) TextButton(onClick = onOffline) {
            Text(if (chapter.isOffline) "删除离线" else "保存离线")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChapterSheet(chapters: List<ChapterSummary>, activeId: String, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    val activeIndex = chapters.indexOfFirst { it.id == activeId }.coerceAtLeast(0)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text("章节目录", Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.titleLarge)
        LazyColumn(
            state = rememberLazyListState(initialFirstVisibleItemIndex = activeIndex),
            modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        ) {
            items(chapters, key = { it.id }) { chapter ->
                ChapterRow(chapter, chapter.id == activeId) { onSelect(chapter.id) }
                HorizontalDivider()
            }
        }
    }
}
