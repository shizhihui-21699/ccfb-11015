package com.vehicleaudit.cli

import com.vehicleaudit.adapters.file.JsonCaseAdapter
import com.vehicleaudit.core.domain.AuditReport
import com.vehicleaudit.core.domain.RuleFinding
import com.vehicleaudit.core.domain.VerdictLevel
import com.vehicleaudit.core.engine.AuditEngine
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.writeText

/**
 * 里程真实性鉴定 CLI。
 *
 * 用法：
 *   mileage-audit <case.json> [--artifacts <dir>] [--out <report.json>] [--prev-hash <sha256>]
 *
 * 退出码（便于接入业务流水线）：
 *   0 = 未发现调表（LIKELY_GENUINE / CONFIRMED_GENUINE）
 *   2 = 确认/高度疑似调表
 *   3 = 证据不足无法定论
 *   4 = 输入错误（文件缺失、哈希校验失败等）
 */
object Main {
    private val prettyJson = Json { prettyPrint = true; encodeDefaults = true }

    fun run(args: Array<String>): Int {
        val opts = parseArgs(args) ?: return usage()

        val casePath = Path.of(opts.caseFile)
        if (!casePath.exists()) {
            System.err.println("案卷文件不存在: $casePath")
            return 4
        }
        val artifactsDir = opts.artifacts?.let { Path.of(it) }

        val case = try {
            JsonCaseAdapter(artifactsDir).load(casePath)
        } catch (e: Exception) {
            System.err.println("案卷导入失败: ${e.message}")
            return 4
        }

        val engine = AuditEngine()
        val report = engine.evaluate(case, previousReportHash = opts.prevHash)

        val jsonReport = prettyJson.encodeToString(AuditReport.serializer(), report)
        opts.out?.let { Path.of(it).writeText(jsonReport) }

        println(renderText(report))
        println()
        println("报告 JSON${opts.out?.let { "已写入 $it" } ?: "（以下为完整内容）"}：")
        if (opts.out == null) println(jsonReport)

        return when (report.verdict) {
            VerdictLevel.CONFIRMED_TAMPERED, VerdictLevel.LIKELY_TAMPERED -> 2
            VerdictLevel.INCONCLUSIVE -> 3
            VerdictLevel.LIKELY_GENUINE, VerdictLevel.CONFIRMED_GENUINE -> 0
        }
    }

    private data class CliOptions(
        val caseFile: String,
        val artifacts: String?,
        val out: String?,
        val prevHash: String?
    )

    private fun parseArgs(args: Array<String>): CliOptions? {
        if (args.isEmpty()) return null
        var caseFile: String? = null
        var artifacts: String? = null
        var out: String? = null
        var prevHash: String? = null
        var i = 0
        while (i < args.size) {
            when (args[i]) {
                "--artifacts" -> { artifacts = args.getOrNull(++i) ?: return null }
                "--out" -> { out = args.getOrNull(++i) ?: return null }
                "--prev-hash" -> { prevHash = args.getOrNull(++i) ?: return null }
                else -> {
                    if (args[i].startsWith("--") || caseFile != null) return null
                    caseFile = args[i]
                }
            }
            i++
        }
        return caseFile?.let { CliOptions(it, artifacts, out, prevHash) }
    }

    private fun usage(): Int {
        System.err.println(
            """
            用法: mileage-audit <case.json> [--artifacts <dir>] [--out <report.json>] [--prev-hash <sha256>]
            退出码: 0=未调表 2=调表 3=证据不足 4=输入错误
            """.trimIndent()
        )
        return 4
    }

    private fun renderText(report: AuditReport): String = buildString {
        appendLine("=".repeat(72))
        appendLine("车辆里程真实性鉴定报告")
        appendLine("=".repeat(72))
        appendLine("案件编号: ${report.caseId}    VIN: ${report.vin}")
        appendLine("鉴定结论: ${verdictText(report.verdict)}")
        appendLine("融合倾向分: ${"%.3f".format(report.confidenceScore)}（-1=真实，+1=调表）")
        appendLine("表显里程: ${"%,d".format(report.displayedOdometerKm)} km")
        appendLine("真实里程下限（保守口径）: ${"%,d".format(report.minimumTrueOdometerKm)} km")
        if (report.minimumTrueOdometerKm > report.displayedOdometerKm) {
            appendLine("  → 表显至少被压低: ${"%,d".format(report.minimumTrueOdometerKm - report.displayedOdometerKm)} km")
        }
        appendLine("-".repeat(72))
        appendLine("规则明细:")
        report.findings.forEach { f -> appendLine(renderFinding(f)) }
        appendLine("-".repeat(72))
        appendLine("证据链:")
        report.evidenceChain.forEach { appendLine("  $it") }
        appendLine("-".repeat(72))
        appendLine("输入指纹: ${report.inputFingerprint}")
        appendLine("报告哈希: ${report.reportHash}")
        report.previousReportHash?.let { appendLine("前次报告: $it（哈希链）") }
    }

    private fun renderFinding(f: RuleFinding): String = buildString {
        val tag = if (f.notApplicable) "不适用" else "倾向分 ${"%.2f".format(f.tamperScore)} / 权重 ${"%.1f".format(f.weight)}"
        appendLine("● [${f.ruleId}] ${f.title}（$tag）")
        f.detail.trim().lineSequence().forEach { appendLine("    $it") }
    }

    private fun verdictText(v: VerdictLevel) = when (v) {
        VerdictLevel.CONFIRMED_TAMPERED -> "确认调表（证据链闭合）"
        VerdictLevel.LIKELY_TAMPERED -> "高度疑似调表"
        VerdictLevel.INCONCLUSIVE -> "证据不足，无法定论"
        VerdictLevel.LIKELY_GENUINE -> "未发现调表迹象"
        VerdictLevel.CONFIRMED_GENUINE -> "确认未调表（多源高可信一致）"
    }
}

fun main(args: Array<String>) {
    kotlin.system.exitProcess(Main.run(args))
}
