package com.vehicleaudit.core.engine

import com.vehicleaudit.core.domain.RuleId
import com.vehicleaudit.core.domain.SourceKind
import com.vehicleaudit.core.domain.TrustLevel
import com.vehicleaudit.core.domain.VerdictLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 场景化测试：每个测试对应真实鉴定中会遇到的一类案情，
 * 也是向买卖双方解释结论时的标准案例库。
 */
class AuditEngineScenariosTest {

    @Test
    fun `场景1 仅改仪表盘 多个独立ECU保留高读数 确认调表`() {
        val vehicleCase = CaseFixtures.case(
            displayed = 80_000,
            readings = listOf(
                CaseFixtures.ecu("IC", SourceKind.INSTRUMENT_CLUSTER, TrustLevel.UNTRUSTED, odometerKm = 80_000),
                CaseFixtures.ecu("ECM", SourceKind.ECU, TrustLevel.HIGH, odometerKm = 152_000),
                CaseFixtures.ecu("TCU", SourceKind.ECU, TrustLevel.HIGH, odometerKm = 151_800),
                CaseFixtures.ecu("ABS", SourceKind.ECU, TrustLevel.MEDIUM, odometerKm = 151_500),
                CaseFixtures.ecu("BCM", SourceKind.BODY_MODULE, TrustLevel.MEDIUM, odometerKm = 150_900)
            )
        )

        val report = CaseFixtures.audit(vehicleCase)

        assertEquals(VerdictLevel.CONFIRMED_TAMPERED, report.verdict)
        // 真实里程下限取独立模块最高读数
        assertEquals(152_000L, report.minimumTrueOdometerKm)
        assertTrue(report.confidenceScore > 0.55)
        val r1 = report.findings.single { it.ruleId == RuleId.CROSS_ECU_DIVERGENCE }
        assertTrue(r1.evidenceRefs.containsAll(listOf("ECM", "TCU", "ABS", "BCM")))
        // 仪表盘自身不得出现在证据锚点中
        assertTrue("IC" !in r1.evidenceRefs)
    }

    @Test
    fun `场景2 全车同步刷写 各ECU读数一致 但历史单据戳穿 确认调表`() {
        val vehicleCase = CaseFixtures.case(
            displayed = 90_000,
            readings = listOf(
                CaseFixtures.ecu("IC", SourceKind.INSTRUMENT_CLUSTER, TrustLevel.UNTRUSTED, odometerKm = 90_000),
                CaseFixtures.ecu("ECM", SourceKind.ECU, TrustLevel.HIGH, odometerKm = 90_100),
                CaseFixtures.ecu("TCU", SourceKind.ECU, TrustLevel.HIGH, odometerKm = 89_950),
                CaseFixtures.ecu("BCM", SourceKind.BODY_MODULE, TrustLevel.MEDIUM, odometerKm = 90_020)
            ),
            events = listOf(
                CaseFixtures.event("INSPECT-2022", SourceKind.INSPECTION, TrustLevel.CRYPTOGRAPHIC, CaseFixtures.T2, 118_000, verified = true),
                CaseFixtures.event("SERVICE-2023", SourceKind.SERVICE_RECORD, TrustLevel.MEDIUM, CaseFixtures.T3, 132_000, verified = true)
            )
        )

        val report = CaseFixtures.audit(vehicleCase)

        assertEquals(VerdictLevel.CONFIRMED_TAMPERED, report.verdict)
        assertEquals(132_000L, report.minimumTrueOdometerKm)
        // 一致性读数只给了弱负分，不足以对抗密码学级历史证据
        val r4 = report.findings.single { it.ruleId == RuleId.HISTORY_EXCEEDS_CURRENT }
        assertTrue(r4.tamperScore > 0.7)
    }

