package com.leon.appconfig.compose

import android.widget.Toast
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.leon.appconfig.AppConfig
import com.leon.appconfig.FeedbackCategory
import com.leon.appconfig.FeedbackItem
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * Feedback form plus this user's earlier feedback and your replies.
 * The install ID is not shown; long-pressing the title copies it, for adding a tester on the admin page.
 */
@Composable
fun FeedbackDialog(onDismiss: () -> Unit) {
    if (!AppConfig.isInitialized) return
    val scope = rememberCoroutineScope()
    var category by remember { mutableStateOf(FeedbackCategory.BUG) }
    var message by remember { mutableStateOf("") }
    var contact by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Boolean?>(null) }
    val history = remember { mutableStateListOf<FeedbackItem>() }
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val copiedText = stringResource(R.string.appconfig_install_id_copied)

    suspend fun loadHistory() {
        AppConfig.fetchFeedback().onSuccess { items ->
            history.clear()
            history.addAll(items)
            items.filter { it.reply != null }.forEach(AppConfig::markReplySeen)
        }
    }
    LaunchedEffect(Unit) { loadHistory() }

    AlertDialog(
        onDismissRequest = { if (!sending) onDismiss() },
        // Long-press the title to copy the install ID (for adding this device as a tester).
        // It is deliberately not shown in the dialog.
        title = {
            Text(
                stringResource(R.string.appconfig_feedback_title),
                modifier = Modifier.pointerInput(Unit) {
                    detectTapGestures(onLongPress = {
                        clipboard.setText(AnnotatedString(AppConfig.installId))
                        Toast.makeText(context, copiedText, Toast.LENGTH_SHORT).show()
                    })
                },
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        FeedbackCategory.BUG to R.string.appconfig_feedback_bug,
                        FeedbackCategory.REQUEST to R.string.appconfig_feedback_request,
                        FeedbackCategory.OTHER to R.string.appconfig_feedback_other,
                    ).forEach { (c, label) ->
                        FilterChip(selected = category == c, onClick = { category = c }, label = { Text(stringResource(label)) })
                    }
                }
                OutlinedTextField(
                    value = message,
                    onValueChange = { message = it.take(4000); result = null },
                    placeholder = { Text(stringResource(R.string.appconfig_feedback_hint)) },
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = contact,
                    onValueChange = { contact = it.take(200) },
                    label = { Text(stringResource(R.string.appconfig_feedback_contact)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth(),
                )
                result?.let { ok ->
                    Text(
                        stringResource(if (ok) R.string.appconfig_feedback_sent else R.string.appconfig_feedback_failed),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                }

                if (history.isNotEmpty()) {
                    HorizontalDivider(modifier = Modifier.padding(top = 4.dp))
                    Text(stringResource(R.string.appconfig_feedback_history), style = MaterialTheme.typography.titleSmall)
                    history.forEach { item -> HistoryItem(item) }
                }

            }
        },
        confirmButton = {
            TextButton(
                enabled = message.isNotBlank() && !sending,
                onClick = {
                    sending = true
                    scope.launch {
                        val ok = AppConfig.sendFeedback(message.trim(), category, contact.trim()).isSuccess
                        sending = false
                        result = ok
                        if (ok) {
                            message = ""
                            loadHistory()
                        }
                    }
                },
            ) {
                if (sending) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.padding(end = 8.dp).heightIn(max = 16.dp))
                Text(stringResource(R.string.appconfig_feedback_send))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !sending) { Text(stringResource(R.string.appconfig_close)) }
        },
    )
}

@Composable
private fun HistoryItem(item: FeedbackItem) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(item.time)),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(item.message, style = MaterialTheme.typography.bodySmall)
        item.reply?.let { reply ->
            Text(
                stringResource(R.string.appconfig_feedback_reply) + "：" + reply,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
