package com.vehicleaudit.core.engine.rules

import com.vehicleaudit.core.domain.RuleFinding
import com.vehicleaudit.core.domain.RuleId
import com.vehicleaudit.core.domain.TrustLevel
import com.vehicleaudit.core.domain.VehicleCase
import com.vehicleaudit.core.engine.AuditRule
import com.vehicleaudit.core.engine.TrustWeights
import com.vehicleaudit.core.engine.TrustWeights.of

/**
 * R5 多来源单据冲突（伪造记录识别）。
 *
 * 背景：维保记录本身可能是伪造的——调表后补一套"里程连贯"的假保养单。
 * 单看假单据无法识别，但与独立渠道（检测站/保险/厂家后台）的同期记录
 * 对撞时矛盾就会暴露。
 *
 * 判定：把事件按 90 天窗口配对，窗口内两张不同来源单据的里程差超过
 * 噪声带即构成冲突；若其中一方来自高可信渠道而另一方是低可信/未核验
 * 渠道，则同时标注低可信单据疑似伪造。冲突本身只给中等强度分，
 * 因为"哪一方为真"需要人工复核，引擎不越权下事实认定。
 */
class SourceConflictRule : AuditRule {
    override val id = RuleId.SOURCE_CONFLICT

    override fun evaluate(case: VehicleCase): RuleFinding {
        val events = case.historyEvents
        if (events.size < 2) {
            return RuleFinding(
                ruleId = id,
                title = "多来源单据冲突分析",
                detail = "历史单据不足 2 条，本规则不适用。",
                tamperScore = 0.0,
                weight = 0.0,
                notApplicable = true
            )
        }

        data class Conflict(val aId: String, val bId: String, val gapKm: Long, val suspectForgeryId: String?)

        val conflicts = buildList {
            for (i in events.indices) {
                for (j in i + 1 until events.size) {
                    val a = events[i]
                    val b = events[j]
                    if (a.sourceKind == b.sourceKind) continue
                    val daysApart = kotlin.math.abs(a.recordedAtEpoch - b.recordedAtEpoch) / MS_PER_DAY
                    if (daysApart > WINDOW_DAYS) continue
                    val gap = kotlin.math.abs(a.odometerKm - b.odometerKm)
                    if (gap <= TrustWeights.NOISE_BAND_KM) continue

                    val suspect = when {
                        a.trustLevel == TrustLevel.LOW || a.trustLevel == TrustLevel.UNTRUSTED || !a.verified ->
                            if (b.verified || b.trustLevel == TrustLevel.CRYPTOGRAPHIC || b.trustLevel == TrustLevel.HIGH) a.eventId else null
                        b.trustLevel == TrustLevel.LOW || b.trustLevel == TrustLevel.UNTRUSTED || !b.verified ->
                            if (a.verified || a.trustLevel == TrustLevel.CRYPTOGRAPHIC || a.trustLevel == TrustLevel.HIGH) b.eventId else null
                        else -> null
                    }
                    add(Conflict(a.eventId, b.eventId, gap, suspect))
                }
            }
        }

        if (conflicts.isEmpty()) {
            return RuleFinding(
                ruleId = id,
                title = "多来源单据无冲突",
                detail = "同一时间窗口内不同渠道的里程记录相互吻合，未发现伪造迹象。",
                tamperScore = -0.2,
                weight = events.maxOf { of(it.trustLevel) },
                evidenceRefs = events.map { it.eventId }
            )
        }

        val maxGap = conflicts.maxOf { it.gapKm }
        val score = minOf(
            0.35 + TrustWeights.divergenceScore(maxGap, halfSaturation = 20_000.0) * 0.5,
            0.85
        )
        val forgeryHints = conflicts.mapNotNull { it.suspectForgeryId }.distinct()

        return RuleFinding(
            ruleId = id,
            title = "同期多来源单据里程冲突",
            detail = buildString {
                appendLine("在 ±${WINDOW_DAYS} 天窗口内发现 ${conflicts.size} 组跨渠道里程冲突：")
                conflicts.forEach { c ->
                    appendLine("  · ${c.aId} ↔ ${c.bId}：里程差 ${"%,d".format(c.gapKm)} km" +
                        (c.suspectForgeryId?.let { "；$it 来自低可信/未核验渠道，疑似伪造单据" } ?: ""))
                }
                if (forgeryHints.isEmpty()) {
                    append("双方可信度相当，无法自动判定真伪，需人工调取原始单据复核。")
                }
            },
            // 冲突指向"记录体系不可信"，与调表高度相关但非直接等同，封顶 0.85
            tamperScore = score,
            weight = events.maxOf { of(it.trustLevel) },
            evidenceRefs = conflicts.flatMap { listOf(it.aId, it.bId) }.distinct()
        )
    }

    private companion object {
        const val WINDOW_DAYS = 90L
        const val MS_PER_DAY = 86_400_000L
    }
}
