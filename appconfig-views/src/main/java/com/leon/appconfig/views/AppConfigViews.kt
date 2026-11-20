package com.leon.appconfig.views

import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.leon.appconfig.AppConfig
import com.leon.appconfig.AppConfigState
import com.leon.appconfig.FeedbackCategory
import com.leon.appconfig.FeedbackItem
import com.leon.appconfig.Notice
import com.leon.appconfig.UpdateInfo
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * AppCompat (View system) counterpart of `appconfig-compose`, for apps that do not use Compose.
 * Callable from Java:
 *
 * ```java
 * // Application.onCreate
 * AppConfig.INSTANCE.init(this, BuildConfig.APP_CONFIG_URL, BuildConfig.DEBUG);
 * // Activity.onCreate
 * AppConfigViews.attach(this, () -> App.suppressNextAppOpenAd());
 * // Menu item
 * AppConfigViews.showFeedback(this);
 * ```
 */
object AppConfigViews {

    /**
     * Refreshes when [activity] resumes, then shows, one at a time and in this order:
     * a forced update (cannot be dismissed), a soft update, unseen notices, replies to feedback.
     * Dialogs are closed when the activity stops and shown again when it resumes.
     *
     * @param onLeaveApp called right before opening the store or a link, e.g. to suppress an app-open ad
     */
    @JvmStatic
    @JvmOverloads
    fun attach(
        activity: AppCompatActivity,
        onLeaveApp: Runnable? = null,
        resumeRefreshIntervalMs: Long = 30 * 60_000L,
    ) {
        if (!AppConfig.isInitialized) return
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                val host = Host(activity, onLeaveApp)
                try {
                    launch {
                        // After returning from the store, check right away whether a forced update was installed.
                        val forced = AppConfig.state.value.update?.type == UpdateInfo.Type.FORCE
                        AppConfig.refresh(minIntervalMs = if (forced) 0 else resumeRefreshIntervalMs)
                    }
                    AppConfig.state.collect { host.render(it) }
                } finally {
                    host.close()
                }
            }
        }
    }

    /** Feedback form plus this user's earlier feedback and your replies. Long-press the title to copy the install ID. */
    @JvmStatic
    fun showFeedback(activity: AppCompatActivity) {
        if (!AppConfig.isInitialized) return
        FeedbackForm(activity).show()
    }

    private class Host(val activity: AppCompatActivity, val onLeaveApp: Runnable?) {
        private var dialog: Dialog? = null
        private var shownKey: String? = null

        fun render(state: AppConfigState) {
            val update = state.update
            val notice = state.notices.firstOrNull()
            val reply = state.unreadReplies.firstOrNull()
            val key = when {
                update != null -> "update:${update.type}:${update.latestVersionCode}"
                notice != null -> "notice:${notice.id}"
                reply != null -> "reply:${reply.id}:${reply.repliedAt}"
                else -> null
            }
            if (key == shownKey && dialog?.isShowing == true) return
            close()
            if (key == null || activity.isFinishing || activity.isDestroyed) return
            shownKey = key
            dialog = when {
                update != null -> updateDialog(update)
                notice != null -> noticeDialog(notice)
                else -> replyDialog(reply!!)
            }.also { it.show() }
        }

        fun close() {
            dialog?.dismiss()
            dialog = null
            shownKey = null
        }

        private fun open(url: String) {
            onLeaveApp?.run()
            openUrl(activity, url)
        }

        private fun updateDialog(update: UpdateInfo): Dialog {
            val forced = update.type == UpdateInfo.Type.FORCE
            val title = update.title ?: activity.getString(
                if (forced) R.string.appconfig_force_update_title else R.string.appconfig_update_title,
            )
            val body = update.body ?: if (forced) {
                activity.getString(R.string.appconfig_force_update_body)
            } else {
                activity.getString(
                    R.string.appconfig_update_body,
                    update.latestVersionName ?: update.latestVersionCode.toString(),
                )
            }
            val builder = AlertDialog.Builder(activity)
                .setTitle(title)
                .setView(scrollingText(activity, body))
                .setCancelable(!forced)
                .setPositiveButton(R.string.appconfig_update, null)
            if (!forced) {
                builder.setNeutralButton(R.string.appconfig_skip_version) { _, _ -> AppConfig.skipVersion() }
                builder.setNegativeButton(R.string.appconfig_later) { _, _ -> AppConfig.dismissUpdate() }
                builder.setOnCancelListener { AppConfig.dismissUpdate() }
            }
            val dialog = builder.create()
            dialog.setCanceledOnTouchOutside(!forced)
            // The Update button opens the store and keeps the dialog (a forced update stays until installed).
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    open(update.storeUrl ?: AppConfig.playStoreUrl())
                }
            }
            return dialog
        }

        private fun noticeDialog(notice: Notice): Dialog {
            val done = { AppConfig.markNoticeSeen(notice.id) }
            val builder = AlertDialog.Builder(activity)
                .setTitle((if (notice.test) activity.getString(R.string.appconfig_test_prefix) else "") + notice.title)
                .setView(scrollingText(activity, notice.body))
                .setCancelable(!notice.mustConfirm)
                .setPositiveButton(if (notice.mustConfirm) R.string.appconfig_confirm else R.string.appconfig_close) { _, _ -> done() }
            if (!notice.mustConfirm) builder.setOnCancelListener { done() }
            val dialog = builder.create()
            dialog.setCanceledOnTouchOutside(!notice.mustConfirm)
            // Opening the link does not count as confirming.
            notice.link?.let { link ->
                dialog.setButton(AlertDialog.BUTTON_NEUTRAL, activity.getString(R.string.appconfig_open_link)) { _, _ -> }
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { open(link) }
                }
            }
            return dialog
        }

        private fun replyDialog(item: FeedbackItem): Dialog {
            val column = column(activity)
            column.addView(TextView(activity).apply { text = item.reply.orEmpty() })
            column.addView(TextView(activity).apply {
                text = activity.getString(R.string.appconfig_your_message) + "\n" + item.message
                textSize = 13f
                alpha = 0.7f
                setPadding(0, dp(activity, 12), 0, 0)
            })
            return AlertDialog.Builder(activity)
                .setTitle(R.string.appconfig_reply_title)
                .setView(ScrollView(activity).apply { addView(column) })
                .setPositiveButton(R.string.appconfig_close) { _, _ -> AppConfig.markReplySeen(item) }
                .setOnCancelListener { AppConfig.markReplySeen(item) }
                .create()
        }
    }

    private class FeedbackForm(val activity: AppCompatActivity) {
        private val categories = listOf(
            FeedbackCategory.BUG to R.string.appconfig_feedback_bug,
            FeedbackCategory.REQUEST to R.string.appconfig_feedback_request,
            FeedbackCategory.OTHER to R.string.appconfig_feedback_other,
        )
        private val group = RadioGroup(activity).apply { orientation = RadioGroup.HORIZONTAL }
        private val message = EditText(activity).apply {
            hint = activity.getString(R.string.appconfig_feedback_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 4
            gravity = Gravity.TOP or Gravity.START
        }
        private val contact = EditText(activity).apply {
            hint = activity.getString(R.string.appconfig_feedback_contact)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setSingleLine()
        }
        private val status = TextView(activity).apply { textSize = 13f; visibility = View.GONE }
        private val history = column(activity)
        private lateinit var dialog: AlertDialog
        private var sending = false

        fun show() {
            categories.forEachIndexed { i, (_, label) ->
                group.addView(RadioButton(activity).apply { id = View.generateViewId(); setText(label); tag = i })
            }
            group.check(group.getChildAt(0).id)
            val column = column(activity).apply {
                addView(group)
                addView(message)
                addView(contact)
                addView(status)
                addView(history)
            }
            val titleView = TextView(activity).apply {
                setText(R.string.appconfig_feedback_title)
                textSize = 20f
                setPadding(dp(activity, 24), dp(activity, 20), dp(activity, 24), dp(activity, 4))
                // Long-press the title to copy the install ID (for adding this device as a tester).
                // It is deliberately not shown in the dialog.
                setOnLongClickListener {
                    val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("install id", AppConfig.installId))
                    Toast.makeText(activity, R.string.appconfig_install_id_copied, Toast.LENGTH_SHORT).show()
                    true
                }
            }
            dialog = AlertDialog.Builder(activity)
                .setCustomTitle(titleView)
                .setView(ScrollView(activity).apply { addView(column) })
                .setPositiveButton(R.string.appconfig_feedback_send, null)
                .setNegativeButton(R.string.appconfig_close, null)
                .create()
            dialog.setOnShowListener {
                // Sending keeps the dialog open to show the result, so the button is wired by hand.
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { send() }
            }
            dialog.show()
            activity.lifecycleScope.launch { loadHistory() }
        }

        private fun send() {
            val text = message.text.toString().trim()
            if (text.isEmpty() || sending) return
            sending = true
            val send = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            send.isEnabled = false
            val category = categories[group.findViewById<View>(group.checkedRadioButtonId).tag as Int].first
            activity.lifecycleScope.launch {
                val ok = AppConfig.sendFeedback(text, category, contact.text.toString().trim()).isSuccess
                sending = false
                send.isEnabled = true
                status.visibility = View.VISIBLE
                status.setText(if (ok) R.string.appconfig_feedback_sent else R.string.appconfig_feedback_failed)
                if (ok) {
                    message.setText("")
                    loadHistory()
                }
            }
        }

        private suspend fun loadHistory() {
            AppConfig.fetchFeedback().onSuccess { items ->
                history.removeAllViews()
                if (items.isEmpty()) return@onSuccess
                history.addView(TextView(activity).apply {
                    setText(R.string.appconfig_feedback_history)
                    textSize = 15f
                    setPadding(0, dp(activity, 16), 0, dp(activity, 4))
                })
                items.forEach { item ->
                    history.addView(TextView(activity).apply {
                        val date = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(item.time))
                        val reply = item.reply?.let { "\n" + activity.getString(R.string.appconfig_feedback_reply) + "：" + it }.orEmpty()
                        text = date + "\n" + item.message + reply
                        textSize = 13f
                        setPadding(0, dp(activity, 6), 0, dp(activity, 6))
                    })
                }
                items.filter { it.reply != null }.forEach(AppConfig::markReplySeen)
            }
        }
    }

    private fun column(context: Context) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(context, 24), dp(context, 8), dp(context, 24), dp(context, 8))
    }

    private fun scrollingText(context: Context, text: String) = ScrollView(context).apply {
        addView(column(context).apply { addView(TextView(context).apply { this.text = text; textSize = 15f }) })
    }

    private fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()

    private fun openUrl(context: Context, url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
        }
    }
}
