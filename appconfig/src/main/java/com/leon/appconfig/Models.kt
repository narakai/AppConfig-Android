package com.leon.appconfig

/** What the server says about updating this app. */
data class UpdateInfo(
    val type: Type,
    val latestVersionCode: Long,
    val latestVersionName: String?,
    /** Versions below this must update; the dialog cannot be dismissed. */
    val minVersionCode: Long,
    val storeUrl: String?,
    /** Custom title and message; null means use the library's default text. */
    val title: String?,
    val body: String?,
) {
    enum class Type { FORCE, SOFT }
}

data class Notice(
    val id: Long,
    val title: String,
    val body: String,
    val link: String?,
    /** Only a confirm button: no dismissing by tapping outside or pressing back. */
    val mustConfirm: Boolean,
    /** Shown only to testers. */
    val test: Boolean,
)

/** Another app of yours to recommend. */
data class Promo(
    val id: Long,
    val appId: String,
    val title: String,
    val body: String?,
    val iconUrl: String?,
    val storeUrl: String?,
)

/** When to ask for a store review. Null in [AppConfigState.review] means never. */
data class ReviewRule(
    val minLaunches: Int,
    val minDays: Int,
    val noCrashDays: Int,
    val cooldownDays: Int,
)

data class FeedbackItem(
    val id: Long,
    val time: Long,
    val category: FeedbackCategory,
    val message: String,
    val status: String,
    val reply: String?,
    val repliedAt: Long?,
)

enum class FeedbackCategory(val wire: String) {
    BUG("bug"), REQUEST("request"), OTHER("other");

    companion object {
        fun of(wire: String?) = entries.firstOrNull { it.wire == wire } ?: OTHER
    }
}

/**
 * Everything the app needs from the server, already filtered for this device:
 * notices the user has seen and versions they skipped are removed.
 */
data class AppConfigState(
    /** True once the first check finished (from the network or the offline cache). */
    val loaded: Boolean = false,
    val update: UpdateInfo? = null,
    val notices: List<Notice> = emptyList(),
    val promos: List<Promo> = emptyList(),
    val review: ReviewRule? = null,
    /** Replies to this user's feedback they have not seen yet. */
    val unreadReplies: List<FeedbackItem> = emptyList(),
    /** This install sees test-only notices, config and versions. */
    val tester: Boolean = false,
    /** Remote config values: Boolean, Number, String, Map, List or null. */
    val config: Map<String, Any?> = emptyMap(),
)
