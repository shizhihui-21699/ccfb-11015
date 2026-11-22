package com.vehicleaudit.core.engine.rules

import com.vehicleaudit.core.domain.EcuStatus
import com.vehicleaudit.core.domain.RuleFinding
import com.vehicleaudit.core.domain.RuleId
import com.vehicleaudit.core.domain.VehicleCase
import com.vehicleaudit.core.engine.AuditRule

/**
 * R6 模块更换/维修清零的显式留痕（防误报规则）。
 *
 * 背景：二手车上更换 ECU 很常见（事故、进水、故障）。新模块的里程从 0
 * 或拆车件自带里程开始，若把这类读数当作"独立证据"，必然产生大量误报，
 * 鉴定结论也就无法经得起卖方质疑。
 *
 * 做法：R1/R2 已在计算时排除非 ORIGINAL 模块；本规则把"排除了什么、
 * 为什么排除"写进报告，保证证据链的取舍过程本身可质证。本规则不产生
 * 调表倾向分（中性分不参与融合拉偏），但当更换记录本身可疑（多模块
 * 集中更换且无任何单据支撑）时给出弱正向提示，供人工调查。
 */
class ModuleReplacementDiscountRule : AuditRule {
    override val id = RuleId.MODULE_REPLACEMENT_DISCOUNT

    override fun evaluate(case: VehicleCase): RuleFinding {
        val replaced = case.ecuReadings.filter { it.ecuStatus == EcuStatus.REPLACED_OR_REPAIRED }
        val unknown = case.ecuReadings.filter { it.ecuStatus == EcuStatus.UNKNOWN }

        // 无任何模块数据时本规则无话可说，交由其他规则的"不适用"覆盖
        if (case.ecuReadings.isEmpty()) {
            return RuleFinding(
                ruleId = id,
                title = "模块更换史核查",
                detail = "未采集到任何控制单元数据，本规则不适用。",
                tamperScore = 0.0,
                weight = 0.0,
                notApplicable = true
            )
        }

        if (replaced.isEmpty() && unknown.isEmpty()) {
            return RuleFinding(
                ruleId = id,
                title = "模块更换史核查通过",
                detail = "全部 ${case.ecuReadings.size} 个控制单元均为经核实的原厂状态，无换件清零干扰。",
                tamperScore = -0.2,
                weight = 1.0
            )
        }

        if (replaced.isEmpty()) {
            // 仅当采集方未完成更换史核查时，UNKNOWN 才构成证据瑕疵；
            // 已核查仍标 UNKNOWN 说明渠道数据缺失，同样提示但措辞不同
            val reason = if (case.replacementHistoryKnown)
                "渠道数据缺失，状态无法确认" else "更换史核查未完成"
            return RuleFinding(
                ruleId = id,
                title = "部分模块状态不明，读数已降权",
                detail = "${unknown.size} 个模块（${unknown.joinToString { it.ecuId }}）$reason，" +
                    "其读数不进入 R1/R2 硬证据计算，建议补全维修履历后重新鉴定。",
                tamperScore = 0.0,
                weight = 0.5,
                evidenceRefs = unknown.map { it.ecuId }
            )
        }

        // 多模块无单换件 + 无任何历史单据佐证 → 换件动机存疑的弱提示
        val hasPaperTrail = case.historyEvents.isNotEmpty()
        val suspiciousBatch = replaced.size >= 2 && !hasPaperTrail
        val score = if (suspiciousBatch) 0.3 else 0.0

        return RuleFinding(
            ruleId = id,
            title = "更换/维修模块已从里程证据中排除",
            detail = buildString {
                appendLine("以下模块存在更换或维修记录，其里程/小时读数不具备全车里程代表性，已从 R1/R2 计算中排除：")
                replaced.forEach { r ->
                    appendLine("  · ${r.ecuId}：读数=${r.odometerKm?.let { "%,d km".format(it) } ?: "无"}，备注=${r.notes.ifBlank { "无" }}")
                }
                if (unknown.isNotEmpty()) {
                    appendLine("另有状态不明模块已同步降权：${unknown.joinToString { it.ecuId }}")
                }
                if (suspiciousBatch) {
                    append("注意：多个模块集中更换且全案无任何维修单据佐证，换件动机需人工调查（不排除毁证式换件）。")
                } else {
                    append("排除换件干扰后，其余模块的证据效力不受影响。")
                }
            },
            tamperScore = score,
            weight = if (suspiciousBatch) 2.0 else 1.0,
            evidenceRefs = replaced.map { it.ecuId }
        )
    }
}
