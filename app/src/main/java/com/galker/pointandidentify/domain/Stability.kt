// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// Version 1.1
package com.galker.pointandidentify.domain

import com.galker.pointandidentify.config.AppConfig
import kotlin.math.abs

/** Identity of a target across passes (the evaluation objects are rebuilt on every pass). */
private fun keyOf(e: TargetEvaluation) = "${e.target.name}|${e.target.latitude}|${e.target.longitude}"

/**
 * Keeps the shown target steady while the hand shakes. The raw selection can flip between two neighbouring places
 * on every small movement; a challenger replaces the held target only when it is clearly better (score below
 * SELECTION_SWITCH_RATIO times the held score) for SELECTION_HOLD_MS, and a held target that just left the
 * crosshair stays for SELECTION_LOST_MS.
 */
class SelectionStabilizer {

    private var heldKey: String? = null
    private var held: Candidate? = null
    private var heldSeenMs = 0L
    private var challengerKey: String? = null
    private var challengerSinceMs = 0L

    /** The untruncated list: a held target ranked below the displayed candidates is still inside the crosshair. */
    private fun pool(raw: Selection): List<Candidate> = raw.all.ifEmpty { raw.candidates }

    fun stabilize(nowMs: Long, raw: Selection): Selection {
        val best = raw.best
        val bestKey = best?.let { keyOf(it) }
        val heldNow = heldKey?.let { k -> pool(raw).firstOrNull { keyOf(it.evaluation) == k && it.evaluation.visibility == Visibility.VISIBLE } }
        if (heldNow != null) {
            held = heldNow
            heldSeenMs = nowMs
        }

        if (heldKey == null) {
            if (best != null) adopt(raw, bestKey!!, nowMs)
            return raw
        }

        // The held target is not in the crosshair now.
        if (heldNow == null) {
            val lastHeld = held
            if (lastHeld != null && nowMs - heldSeenMs <= AppConfig.SELECTION_LOST_MS) {
                return raw.copy(best = lastHeld.evaluation, angleDiffDeg = lastHeld.angleDiffDeg)
            }
            reset()
            if (best != null) adopt(raw, bestKey!!, nowMs)
            return raw
        }

        if (bestKey == heldKey) {
            challengerKey = null
            return raw
        }

        // Another target is the best now: switch only when it is clearly better and stays so.
        val bestCandidate = pool(raw).firstOrNull { keyOf(it.evaluation) == bestKey }
        val clearlyBetter = bestCandidate != null && bestCandidate.score < heldNow.score * AppConfig.SELECTION_SWITCH_RATIO
        if (clearlyBetter) {
            if (challengerKey != bestKey) {
                challengerKey = bestKey
                challengerSinceMs = nowMs
            }
            if (nowMs - challengerSinceMs >= AppConfig.SELECTION_HOLD_MS) {
                adopt(raw, bestKey!!, nowMs)
                return raw
            }
        } else {
            challengerKey = null
        }
        return raw.copy(best = heldNow.evaluation, angleDiffDeg = heldNow.angleDiffDeg)
    }

    private fun adopt(raw: Selection, key: String, nowMs: Long) {
        heldKey = key
        held = pool(raw).firstOrNull { keyOf(it.evaluation) == key }
        heldSeenMs = nowMs
        challengerKey = null
    }

    private fun reset() {
        heldKey = null
        held = null
        challengerKey = null
    }
}

/**
 * The "current place" shown when the camera points down: the settlement nearest in AZIMUTH (the one the camera was
 * turned towards), not the nearest overall. Without a settlement within CITY_AZIMUTH_TOL_DEG of the camera azimuth
 * the nearest settlement overall is used. The previous choice is kept until another one is better by
 * CITY_AZIMUTH_SWITCH_MARGIN_DEG, so hand shake does not swap places.
 */
object CityPicker {

    private fun offsetDeg(azimuthDeg: Double, e: TargetEvaluation) = abs(FindGuidance.signedDiffDeg(azimuthDeg, e.bearingDeg))

    fun pick(candidates: List<TargetEvaluation>, azimuthDeg: Double, previous: TargetEvaluation?): TargetEvaluation? {
        if (candidates.isEmpty()) return null
        val aligned = candidates.filter { offsetDeg(azimuthDeg, it) <= AppConfig.CITY_AZIMUTH_TOL_DEG }
        val best = if (aligned.isNotEmpty()) {
            aligned.minWith(compareBy({ offsetDeg(azimuthDeg, it) }, { it.distanceM }))
        } else {
            candidates.minBy { it.distanceM }
        }
        val keep = previous?.let { p -> candidates.firstOrNull { keyOf(it) == keyOf(p) } } ?: return best
        if (keyOf(best) == keyOf(keep)) return keep
        if (aligned.isEmpty()) return keep // nobody in the camera direction: keep the previous place
        val keepIsAligned = offsetDeg(azimuthDeg, keep) <= AppConfig.CITY_AZIMUTH_TOL_DEG
        if (keepIsAligned && offsetDeg(azimuthDeg, best) + AppConfig.CITY_AZIMUTH_SWITCH_MARGIN_DEG >= offsetDeg(azimuthDeg, keep)) return keep
        return best
    }
}
