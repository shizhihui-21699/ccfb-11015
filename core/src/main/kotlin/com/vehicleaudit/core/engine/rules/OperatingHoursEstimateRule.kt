package com.vehicleaudit.core.engine.rules

import com.vehicleaudit.core.domain.EcuStatus
import com.vehicleaudit.core.domain.RuleFinding
import com.vehicleaudit.core.domain.RuleId
import com.vehicleaudit.core.domain.VehicleCase
import com.vehicleaudit.core.engine.AuditRule
import com.vehicleaudit.core.engine.TrustWeights
import com.vehicleaudit.core.engine.TrustWeights.divergenceScore
import com.vehicleaudit.core.engine.TrustWeights.of

/**
 * R2 工作小时/附加计数器反推里程。
 *
 * 事实逻辑：发动机、空调、发电机等单元记录累计工作小时，调表改写里程时
 * 小时计数器经常被忽略。里程 ≈ 工作小时 × 平均行驶速度。
 *
 * 不确定性处理：平均速度是假设值而非测量值，因此采用保守系数 0.8 给出
 * 里程下限，避免城市拥堵工况下的误判；extraCounters 同理取适配器换算后的
 * 公里当量参与比对。
 */
class OperatingHoursEstimateRule : AuditRule {
    override val id = RuleId.OPERATING_HOURS_ESTIMATE

    override fun evaluate(case: VehicleCase): RuleFinding {
        val candidates = case.ecuReadings.filter {
            it.ecuStatus == EcuStatus.ORIGINAL &&
                (it.operatingHours != null || it.extraCounters.isNotEmpty())
        }

        if (candidates.isEmpty()) {
            return RuleFinding(
                ruleId = id,
                title = "工作小时/计数器反推",
                detail = "未采集到工作小时或可换算里程的附加计数器，本规则不适用。",
                tamperScore = 0.0,
                weight = 0.0,
                notApplicable = true
            )
        }

        data class Estimate(val ecuId: String, val label: String, val lowerBoundKm: Long, val trustWeight: Double)

        val estimates = buildList {
            candidates.forEach { r ->
                r.operatingHours?.let { hours ->
                    // 保守系数 0.8：平均速度假设的不确定性向"低估"方向留余量
                    val lower = (hours * case.assumedAverageSpeedKmh * CONSERVATISM).toLong()
                    add(Estimate(r.ecuId, "工作小时 ${"%.1f".format(hours)}h", lower, of(r.trustLevel)))
                }
                r.extraCounters.forEach { (name, kmEquivalent) ->
                    add(Estimate(r.ecuId, "计数器 $name", kmEquivalent, of(r.trustLevel)))
                }
            }
        }

        val strongest = estimates.maxByOrNull { it.lowerBoundKm }!!
        val diff = strongest.lowerBoundKm - case.displayedOdometerKm

        if (diff <= TrustWeights.NOISE_BAND_KM) {
            return RuleFinding(
                ruleId = id,
                title = "工作小时/计数器反推未见异常",
                detail = estimates.joinToString(separator = "\n") {
                    "  · ${it.ecuId}（${it.label}）保守里程下限 ${"%,d".format(it.lowerBoundKm)} km"
                } + "\n均未超过表显 ${"%,d".format(case.displayedOdometerKm)} km。",
                tamperScore = -0.15,
                weight = strongest.trustWeight
            )
        }

        val score = divergenceScore(diff, halfSaturation = 30_000.0)
        return RuleFinding(
            ruleId = id,
            title = "工作小时/计数器反推里程高于表显",
            detail = buildString {
                appendLine("按假设平均速度 ${"%.1f".format(case.assumedAverageSpeedKmh)} km/h、保守系数 $CONSERVATISM 反推：")
                estimates.sortedByDescending { it.lowerBoundKm }.forEach {
                    appendLine("  · ${it.ecuId}（${it.label}）→ 里程下限 ${"%,d".format(it.lowerBoundKm)} km")
                }
                append("最高下限高出表显 ${"%,d".format(diff)} km。")
            },
            tamperScore = score,
            weight = strongest.trustWeight,
            estimatedTrueKm = strongest.lowerBoundKm,
            evidenceRefs = estimates.map { it.ecuId }.distinct()
        )
    }

    private companion object {
        const val CONSERVATISM = 0.8
    }
}
