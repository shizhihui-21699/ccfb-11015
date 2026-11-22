package com.vehicleaudit.core.domain

import kotlinx.serialization.Serializable

/** 规则标识。新增规则时在此登记，报告按规则 ID 逐条质证。 */
@Serializable
enum class RuleId {
    /** R1 跨 ECU 里程比对：独立模块读数显著高于表显 */
    CROSS_ECU_DIVERGENCE,

    /** R2 工作小时/计数器换算：小时数 × 平均速度反推里程 */
    OPERATING_HOURS_ESTIMATE,

    /** R3 历史事件时序矛盾：后一单据里程小于前一单据 */
    HISTORY_REGRESSION,

    /** R4 历史事件高于当前表显：过去的里程不可能大于现在 */
    HISTORY_EXCEEDS_CURRENT,

    /** R5 单据可信度冲突：同里程区间出现互相矛盾的多来源记录 */
    SOURCE_CONFLICT,

    /** R6 模块更换/清零识别：低读数有换件解释时降级，避免误报 */
    MODULE_REPLACEMENT_DISCOUNT
}

/**
 * 最终鉴定结论。只给五档、不给含糊措辞，并强制附带依据。
 */
@Serializable
enum class VerdictLevel {
    /** 证据链闭合，可确认调表 */
    CONFIRMED_TAMPERED,

    /** 多项独立证据指向调表，但存在未排除的替代解释 */
    LIKELY_TAMPERED,

    /** 数据不足或证据互相抵消，无法出具倾向性结论 */
    INCONCLUSIVE,

    /** 交叉验证通过，未发现调表迹象 */
    LIKELY_GENUINE,

    /** 全链路高可信证据一致，可确认未调表 */
    CONFIRMED_GENUINE
}

/**
 * 单条规则的产出。规则只做事实判断，不直接下最终结论。
 *
 * @property tamperScore 规则对"调表成立"的贡献分，范围 [-1, 1]：
 *                       正值指向调表，负值指向真实，0 为中性/不适用
 * @property weight      该条发现在本案中的权重（依据证据可信度计算）
 * @property estimatedTrueKm 该规则推断出的真实里程下限/估计值，无法推断为 null
 */
@Serializable
data class RuleFinding(
    val ruleId: RuleId,
    val title: String,
    val detail: String,
    val tamperScore: Double,
    val weight: Double,
    val estimatedTrueKm: Long? = null,
    /** 证据锚点：ECU 标识或历史事件 ID，使每条结论可回溯到原始数据 */
    val evidenceRefs: List<String> = emptyList(),
    /** 该规则适用的前提不成立（如缺少历史记录），不计入融合 */
    val notApplicable: Boolean = false
) {
    init {
        require(tamperScore in -1.0..1.0) { "tamperScore 必须在 [-1,1]" }
        require(weight >= 0.0) { "权重不能为负" }
    }
}

/**
 * 完整鉴定报告。JSON 可序列化、字段稳定，是对外出具（含司法/消保场景）的正式产物。
 */
@Serializable
data class AuditReport(
    val caseId: String,
    val vin: String,
    val generatedAtEpoch: Long,
    val verdict: VerdictLevel,
    /** 融合后的调表概率分，[-1,1] */
    val confidenceScore: Double,
    val displayedOdometerKm: Long,
    /** 引擎认定的真实里程下限（保守值，宁低勿高） */
    val minimumTrueOdometerKm: Long,
    val findings: List<RuleFinding>,
    /** 按可信度从高到低排列的关键证据摘要 */
    val evidenceChain: List<String>,
    /** 输入数据指纹，用于证明报告与原始采集数据一一对应 */
    val inputFingerprint: String,
    /** 前一份报告哈希；同一案件多次鉴定构成哈希链 */
    val previousReportHash: String? = null,
    val reportHash: String
)
