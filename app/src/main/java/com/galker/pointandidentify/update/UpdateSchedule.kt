// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// Version 1.0
package com.galker.pointandidentify.update

import com.galker.pointandidentify.config.AppConfig

/**
 * When the automatic update check runs:
 *   - on every launch of the app (no previous attempt in this process),
 *   - when the user returns to the app after UPDATE_CHECK_INTERVAL_MS,
 *   - and again after UPDATE_RETRY_INTERVAL_MS when the last attempt failed (for example without a network).
 * Times are monotonic milliseconds (SystemClock.elapsedRealtime); 0 means "never attempted".
 */
object UpdateSchedule {

    fun isDue(lastAttemptMs: Long, lastAttemptSucceeded: Boolean, nowMs: Long): Boolean {
        if (lastAttemptMs == 0L) return true
        val interval = if (lastAttemptSucceeded) AppConfig.UPDATE_CHECK_INTERVAL_MS else AppConfig.UPDATE_RETRY_INTERVAL_MS
        return nowMs - lastAttemptMs >= interval
    }
}
