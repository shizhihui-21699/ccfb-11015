package com.vehicleaudit.core.engine.rules

import com.vehicleaudit.core.domain.EcuReading
import com.vehicleaudit.core.domain.EcuStatus
import com.vehicleaudit.core.domain.RuleFinding
import com.vehicleaudit.core.domain.RuleId
import com.vehicleaudit.core.domain.SourceKind
import com.vehicleaudit.core.domain.VehicleCase
import com.vehicleaudit.core.engine.AuditRule
import com.vehicleaudit.core.engine.TrustWeights
import com.vehicleaudit.core.engine.TrustWeights.divergenceScore
import com.vehicleaudit.core.engine.TrustWeights.of

/**
 * R1 跨 ECU 里程比对。
 *
 * 事实逻辑：车上多个相互独立的控制单元各自保存总里程。调表者通常只改仪表盘，
 * 即使全车刷写，也往往遗漏车身/舒适模块。任何一个独立模块的读数高于表显，
 * 高出的部分就是表显被压低的硬下限。
 *
 * 证据分层（防误报的核心）：
 * - 硬证据：原厂状态（ORIGINAL）模块的读数，可用于确定真实里程下限
 * - 软证据：状态不明（UNKNOWN）模块的读数——可能是拆车件，只能作为弱佐证，
 *   不参与里程下限计算，权重减半且输出独立措辞
 * - 排除：仪表盘自身（待证伪对象不能自证）、更换/维修模块（R6 留痕）
 * - 100km 噪声带内的差异视为测量误差
 */
class CrossEcuDivergenceRule : AuditRule {
    override val id = RuleId.CROSS_ECU_DIVERGENCE

    override fun evaluate(case: VehicleCase): RuleFinding {
        val candidates = case.ecuReadings.filter {
            it.sourceKind != SourceKind.INSTRUMENT_CLUSTER &&
                it.ecuStatus != EcuStatus.REPLACED_OR_REPAIRED &&
                it.odometerKm != null
        }
        val hard = candidates.filter { it.ecuStatus == EcuStatus.ORIGINAL }
        val soft = candidates.filter { it.ecuStatus == EcuStatus.UNKNOWN }

        if (candidates.isEmpty()) {
            return RuleFinding(
                ruleId = id,
                title = "跨 ECU 里程比对",
                detail = "无可用的独立控制单元读数（仅有仪表盘或模块均已更换），本规则不适用。",
                tamperScore = 0.0,
                weight = 0.0,
                notApplicable = true
            )
        }

        val displayed = case.displayedOdometerKm
        fun EcuReading.diff() = odometerKm!! - displayed

        val hardSupporting = hard.filter { it.diff() > TrustWeights.NOISE_BAND_KM }
        val softSupporting = soft.filter { it.diff() > TrustWeights.NOISE_BAND_KM }

        if (hardSupporting.isNotEmpty()) {
            val strongest = hardSupporting.maxBy { it.odometerKm!! }
            val maxDiff = strongest.diff()
            val corroborationBonus = minOf(0.05 * (hardSupporting.size - 1), 0.15)
            val score = minOf(divergenceScore(maxDiff, halfSaturation = 20_000.0) + corroborationBonus, 1.0)
            val weight = hardSupporting.maxOf { of(it.trustLevel) }

            return RuleFinding(
                ruleId = id,
                title = "跨 ECU 里程比对发现读数高于表显",
                detail = buildString {
                    appendLine("${hardSupporting.size} 个原厂控制单元的里程读数高于表显 ${"%,d".format(displayed)} km：")
                    hardSupporting.sortedByDescending { it.odometerKm!! }.forEach { r ->
                        appendLine("  · ${r.ecuId}：${"%,d".format(r.odometerKm)} km（可信度 ${r.trustLevel}）")
                    }
                    if (softSupporting.isNotEmpty()) {
                        appendLine("另有 ${softSupporting.size} 个状态不明模块读数同向偏高，仅作佐证（不参与里程下限）：")
                        softSupporting.forEach { appendLine("  · ${it.ecuId}：${"%,d".format(it.odometerKm)} km") }
                    }
                    append("最高原厂读数与表显差值 ${"%,d".format(maxDiff)} km，该差值构成表显被压低的硬下限。")
                },
                tamperScore = score,
                weight = weight,
                estimatedTrueKm = strongest.odometerKm,
                evidenceRefs = (hardSupporting + softSupporting).map { it.ecuId }
            )
        }

        if (softSupporting.isNotEmpty()) {
            // 只有软证据：可以提示疑点，但不足以定调。分数封顶 0.25，
            // 低于引擎的证据组门槛（>0.25），单条软证据不会触发"疑似调表"
            val strongest = softSupporting.maxBy { it.odometerKm!! }
            val score = (divergenceScore(strongest.diff(), halfSaturation = 30_000.0) * 0.5).coerceAtMost(0.25)
            return RuleFinding(
                ruleId = id,
                title = "状态不明模块读数偏高（软证据）",
                detail = "${softSupporting.size} 个更换史不明的模块读数高于表显，" +
                    "最高为 ${strongest.ecuId} ${"%,d".format(strongest.odometerKm)} km。" +
                    "因无法排除拆车件/清零可能，该疑点不构成硬证据，建议补全维修履历后复检。",
                tamperScore = score,
                weight = of(strongest.trustLevel) * 0.5,
                estimatedTrueKm = null,
                evidenceRefs = softSupporting.map { it.ecuId }
            )
        }

        // 无背离：>=2 个模块互相印证时给出弱负分（全车同步刷写时一致性不能证明清白）。
        // 对齐只检查"没有正向背离"——模块读数低于表显永远不构成调表证据
        // （可能是该模块计数器滞后/换件未登记），不能让负向差异阻断对齐分支。
        val moduleCount = candidates.map { it.ecuId }.distinct().size
        val hardAligned = hard.isNotEmpty() &&
            hard.all { it.diff() <= TrustWeights.NOISE_BAND_KM }
        if (moduleCount >= 2 && hardAligned) {
            // 真实里程保守下限取全部候选模块的最高读数：
            // ORIGINAL 决定证据效力，UNKNOWN 不触发告警，但其读数可参与下限取 max
            val lowerBound = candidates.maxOf { it.odometerKm!! }
            return RuleFinding(
                ruleId = id,
                title = "跨 ECU 读数与表显一致",
                detail = "$moduleCount 个独立控制单元读数均落在表显 ±${TrustWeights.NOISE_BAND_KM}km 误差带内。" +
                    "注意：若各单元被同步刷写，一致性本身不能排除调表，需结合 R2/R3/R4 综合判断。",
                tamperScore = -0.4,
                weight = hard.maxOf { of(it.trustLevel) },
                estimatedTrueKm = lowerBound
            )
        }

        return RuleFinding(
            ruleId = id,
            title = "跨 ECU 里程比对无显著背离",
            detail = "独立模块读数未显著高于表显，但可交叉验证的模块数量不足，无法给出一致性背书。",
            tamperScore = 0.0,
            weight = 0.0,
            notApplicable = true
        )
    }
}