    @Test
    fun `场景3 发动机更换过 低读数不应误报为调表`() {
        val vehicleCase = CaseFixtures.case(
            displayed = 160_000,
            readings = listOf(
                CaseFixtures.ecu("ECM-NEW", SourceKind.ECU, TrustLevel.HIGH, odometerKm = 12_000,
                    status = com.vehicleaudit.core.domain.EcuStatus.REPLACED_OR_REPAIRED,
                    hours = 310.0, notes = "2023年事故更换发动机总成"),
                CaseFixtures.ecu("TCU", SourceKind.ECU, TrustLevel.HIGH, odometerKm = 159_800),
                CaseFixtures.ecu("ABS", SourceKind.ECU, TrustLevel.MEDIUM, odometerKm = 160_100)
            ),
            events = listOf(
                CaseFixtures.event("SERVICE-ENGINE", SourceKind.SERVICE_RECORD, TrustLevel.MEDIUM, CaseFixtures.T2, 120_000, verified = true)
            )
        )

        val report = CaseFixtures.audit(vehicleCase)

        assertTrue(
            report.verdict == VerdictLevel.LIKELY_GENUINE || report.verdict == VerdictLevel.CONFIRMED_GENUINE,
            "换件导致的低读数被误判为调表: ${report.verdict}"
        )
        val r6 = report.findings.single { it.ruleId == RuleId.MODULE_REPLACEMENT_DISCOUNT }
        assertTrue(r6.evidenceRefs.contains("ECM-NEW"))
        // R1 不得引用更换模块作为证据
        val r1 = report.findings.single { it.ruleId == RuleId.CROSS_ECU_DIVERGENCE }
        assertTrue("ECM-NEW" !in r1.evidenceRefs)
        // 真实里程下限不应被换件模块拉低
        assertEquals(160_100L, report.minimumTrueOdometerKm)
    }

    @Test
    fun `场景4 维保记录缺失 仅靠工作小时反推 给出谨慎结论`() {
        // 4000 小时 × 35km/h × 0.8 保守系数 = 112000 km，表显 60000
        val vehicleCase = CaseFixtures.case(
            displayed = 60_000,
            readings = listOf(
                CaseFixtures.ecu("ECM", SourceKind.ECU, TrustLevel.HIGH, hours = 4_000.0),
                CaseFixtures.ecu("HVAC", SourceKind.BODY_MODULE, TrustLevel.MEDIUM, hours = 3_950.0)
            )
        )

        val report = CaseFixtures.audit(vehicleCase)

        val r2 = report.findings.single { it.ruleId == RuleId.OPERATING_HOURS_ESTIMATE }
        assertEquals(112_000L, r2.estimatedTrueKm)
        assertTrue(r2.tamperScore > 0.4)
        // 单一证据组（R2）不足以"确认"，只能到"高度疑似"
        assertEquals(VerdictLevel.LIKELY_TAMPERED, report.verdict)
        assertTrue(report.evidenceChain.any { it.contains("工作小时") })
    }

    @Test
    fun `场景5 伪造的维保单据与年检记录冲突 标记疑似伪造`() {
        val vehicleCase = CaseFixtures.case(
            displayed = 100_000,
            events = listOf(
                // 检测站官方读数：同期 13 万
                CaseFixtures.event("INSPECTION", SourceKind.INSPECTION, TrustLevel.CRYPTOGRAPHIC, CaseFixtures.T2, 130_000, verified = true),
                // 调表后补做的假保养单：同期只写 8.5 万，未核验
                CaseFixtures.event("FAKE-SERVICE", SourceKind.SERVICE_RECORD, TrustLevel.LOW, CaseFixtures.T2 + 5_000_000L, 85_000, verified = false),
                CaseFixtures.event("SERVICE-OLD", SourceKind.SERVICE_RECORD, TrustLevel.MEDIUM, CaseFixtures.T1, 95_000, verified = true)
            )
        )

        val report = CaseFixtures.audit(vehicleCase)

        val r5 = report.findings.single { it.ruleId == RuleId.SOURCE_CONFLICT }
        assertTrue(r5.detail.contains("FAKE-SERVICE"))
        assertTrue(r5.detail.contains("疑似伪造"))
        // 同时 R3 时序倒挂（CaseFixtures.T1 9.5万 → CaseFixtures.T2 8.5万）与 R4（13万 > 表显10万）成立
        assertEquals(
            setOf(RuleId.SOURCE_CONFLICT, RuleId.HISTORY_REGRESSION, RuleId.HISTORY_EXCEEDS_CURRENT),
            report.findings.filter { it.tamperScore > 0.25 }.map { it.ruleId }.toSet()
        )
        assertEquals(VerdictLevel.CONFIRMED_TAMPERED, report.verdict)
    }

    @Test
    fun `场景6 数据极度匮乏 必须出具无法定论而非猜测`() {
        val vehicleCase = CaseFixtures.case(displayed = 50_000)

        val report = CaseFixtures.audit(vehicleCase)

        assertEquals(VerdictLevel.INCONCLUSIVE, report.verdict)
        assertTrue(report.findings.all { it.notApplicable || it.tamperScore == 0.0 })
        assertEquals(50_000L, report.minimumTrueOdometerKm)
    }

