package com.vehicleaudit.core.engine

import com.vehicleaudit.core.domain.AuditReport
import com.vehicleaudit.core.domain.RuleFinding
import com.vehicleaudit.core.domain.VehicleCase
import com.vehicleaudit.core.domain.VerdictLevel
import com.vehicleaudit.core.engine.rules.CrossEcuDivergenceRule
import com.vehicleaudit.core.engine.rules.HistoryExceedsCurrentRule
import com.vehicleaudit.core.engine.rules.HistoryRegressionRule
import com.vehicleaudit.core.engine.rules.ModuleReplacementDiscountRule
import com.vehicleaudit.core.engine.rules.OperatingHoursEstimateRule
import com.vehicleaudit.core.engine.rules.SourceConflictRule
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.security.MessageDigest

/**
 * 证据指纹：对案件输入做规范化 JSON 序列化（键排序、无多余空白）后取 SHA-256。
 * 规范化保证字段顺序不影响指纹，同一案件无论从哪个适配器组装都得到同一哈希。
 */
object Fingerprint {
    private val canonicalJson = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    private fun canonicalize(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.entries.sortedBy { it.key }.associate { (k, v) -> k to canonicalize(v) })
        is JsonArray -> JsonArray(element.map { canonicalize(it) })
        else -> element
    }

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun ofCase(case: VehicleCase): String {
        val raw = canonicalJson.encodeToString(VehicleCase.serializer(), case)
        val tree = canonicalize(Json.parseToJsonElement(raw))
        return sha256Hex(canonicalJson.encodeToString(JsonElement.serializer(), tree).toByteArray(Charsets.UTF_8))
    }

    fun ofReport(report: AuditReport): String {
        // 报告哈希对除 reportHash 自身外的全部字段计算
        val withoutHash = report.copy(reportHash = "")
        val raw = canonicalJson.encodeToString(AuditReport.serializer(), withoutHash)
        val tree = canonicalize(Json.parseToJsonElement(raw))
        return sha256Hex(canonicalJson.encodeToString(JsonElement.serializer(), tree).toByteArray(Charsets.UTF_8))
    }
}

/**
 * 融合引擎。规则产出 → 加权融合 → 五档结论 → 证据链 → 带指纹的正式报告。
 *
 * 全部计算确定性执行：无随机数、无外部调用、无隐式时钟
 * （报告时间由 [clockMillis] 注入，测试可固定）。
 */
