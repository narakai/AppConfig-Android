package com.leon.appconfig

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Update prompts, in-app notices, remote config, review prompts, cross-promotion and feedback,
 * served by your own app-config server. The app is identified by its applicationId.
 *
 * ```
 * // Application.onCreate
 * AppConfig.init(this, baseUrl = BuildConfig.APP_CONFIG_URL, tester = BuildConfig.DEBUG)
 * ```
 * Then show `AppConfigHost()` (appconfig-compose) at the root of the UI, or call [refresh] and
 * read [state] yourself. Network failures never throw: the last good answer is used instead,
 * so a forced update still applies offline.
 */
object AppConfig {
    private const val TAG = "AppConfig"
    private const val PREFS = "com.leon.appconfig"
    private const val K_INSTALL_ID = "install_id"
    private const val K_FIRST_LAUNCH = "first_launch"
    private const val K_LAUNCHES = "launches"
    private const val K_LAST_CRASH = "last_crash"
    private const val K_LAST_REVIEW = "last_review"
    private const val K_LAST_RESPONSE = "last_response"
    private const val K_SEEN_NOTICES = "seen_notices"
    private const val K_SKIPPED_VERSION = "skipped_version"
    private const val K_PING_DAY = "ping_day"
    private const val K_HAS_FEEDBACK = "has_feedback"
    private const val K_SEEN_REPLIES = "seen_replies"
    private const val DAY_MS = 86_400_000L

    @SuppressLint("StaticFieldLeak") // application context only
    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences
    private lateinit var baseUrl: String
    private var testerBuild = false

    private val _state = MutableStateFlow(AppConfigState())

    /** The latest known state. Updated by [refresh] and by the mark/skip calls. */
    val state: StateFlow<AppConfigState> = _state.asStateFlow()

    private val refreshLock = Mutex()
    private var lastRefreshAt = 0L

    val isInitialized: Boolean get() = ::prefs.isInitialized

    /**
     * Call once from Application.onCreate.
     * @param baseUrl your server, e.g. "https://app-config.example.workers.dev"
     * @param tester true for debug builds: they also see test-only notices, config and versions
     */
    fun init(context: Context, baseUrl: String, tester: Boolean = false) {
        if (isInitialized) return
        this.context = context.applicationContext
        this.baseUrl = baseUrl.trimEnd('/')
        this.testerBuild = tester
        prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        val edit = prefs.edit()
        if (!prefs.contains(K_FIRST_LAUNCH)) edit.putLong(K_FIRST_LAUNCH, System.currentTimeMillis())
        edit.putInt(K_LAUNCHES, prefs.getInt(K_LAUNCHES, 0) + 1).apply()
        recordCrashes()

        // Start from the last good answer so config and forced updates work before (or without) the network.
        prefs.getString(K_LAST_RESPONSE, null)?.let { cached ->
            runCatching { applyResponse(JSONObject(cached)) }.onFailure { Log.w(TAG, "bad cached response", it) }
        }
    }

    /** Random ID for this install, unrelated to the user, account or device. Testers give it to you. */
    val installId: String
        get() = prefs.getString(K_INSTALL_ID, null)
            ?: UUID.randomUUID().toString().also { prefs.edit().putString(K_INSTALL_ID, it).apply() }

    val versionCode: Long by lazy {
        val info = packageInfo() ?: return@lazy 0L
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    }

    val versionName: String? by lazy { packageInfo()?.versionName }

