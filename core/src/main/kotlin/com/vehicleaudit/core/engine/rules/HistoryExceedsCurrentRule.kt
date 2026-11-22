package com.vehicleaudit.core.engine.rules

import com.vehicleaudit.core.domain.RuleFinding
import com.vehicleaudit.core.domain.RuleId
import com.vehicleaudit.core.domain.VehicleCase
import com.vehicleaudit.core.engine.AuditRule
import com.vehicleaudit.core.engine.TrustWeights
import com.vehicleaudit.core.engine.TrustWeights.of

/**
 * R4 历史里程高于当前表显。
 *
 * 事实逻辑：这是最朴素也最难辩驳的矛盾——一年前年检时跑了 12 万公里，
 * 今天表显不可能只有 9 万公里。该规则同时是"真实里程下限"的重要来源：
 * 取历史最高里程作为保守下限（不依赖任何速度假设）。
 */
class HistoryExceedsCurrentRule : AuditRule {
    override val id = RuleId.HISTORY_EXCEEDS_CURRENT

    override fun evaluate(case: VehicleCase): RuleFinding {
        if (case.historyEvents.isEmpty()) {
            return RuleFinding(
                ruleId = id,
                title = "历史里程与表显比对",
                detail = "无历史单据，本规则不适用。",
                tamperScore = 0.0,
                weight = 0.0,
                notApplicable = true
            )
        }

        val displayed = case.displayedOdometerKm
        val exceeders = case.historyEvents
            .filter { it.odometerKm - displayed > TrustWeights.NOISE_BAND_KM }
            .sortedByDescending { it.odometerKm }

        if (exceeders.isEmpty()) {
            val highest = case.historyEvents.maxOf { it.odometerKm }
            return RuleFinding(
                ruleId = id,
                title = "历史最高里程未超过当前表显",
                detail = "历史单据最高里程 ${"%,d".format(highest)} km ≤ 表显 ${"%,d".format(displayed)} km，逻辑自洽。",
                tamperScore = -0.3,
                weight = case.historyEvents.maxOf { of(it.trustLevel) },
                evidenceRefs = case.historyEvents.map { it.eventId }
            )
        }

        val strongest = exceeders.first()
        val diff = strongest.odometerKm - displayed
        val score = TrustWeights.divergenceScore(diff, halfSaturation = 15_000.0)

        return RuleFinding(
            ruleId = id,
            title = "历史单据里程高于当前表显",
            detail = buildString {
                appendLine("以下历史时点记录的里程已高于当前表显 ${"%,d".format(displayed)} km：")
                exceeders.forEach { e ->
                    appendLine("  · ${e.eventId}（${e.sourceKind}，可信度 ${e.trustLevel}，已核验=${e.verified}）：${"%,d".format(e.odometerKm)} km")
                }
                append("过去的里程不可能大于现在，表显至少被压低 ${"%,d".format(diff)} km。")
            },
            tamperScore = score,
            weight = exceeders.maxOf { of(it.trustLevel) },
            estimatedTrueKm = strongest.odometerKm,
            evidenceRefs = exceeders.map { it.eventId }
        )
    }
}
