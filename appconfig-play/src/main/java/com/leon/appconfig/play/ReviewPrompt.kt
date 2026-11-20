package com.leon.appconfig.play

import android.app.Activity
import android.util.Log
import com.google.android.play.core.review.ReviewManagerFactory
import com.leon.appconfig.AppConfig

/**
 * Google Play in-app review, gated by the server's review rule (launches, days installed,
 * no recent crash, cooldown). Call it at a natural pause, e.g. after the user finished something,
 * not on launch. Play itself may still decide not to show the dialog.
 */
object ReviewPrompt {
    private const val TAG = "ReviewPrompt"

    /** Returns true if a review flow was started. */
    fun maybeAsk(activity: Activity): Boolean {
        if (!AppConfig.isInitialized || !AppConfig.shouldAskForReview()) return false
        // Count it as asked now: Play does not tell us whether the dialog was shown.
        AppConfig.markReviewAsked()
        val manager = ReviewManagerFactory.create(activity)
        manager.requestReviewFlow().addOnCompleteListener { request ->
            if (!request.isSuccessful) {
                Log.w(TAG, "review flow unavailable", request.exception)
                return@addOnCompleteListener
            }
            if (activity.isFinishing || activity.isDestroyed) return@addOnCompleteListener
            manager.launchReviewFlow(activity, request.result)
        }
        return true
    }
}
