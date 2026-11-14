// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// Version 1.2
package com.galker.pointandidentify.domain

import com.galker.pointandidentify.config.AppConfig
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sqrt

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
 * The "current place" shown when the camera points at the ground nearby: the settlement nearest to the AIMED GROUND
 * POINT, the point at aimDistanceM from the observer in the camera azimuth (AimRay). Straight down that point lies
 * under the observer, so the nearest settlement is shown whatever the compass says (the azimuth of a camera pointing
 * down is meaningless and jumps with every small movement). Lowering the camera moves the aimed point towards the
 * observer, so it can never bring up a farther place. Without a known ground point (no terrain data on the way) the
 * point under the observer is used. The previous choice is kept until another settlement is nearer to the aimed point
 * by CITY_SWITCH_MARGIN_M, so hand shake does not swap places.
 * Field finding 2026-11-13: the earlier choice by azimuth alone showed settlements 9 to 15 km away, a different one
 * on every shot, from a point 350 m from the nearest settlement.
 */
object CityPicker {

    /**
     * Horizontal distance, m, from the aimed ground point to a place. Plane geometry around the observer (law of
     * cosines); the error over the CITY_SEARCH_RADIUS_M range is far below the switch margin.
     */
    fun distanceToAimM(e: TargetEvaluation, azimuthDeg: Double, aimDistanceM: Double): Double {
        val d = max(aimDistanceM, 0.0)
        val square = e.distanceM * e.distanceM + d * d - 2.0 * e.distanceM * d * cos(Math.toRadians(azimuthDeg - e.bearingDeg))
        return sqrt(max(square, 0.0)) // rounding can give a tiny negative value when the point is on the place
    }

    fun pick(
        candidates: List<TargetEvaluation>, azimuthDeg: Double, aimDistanceM: Double?, previous: TargetEvaluation?
    ): TargetEvaluation? {
        if (candidates.isEmpty()) return null
        val aim = aimDistanceM ?: 0.0
        val best = candidates.minBy { distanceToAimM(it, azimuthDeg, aim) }
        val keep = previous?.let { p -> candidates.firstOrNull { keyOf(it) == keyOf(p) } } ?: return best
        if (keyOf(best) == keyOf(keep)) return keep
        val gainM = distanceToAimM(keep, azimuthDeg, aim) - distanceToAimM(best, azimuthDeg, aim)
        return if (gainM > AppConfig.CITY_SWITCH_MARGIN_M) best else keep
    }
}