    @Test
    fun `场景7 高可信多源一致 确认未调表`() {
        val vehicleCase = CaseFixtures.case(
            displayed = 75_000,
            readings = listOf(
                CaseFixtures.ecu("ECM", SourceKind.ECU, TrustLevel.HIGH, odometerKm = 75_050),
                CaseFixtures.ecu("TCU", SourceKind.ECU, TrustLevel.HIGH, odometerKm = 74_980),
                CaseFixtures.ecu("TBOX", SourceKind.TELEMATICS, TrustLevel.CRYPTOGRAPHIC, odometerKm = 75_010)
            ),
            events = listOf(
                CaseFixtures.event("INSPECT-1", SourceKind.INSPECTION, TrustLevel.CRYPTOGRAPHIC, CaseFixtures.T1, 38_000, verified = true),
                CaseFixtures.event("INSPECT-2", SourceKind.INSPECTION, TrustLevel.CRYPTOGRAPHIC, CaseFixtures.T2, 56_000, verified = true),
                CaseFixtures.event("INSPECT-3", SourceKind.INSPECTION, TrustLevel.CRYPTOGRAPHIC, CaseFixtures.T3, 70_000, verified = true)
            )
        )

        val report = CaseFixtures.audit(vehicleCase)

        assertEquals(VerdictLevel.CONFIRMED_GENUINE, report.verdict)
        assertTrue(report.confidenceScore <= -0.25)
    }

    @Test
    fun `场景8 同案同判 引擎输出确定性且报告哈希可复算`() {
        val vehicleCase = CaseFixtures.case(
            displayed = 80_000,
            readings = listOf(
                CaseFixtures.ecu("ECM", SourceKind.ECU, TrustLevel.HIGH, odometerKm = 140_000),
                CaseFixtures.ecu("BCM", SourceKind.BODY_MODULE, TrustLevel.MEDIUM, odometerKm = 139_000)
            )
        )

        val r1 = CaseFixtures.audit(vehicleCase)
        val r2 = CaseFixtures.audit(vehicleCase)

        assertEquals(r1.reportHash, r2.reportHash)
        assertEquals(r1.inputFingerprint, r2.inputFingerprint)
        // 报告哈希必须能由报告内容独立复算
        assertEquals(r1.reportHash, Fingerprint.ofReport(r1))
        // 哈希链：前案哈希被写入新报告
        val chained = AuditEngine(clockMillis = { CaseFixtures.T_NOW }).evaluate(vehicleCase, previousReportHash = r1.reportHash)
        assertEquals(r1.reportHash, chained.previousReportHash)
        assertTrue(chained.reportHash != r1.reportHash)
    }

    @Test
    fun `场景9 100km噪声带内的差异不构成证据`() {
        val vehicleCase = CaseFixtures.case(
            displayed = 100_000,
            readings = listOf(
                CaseFixtures.ecu("ECM", SourceKind.ECU, TrustLevel.HIGH, odometerKm = 100_060),
                CaseFixtures.ecu("TCU", SourceKind.ECU, TrustLevel.HIGH, odometerKm = 99_970)
            )
        )

        val report = CaseFixtures.audit(vehicleCase)
        val r1 = report.findings.single { it.ruleId == RuleId.CROSS_ECU_DIVERGENCE }
        assertTrue(r1.tamperScore <= 0.0, "噪声带内差异不应产生调表倾向分")
    }

    @Test
    fun `场景10 未知状态模块降权 不参与硬证据但提示补全履历`() {
        val vehicleCase = CaseFixtures.case(
            displayed = 90_000,
            readings = listOf(
                CaseFixtures.ecu("MYSTERY-ECU", SourceKind.ECU, TrustLevel.MEDIUM, odometerKm = 250_000,
                    status = com.vehicleaudit.core.domain.EcuStatus.UNKNOWN)
            )
        )

        val report = CaseFixtures.audit(vehicleCase)
        val r6 = report.findings.single { it.ruleId == RuleId.MODULE_REPLACEMENT_DISCOUNT }
        assertTrue(r6.detail.contains("MYSTERY-ECU"))
        // 状态未知的模块不能直接把真实里程下限顶到 25 万
        assertEquals(90_000L, report.minimumTrueOdometerKm)
    }
}
