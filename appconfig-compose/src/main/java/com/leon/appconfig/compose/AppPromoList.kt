package com.leon.appconfig.compose

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.leon.appconfig.AppConfig
import com.leon.appconfig.Promo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Your other apps, as configured on the server (never the current app).
 * Draws nothing when there are none, so it can sit in a settings screen unconditionally.
 */
@Composable
fun AppPromoList(
    modifier: Modifier = Modifier,
    onLeaveApp: () -> Unit = {},
) {
    if (!AppConfig.isInitialized) return
    val state by AppConfig.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    if (state.promos.isEmpty()) return
    Column(modifier = modifier) {
        state.promos.forEach { promo ->
            PromoRow(promo) {
                onLeaveApp()
                openUrl(context, promo.storeUrl ?: AppConfig.playStoreUrl(promo.appId))
            }
        }
    }
}

@Composable
private fun PromoRow(promo: Promo, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        val icon by produceState<ImageBitmap?>(null, promo.iconUrl) { value = promo.iconUrl?.let { loadIcon(it) } }
        val shape = RoundedCornerShape(10.dp)
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            icon?.let { Image(it, contentDescription = null, modifier = Modifier.size(44.dp)) }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(promo.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            promo.body?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private val iconCache = ConcurrentHashMap<String, ImageBitmap>()

private suspend fun loadIcon(url: String): ImageBitmap? = iconCache[url] ?: withContext(Dispatchers.IO) {
    runCatching {
        URL(url).openStream().use { BitmapFactory.decodeStream(it) }?.asImageBitmap()
    }.getOrNull()?.also { iconCache[url] = it }
}
