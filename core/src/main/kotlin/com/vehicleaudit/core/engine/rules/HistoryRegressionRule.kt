package com.vehicleaudit.core.engine.rules

import com.vehicleaudit.core.domain.RuleFinding
import com.vehicleaudit.core.domain.RuleId
import com.vehicleaudit.core.domain.VehicleCase
import com.vehicleaudit.core.engine.AuditRule
import com.vehicleaudit.core.engine.TrustWeights
import com.vehicleaudit.core.engine.TrustWeights.of

/**
 * R3 历史事件时序矛盾。
 *
 * 事实逻辑：真实车辆的里程随时间单调不减。把全部带时间戳的单据按时间排序，
 * 任何"后单里程 < 前单里程"的倒挂都是调表或伪造单据的直接迹象。
 * 倒挂差值越大、参与单据可信度越高，证据越强。
 */
class HistoryRegressionRule : AuditRule {
    override val id = RuleId.HISTORY_REGRESSION

    override fun evaluate(case: VehicleCase): RuleFinding {
        if (case.historyEvents.size < 2) {
            return RuleFinding(
                ruleId = id,
                title = "历史时序矛盾分析",
                detail = "历史单据不足 2 条，无法做时序分析，本规则不适用。",
                tamperScore = 0.0,
                weight = 0.0,
                notApplicable = true
            )
        }

        val sorted = case.historyEvents.sortedBy { it.recordedAtEpoch }
        data class Regression(val earlierId: String, val laterId: String, val gapKm: Long, val weight: Double)

        val regressions = buildList {
            for (i in 1 until sorted.size) {
                val prev = sorted[i - 1]
                val cur = sorted[i]
                val gap = prev.odometerKm - cur.odometerKm
                if (gap > TrustWeights.NOISE_BAND_KM) {
                    // 两张单据中可信度较低者决定该倒挂的证据强度（短板原则）
                    add(Regression(prev.eventId, cur.eventId, gap, minOf(of(prev.trustLevel), of(cur.trustLevel))))
                }
            }
        }

        if (regressions.isEmpty()) {
            return RuleFinding(
                ruleId = id,
                title = "历史单据时序单调",
                detail = "${sorted.size} 条历史单据的里程随时间单调不减，无时序倒挂。",
                tamperScore = -0.3,
                weight = sorted.maxOf { of(it.trustLevel) },
                evidenceRefs = sorted.map { it.eventId }
            )
        }

        val worst = regressions.maxBy { it.gapKm }
        val score = minOf(
            TrustWeights.divergenceScore(worst.gapKm, halfSaturation = 20_000.0) +
                minOf(0.05 * (regressions.size - 1), 0.15),
            1.0
        )

        return RuleFinding(
            ruleId = id,
            title = "历史单据出现里程时序倒挂",
            detail = buildString {
                appendLine("发现 ${regressions.size} 处时间在后、里程反而更低的倒挂：")
                regressions.forEach { r ->
                    appendLine("  · 事件 ${r.laterId} 比 ${r.earlierId} 里程低 ${"%,d".format(r.gapKm)} km")
                }
                append("真实车辆里程不可能随时间减少；若单据均经渠道核验，则指向调表，否则指向单据伪造，两者均构成交易瑕疵。")
            },
            tamperScore = score,
            weight = regressions.maxOf { it.weight },
            evidenceRefs = regressions.flatMap { listOf(it.earlierId, it.laterId) }.distinct()
        )
    }
}