    private fun packageInfo() = try {
        context.packageManager.getPackageInfo(context.packageName, 0)
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    // ---------------------------------------------------------------- check

    /**
     * Fetches updates, notices, config and promos, reports the daily ping and checks for feedback replies.
     * @param minIntervalMs skip the network if the last refresh was more recent than this
     */
    suspend fun refresh(minIntervalMs: Long = 0): AppConfigState = withContext(Dispatchers.IO) {
        check(isInitialized) { "Call AppConfig.init() first" }
        refreshLock.withLock {
            val now = System.currentTimeMillis()
            if (_state.value.loaded && now - lastRefreshAt < minIntervalMs) return@withLock
            lastRefreshAt = now
            try {
                val url = "$baseUrl/v1/check?app=${enc(context.packageName)}&platform=android" +
                    "&versionCode=$versionCode&lang=${enc(Locale.getDefault().toLanguageTag())}" +
                    "&installId=${enc(installId)}" + if (testerBuild) "&test=1" else ""
                val response = Http.get(url)
                if (response.ok) {
                    val json = JSONObject(response.body)
                    prefs.edit().putString(K_LAST_RESPONSE, response.body).apply()
                    applyResponse(json)
                } else {
                    Log.w(TAG, "check failed: HTTP ${response.code}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "check failed", e)
            }
            _state.update { it.copy(loaded = true) }
            ping()
            refreshReplies()
        }
        _state.value
    }

    private fun applyResponse(json: JSONObject) {
        val seen = prefs.getStringSet(K_SEEN_NOTICES, emptySet()).orEmpty()
        val skipped = prefs.getLong(K_SKIPPED_VERSION, 0)
        val update = json.optJSONObject("update")?.let(::parseUpdate)
            // The cached answer may be from an older install of the app: judge it against this version.
            ?.let { u ->
                when {
                    versionCode >= u.latestVersionCode -> null
                    versionCode < u.minVersionCode -> u.copy(type = UpdateInfo.Type.FORCE)
                    u.latestVersionCode <= skipped -> null
                    else -> u.copy(type = UpdateInfo.Type.SOFT)
                }
            }
        val config = json.optJSONObject("config")?.let { obj ->
            obj.keys().asSequence().associateWith { key -> unwrap(obj.opt(key)) }
        }.orEmpty()
        _state.update {
            it.copy(
                loaded = true,
                update = update,
                notices = json.optJSONArray("notices").objects().map(::parseNotice).filter { n -> n.id.toString() !in seen },
                promos = json.optJSONArray("promos").objects().map(::parsePromo),
                review = json.optJSONObject("review")?.let(::parseReview),
                tester = json.optBoolean("tester"),
                config = config,
            )
        }
    }

    // ---------------------------------------------------------------- config

    fun getBoolean(key: String, default: Boolean): Boolean = _state.value.config[key] as? Boolean ?: default
    fun getInt(key: String, default: Int): Int = (_state.value.config[key] as? Number)?.toInt() ?: default
    fun getLong(key: String, default: Long): Long = (_state.value.config[key] as? Number)?.toLong() ?: default
    fun getDouble(key: String, default: Double): Double = (_state.value.config[key] as? Number)?.toDouble() ?: default
    fun getString(key: String, default: String): String = _state.value.config[key] as? String ?: default

    /** Raw value: Boolean, Number, String, Map<String, Any?>, List<Any?> or null. */
    fun get(key: String): Any? = _state.value.config[key]

    // ---------------------------------------------------------------- notices and updates

    fun markNoticeSeen(id: Long) {
        val seen = prefs.getStringSet(K_SEEN_NOTICES, emptySet()).orEmpty() + id.toString()
        prefs.edit().putStringSet(K_SEEN_NOTICES, seen).apply()
        _state.update { s -> s.copy(notices = s.notices.filterNot { it.id == id }) }
    }

    /** "Later": hide a soft update until the next launch. Forced updates cannot be dismissed. */
    fun dismissUpdate() {
        _state.update { s -> if (s.update?.type == UpdateInfo.Type.SOFT) s.copy(update = null) else s }
    }

    /** "Skip this version": no soft prompt again until a newer version is published. */
    fun skipVersion() {
        val update = _state.value.update ?: return
        if (update.type != UpdateInfo.Type.SOFT) return
        prefs.edit().putLong(K_SKIPPED_VERSION, update.latestVersionCode).apply()
        _state.update { it.copy(update = null) }
    }

    /** Where the update and promo buttons go when the server gives no URL. */
    fun playStoreUrl(appId: String = context.packageName) =
        "https://play.google.com/store/apps/details?id=$appId"

    // ---------------------------------------------------------------- feedback

    /** Sends feedback. Returns its ID, or a failure (no network, too many requests, ...). */
    suspend fun sendFeedback(
        message: String,
        category: FeedbackCategory = FeedbackCategory.OTHER,
        contact: String? = null,
    ): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject()
                .put("app", context.packageName)
                .put("platform", "android")
                .put("installId", installId)
                .put("category", category.wire)
                .put("message", message)
                .put("contact", contact?.takeIf { it.isNotBlank() })
                .put("versionCode", versionCode)
                .put("versionName", versionName)
                .put("osVersion", Build.VERSION.RELEASE)
                .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                .put("lang", Locale.getDefault().toLanguageTag())
            val response = Http.post("$baseUrl/v1/feedback", body.toString())
            if (!response.ok) error("HTTP ${response.code}")
            prefs.edit().putBoolean(K_HAS_FEEDBACK, true).apply()
            JSONObject(response.body).getLong("id")
        }.onFailure { Log.w(TAG, "feedback failed", it) }
    }

    /** This install's feedback with your replies, newest first. */
    suspend fun fetchFeedback(): Result<List<FeedbackItem>> = withContext(Dispatchers.IO) {
        runCatching {
            val response = Http.get("$baseUrl/v1/feedback?app=${enc(context.packageName)}&installId=${enc(installId)}")
            if (!response.ok) error("HTTP ${response.code}")
            JSONObject(response.body).optJSONArray("feedback").objects().map(::parseFeedback)
        }
    }

    fun markReplySeen(item: FeedbackItem) {
        val seen = prefs.getStringSet(K_SEEN_REPLIES, emptySet()).orEmpty() + replyKey(item)
        prefs.edit().putStringSet(K_SEEN_REPLIES, seen).apply()
        _state.update { s -> s.copy(unreadReplies = s.unreadReplies.filterNot { it.id == item.id }) }
    }

    // An edited reply counts as new.
    private fun replyKey(item: FeedbackItem) = "${item.id}:${item.repliedAt}"

    private fun refreshReplies() {
        if (!prefs.getBoolean(K_HAS_FEEDBACK, false)) return
        try {
            val response = Http.get("$baseUrl/v1/feedback?app=${enc(context.packageName)}&installId=${enc(installId)}")
            if (!response.ok) return
            val seen = prefs.getStringSet(K_SEEN_REPLIES, emptySet()).orEmpty()
            val unread = JSONObject(response.body).optJSONArray("feedback").objects().map(::parseFeedback)
                .filter { it.reply != null && replyKey(it) !in seen }
            _state.update { it.copy(unreadReplies = unread) }
        } catch (e: Exception) {
            Log.w(TAG, "reply check failed", e)
        }
    }

    // ---------------------------------------------------------------- review

    /** True when the server allows a review prompt and this install meets its conditions. */
    fun shouldAskForReview(): Boolean {
        val rule = _state.value.review ?: return false
        val now = System.currentTimeMillis()
        return prefs.getInt(K_LAUNCHES, 0) >= rule.minLaunches &&
            now - prefs.getLong(K_FIRST_LAUNCH, now) >= rule.minDays * DAY_MS &&
            now - prefs.getLong(K_LAST_CRASH, 0) >= rule.noCrashDays * DAY_MS &&
            now - prefs.getLong(K_LAST_REVIEW, 0) >= rule.cooldownDays * DAY_MS
    }

    fun markReviewAsked() {
        prefs.edit().putLong(K_LAST_REVIEW, System.currentTimeMillis()).apply()
    }

    private fun recordCrashes() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { prefs.edit().putLong(K_LAST_CRASH, System.currentTimeMillis()).commit() }
            previous?.uncaughtException(thread, error)
        }
    }

    // ---------------------------------------------------------------- daily ping

    /** One anonymous ping per install per day, for active users and version share. */
    private fun ping() {
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
        if (prefs.getString(K_PING_DAY, null) == day) return
        try {
            val body = JSONObject()
                .put("app", context.packageName)
                .put("platform", "android")
                .put("installId", installId)
                .put("versionCode", versionCode)
                .put("versionName", versionName)
                .put("osVersion", Build.VERSION.RELEASE)
                .put("lang", Locale.getDefault().toLanguageTag())
            val response = Http.post("$baseUrl/v1/ping", body.toString())
            // 404 = app not registered on the server yet: don't retry all day.
            if (response.ok || response.code == 404) prefs.edit().putString(K_PING_DAY, day).apply()
        } catch (e: Exception) {
            Log.w(TAG, "ping failed", e)
        }
    }

    // ---------------------------------------------------------------- parsing

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun JSONObject.str(key: String): String? = if (isNull(key)) null else optString(key).ifEmpty { null }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else List(length()) { optJSONObject(it) }.filterNotNull()

    private fun unwrap(v: Any?): Any? = when (v) {
        null, JSONObject.NULL -> null
        is JSONObject -> v.keys().asSequence().associateWith { unwrap(v.opt(it)) }
        is JSONArray -> List(v.length()) { unwrap(v.opt(it)) }
        else -> v
    }

    private fun parseUpdate(o: JSONObject) = UpdateInfo(
        type = if (o.optString("type") == "force") UpdateInfo.Type.FORCE else UpdateInfo.Type.SOFT,
        latestVersionCode = o.optLong("latestVersionCode"),
        latestVersionName = o.str("latestVersionName"),
        minVersionCode = o.optLong("minVersionCode"),
        storeUrl = o.str("storeUrl"),
        title = o.str("title"),
        body = o.str("body"),
    )

    private fun parseNotice(o: JSONObject) = Notice(
        id = o.optLong("id"),
        title = o.optString("title"),
        body = o.optString("body"),
        link = o.str("link"),
        mustConfirm = o.optBoolean("mustConfirm"),
        test = o.optBoolean("test"),
    )

    private fun parsePromo(o: JSONObject) = Promo(
        id = o.optLong("id"),
        appId = o.optString("appId"),
        title = o.optString("title"),
        body = o.str("body"),
        iconUrl = o.str("iconUrl"),
        storeUrl = o.str("storeUrl"),
    )

    private fun parseReview(o: JSONObject) = ReviewRule(
        minLaunches = o.optInt("minLaunches", 10),
        minDays = o.optInt("minDays", 7),
        noCrashDays = o.optInt("noCrashDays", 3),
        cooldownDays = o.optInt("cooldownDays", 120),
    )

    private fun parseFeedback(o: JSONObject) = FeedbackItem(
        id = o.optLong("id"),
        time = o.optLong("ts"),
        category = FeedbackCategory.of(o.str("category")),
        message = o.optString("message"),
        status = o.optString("status"),
        reply = o.str("reply"),
        repliedAt = if (o.isNull("repliedAt")) null else o.optLong("repliedAt"),
    )
}
