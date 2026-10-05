// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// Version 1.0
package com.galker.pointandidentify

import com.galker.pointandidentify.config.AppConfig
import com.galker.pointandidentify.update.UpdateSchedule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateScheduleTest {

    private val start = 1_000L // any non-zero monotonic time

    @Test
    fun firstCheckOfALaunchIsAlwaysDue() {
        assertTrue(UpdateSchedule.isDue(0L, false, start))
        assertTrue(UpdateSchedule.isDue(0L, true, start))
    }

    @Test
    fun successfulCheckRepeatsOnlyAfterTheInterval() {
        assertFalse(UpdateSchedule.isDue(start, true, start + AppConfig.UPDATE_CHECK_INTERVAL_MS - 1))
        assertTrue(UpdateSchedule.isDue(start, true, start + AppConfig.UPDATE_CHECK_INTERVAL_MS))
    }

    @Test
    fun failedCheckRetriesSoonerButNotImmediately() {
        assertFalse(UpdateSchedule.isDue(start, false, start + AppConfig.UPDATE_RETRY_INTERVAL_MS - 1))
        assertTrue(UpdateSchedule.isDue(start, false, start + AppConfig.UPDATE_RETRY_INTERVAL_MS))
    }
}
