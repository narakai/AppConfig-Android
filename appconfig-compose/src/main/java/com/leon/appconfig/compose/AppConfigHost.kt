package com.leon.appconfig.compose

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.leon.appconfig.AppConfig
import com.leon.appconfig.FeedbackItem
import com.leon.appconfig.Notice
import com.leon.appconfig.UpdateInfo

/**
 * Put this once at the root of the UI. It refreshes on start and when the app comes back,
 * then shows, one at a time and in this order:
 * 1. a forced update (cannot be dismissed; it stays until the app is updated)
 * 2. a soft update (Update / Later / Skip this version)
 * 3. notices the user has not seen
 * 4. replies to the user's feedback
 *
 * Dialogs use the app's MaterialTheme.
 *
 * @param onLeaveApp called right before opening the store or a link, e.g. to suppress an app-open ad
 * @param resumeRefreshIntervalMs how often coming back to the app checks again; a forced update always re-checks
 */
@Composable
fun AppConfigHost(
    onLeaveApp: () -> Unit = {},
    resumeRefreshIntervalMs: Long = 30 * 60_000L,
) {
    if (!AppConfig.isInitialized) return
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state by AppConfig.state.collectAsStateWithLifecycle()

    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            // After returning from the store, check right away whether the forced update was installed.
            val forced = AppConfig.state.value.update?.type == UpdateInfo.Type.FORCE
            AppConfig.refresh(minIntervalMs = if (forced) 0 else resumeRefreshIntervalMs)
        }
    }

    val open: (String) -> Unit = { url ->
        onLeaveApp()
        openUrl(context, url)
    }

    val update = state.update
    val notice = state.notices.firstOrNull()
    val reply = state.unreadReplies.firstOrNull()
    when {
        update?.type == UpdateInfo.Type.FORCE -> UpdateDialog(update, forced = true, open = open)
        update != null -> UpdateDialog(update, forced = false, open = open)
        notice != null -> NoticeDialog(notice, open = open)
        reply != null -> ReplyDialog(reply)
    }
}

@Composable
private fun UpdateDialog(update: UpdateInfo, forced: Boolean, open: (String) -> Unit) {
    val title = update.title ?: stringResource(
        if (forced) R.string.appconfig_force_update_title else R.string.appconfig_update_title,
    )
    val body = update.body ?: if (forced) {
        stringResource(R.string.appconfig_force_update_body)
    } else {
        stringResource(R.string.appconfig_update_body, update.latestVersionName ?: update.latestVersionCode.toString())
    }
    AlertDialog(
        // Forced: back, tapping outside and "Later" are all unavailable; only Update.
        onDismissRequest = { if (!forced) AppConfig.dismissUpdate() },
        properties = DialogProperties(dismissOnBackPress = !forced, dismissOnClickOutside = !forced),
        title = { Text(title) },
        text = { ScrollingText(body) },
        confirmButton = {
            TextButton(onClick = { open(update.storeUrl ?: AppConfig.playStoreUrl()) }) {
                Text(stringResource(R.string.appconfig_update))
            }
        },
        dismissButton = if (forced) null else {
            {
                Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                    TextButton(onClick = AppConfig::dismissUpdate) { Text(stringResource(R.string.appconfig_later)) }
                    TextButton(onClick = AppConfig::skipVersion) { Text(stringResource(R.string.appconfig_skip_version)) }
                }
            }
        },
    )
}

@Composable
private fun NoticeDialog(notice: Notice, open: (String) -> Unit) {
    val done = { AppConfig.markNoticeSeen(notice.id) }
    AlertDialog(
        onDismissRequest = { if (!notice.mustConfirm) done() },
        properties = DialogProperties(dismissOnBackPress = !notice.mustConfirm, dismissOnClickOutside = !notice.mustConfirm),
        title = { Text((if (notice.test) stringResource(R.string.appconfig_test_prefix) else "") + notice.title) },
        text = { ScrollingText(notice.body) },
        confirmButton = {
            TextButton(onClick = done) {
                Text(stringResource(if (notice.mustConfirm) R.string.appconfig_confirm else R.string.appconfig_close))
            }
        },
        // Opening the link does not count as confirming.
        dismissButton = notice.link?.let { link ->
            { TextButton(onClick = { open(link) }) { Text(stringResource(R.string.appconfig_open_link)) } }
        },
    )
}

@Composable
private fun ReplyDialog(item: FeedbackItem) {
    val done = { AppConfig.markReplySeen(item) }
    AlertDialog(
        onDismissRequest = done,
        title = { Text(stringResource(R.string.appconfig_reply_title)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
            ) {
                Text(item.reply.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(R.string.appconfig_your_message) + "\n" + item.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = done) { Text(stringResource(R.string.appconfig_close)) } },
    )
}

@Composable
private fun ScrollingText(text: String) {
    Column(modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

internal fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
    }
}
