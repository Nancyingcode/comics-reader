package io.github.kai.reader

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.asImage
import coil3.compose.AsyncImage
import coil3.decode.DataSource
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.request.ImageRequest

private data class CoverRequest(val page: ReaderPage, val width: Int, val height: Int)

internal fun coverImageLoader(context: Context, images: LocalImages): ImageLoader {
    val factory = Fetcher.Factory<CoverRequest> { request, _, _ ->
        Fetcher {
            val cover = images.thumbnail(request.page, request.width, request.height)
            ImageFetchResult(cover.asImage(), isSampled = true, dataSource = DataSource.DISK)
        }
    }
    return ImageLoader.Builder(context.applicationContext).components { add(factory) }.build()
}

@Composable
internal fun BookCover(book: BookSummary, loader: ImageLoader, modifier: Modifier) {
    BoxWithConstraints(
        modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        val width = constraints.maxWidth.coerceAtLeast(1)
        val height = constraints.maxHeight.coerceAtLeast(1)
        val context = LocalContext.current
        val request = remember(context, book.cover, book.indexedAt, width, height) {
            ImageRequest.Builder(context)
                .data(CoverRequest(book.cover, width, height))
                .size(width, height)
                .memoryCacheKey("${book.cover.id}/${book.cover.remoteVersion}/${book.indexedAt}/${width}x$height")
                .build()
        }
        AsyncImage(
            model = request,
            imageLoader = loader,
            contentDescription = "${book.name}封面",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