class AuditEngine(
    private val rules: List<AuditRule> = DEFAULT_RULES,
    private val clockMillis: () -> Long = { System.currentTimeMillis() }
) {
    fun evaluate(case: VehicleCase, previousReportHash: String? = null): AuditReport {
        val findings = rules.map { it.evaluate(case) }
        val applicable = findings.filter { !it.notApplicable && it.weight > 0.0 }

        val confidenceScore = fuse(applicable)

        val verdict = decideVerdict(confidenceScore, applicable, case)
        val minimumTrueKm = deriveMinimumTrueOdometer(case, applicable)
        val evidenceChain = buildEvidenceChain(case, findings)
        val inputFingerprint = Fingerprint.ofCase(case)

        val draft = AuditReport(
            caseId = case.caseId,
            vin = case.vin,
            generatedAtEpoch = clockMillis(),
            verdict = verdict,
            confidenceScore = kotlin.math.round(confidenceScore * 1000.0) / 1000.0,
            displayedOdometerKm = case.displayedOdometerKm,
            minimumTrueOdometerKm = minimumTrueKm,
            findings = findings,
            evidenceChain = evidenceChain,
            inputFingerprint = inputFingerprint,
            previousReportHash = previousReportHash,
            reportHash = ""
        )
        return draft.copy(reportHash = Fingerprint.ofReport(draft))
    }

    /**
     * 双轴证据融合。
     *
     * 证据法原则：一条可验证的矛盾证据（如年检里程高于当前表显）不能被
     * 若干条"未见异常"的中性证据线性对冲掉。因此：
     * 1. 正向（指向调表）与负向（指向真实）证据分别加权汇总
     * 2. 存在正向证据时，负向总权重按比例封顶（NEGATIVE_CAP），
     *    即"一致性读数"最多只能抵消一部分矛盾，不能抹掉矛盾
     * 3. 无正向证据时，才由负向证据独立给出真实性背书
     */
    private fun fuse(findings: List<RuleFinding>): Double {
        if (findings.isEmpty()) return 0.0

        val positive = findings.filter { it.tamperScore > 0.0 }
        val negative = findings.filter { it.tamperScore < 0.0 }

        val posWeight = positive.sumOf { it.weight }
        val posContribution = positive.sumOf { it.tamperScore * it.weight }

        if (posWeight == 0.0) {
            val negWeight = negative.sumOf { it.weight }
            return if (negWeight == 0.0) 0.0
            else negative.sumOf { it.tamperScore * it.weight } / negWeight
        }

        val rawNegWeight = negative.sumOf { it.weight }
        val cappedNegWeight = minOf(rawNegWeight, posWeight * NEGATIVE_WEIGHT_CAP)

        // 两侧分别做加权平均——高可信证据（如密码学级年检，权重 10）不能与
        // 普通证据等权平均，否则硬证据会被数学稀释。
        val posMean = posContribution / posWeight
        val negMean = if (rawNegWeight == 0.0) 0.0 else
            negative.sumOf { it.tamperScore * it.weight } / rawNegWeight

        // 非对称混合：正向矛盾事实占主导（POSITIVE_DOMINANCE），
        // 负向"未见异常"按封顶后的权重比例作有限修正，不能抹掉矛盾。
        val negShare = if (posWeight == 0.0) 0.0 else
            (cappedNegWeight / posWeight).coerceAtMost(1.0 - POSITIVE_DOMINANCE)
        val combined = posMean * (1.0 - negShare) + negMean * negShare
        return combined.coerceIn(-1.0, 1.0)
    }

    /**
     * 结论分档。除总分外要求"独立证据组数"，防止单条高权重证据一锤定音——
     * 经得起质疑的结论必须建立在多源印证上；但 R1 单规则本身已聚合多个
     * 独立 ECU 时，允许以单组高权重确认（corroboration 已在规则内计分）。
     */
    private fun decideVerdict(score: Double, findings: List<RuleFinding>, case: VehicleCase): VerdictLevel {
        val tamperGroups = findings.filter { it.tamperScore > 0.25 }.map { it.ruleId }.distinct()
        val genuineGroups = findings.filter { it.tamperScore < -0.15 }.map { it.ruleId }.distinct()
        val evidenceVolume = case.ecuReadings.size + case.historyEvents.size

        val strongSingle = tamperGroups.size == 1 &&
            findings.filter { it.tamperScore > 0.25 }.sumOf { it.weight } >= 6.0

        return when {
            score >= 0.50 && (tamperGroups.size >= 2 || strongSingle) -> VerdictLevel.CONFIRMED_TAMPERED
            score >= 0.30 && tamperGroups.isNotEmpty() -> VerdictLevel.LIKELY_TAMPERED
            score <= -0.25 && genuineGroups.size >= 2 && evidenceVolume >= 3 -> VerdictLevel.CONFIRMED_GENUINE
            score <= -0.15 && genuineGroups.isNotEmpty() -> VerdictLevel.LIKELY_GENUINE
            else -> VerdictLevel.INCONCLUSIVE
        }
    }

    /**
     * 真实里程下限（保守口径）：
     * - 各规则给出的估计值取最大（独立模块高读数/历史最高单据/小时反推）
     * - 不低于表显（表显本身就是下限之一）
     * 取"下限"而非"点估计"，是为了在法庭/调解场景下只主张能证明的部分。
     */
    private fun deriveMinimumTrueOdometer(case: VehicleCase, findings: List<RuleFinding>): Long {
        val ruleEstimates = findings.mapNotNull { it.estimatedTrueKm }
        val candidates = ruleEstimates + case.displayedOdometerKm
        return candidates.max()
    }

    /** 证据链：按人类质证顺序组织——先硬证据后推断，每条都带原始数据锚点。 */
    private fun buildEvidenceChain(case: VehicleCase, findings: List<RuleFinding>): List<String> {
        val chain = mutableListOf<String>()
        chain += "案件输入指纹 SHA-256：${Fingerprint.ofCase(case)}（绑定 ${case.ecuReadings.size} 条 ECU 读数、" +
            "${case.historyEvents.size} 条历史单据，原始报文/单据哈希见各采集记录）"

        findings.filter { !it.notApplicable }.forEach { f ->
            val anchor = if (f.evidenceRefs.isEmpty()) "" else " | 证据锚点: ${f.evidenceRefs.joinToString()}"
            val estimate = f.estimatedTrueKm?.let { " | 推断里程: ${"%,d".format(it)} km" } ?: ""
            chain += "[${f.ruleId}] ${f.title}（倾向分 ${"%.2f".format(f.tamperScore)}，权重 ${"%.1f".format(f.weight)})$estimate$anchor"
        }
        return chain
    }

    companion object {
        /** 存在矛盾证据时，"未见异常"类负向证据的总权重封顶为正向权重的 25% */
        private const val NEGATIVE_WEIGHT_CAP = 0.25

        /** 融合时正向矛盾证据的主导占比，剩余比例由负向证据修正 */
        private const val POSITIVE_DOMINANCE = 0.8

        val DEFAULT_RULES: List<AuditRule> = listOf(
            CrossEcuDivergenceRule(),
            OperatingHoursEstimateRule(),
            HistoryRegressionRule(),
            HistoryExceedsCurrentRule(),
            SourceConflictRule(),
            ModuleReplacementDiscountRule()
        )
    }
}
