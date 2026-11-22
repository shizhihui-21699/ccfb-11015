package com.vehicleaudit.adapters.file

import com.vehicleaudit.core.engine.Fingerprint
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JsonCaseAdapterTest {

    @TempDir
    lateinit var tempDir: Path

    private val validCaseJson = """
    {
      "caseId": "CASE-001",
      "vin": "LSGAB52L7DF000000",
      "make": "DemoMake",
      "model": "DemoModel",
      "modelYear": 2020,
      "displayedOdometerKm": 80000,
      "collectedAtEpoch": 1704067200000,
      "ecuReadings": [
        {
          "ecuId": "ECM",
          "sourceKind": "ECU",
          "trustLevel": "HIGH",
          "ecuStatus": "ORIGINAL",
          "odometerKm": 152000,
          "readAtEpoch": 1704067200000,
          "rawPayloadHash": "HASH_PLACEHOLDER",
          "artifact": "ecm.bin"
        }
      ],
      "historyEvents": []
    }
    """.trimIndent()

    @Test
    fun `原始文件哈希与案卷声明一致时正常导入`() {
        val artifacts = Files.createDirectory(tempDir.resolve("artifacts"))
        val payload = "raw-diagnostic-frame-bytes".toByteArray()
        artifacts.resolve("ecm.bin").writeBytes(payload)
        val hash = Fingerprint.sha256Hex(payload)

        val caseFile = tempDir.resolve("case.json")
        caseFile.writeText(validCaseJson.replace("HASH_PLACEHOLDER", hash))

        val vehicleCase = JsonCaseAdapter(artifacts).load(caseFile)

        assertEquals("CASE-001", vehicleCase.caseId)
        assertEquals(152000L, vehicleCase.ecuReadings.single().odometerKm)
        assertEquals(hash, vehicleCase.ecuReadings.single().rawPayloadHash)
    }

    @Test
    fun `原始文件被篡改导致哈希不符时拒绝导入`() {
        val artifacts = Files.createDirectory(tempDir.resolve("artifacts"))
        artifacts.resolve("ecm.bin").writeBytes("original".toByteArray())
        val staleHash = Fingerprint.sha256Hex("original".toByteArray())

        val caseFile = tempDir.resolve("case.json")
        caseFile.writeText(validCaseJson.replace("HASH_PLACEHOLDER", staleHash))

        // 模拟送检案卷中的原始报文被事后替换
        artifacts.resolve("ecm.bin").writeBytes("tampered-bytes".toByteArray())

        val error = assertFailsWith<IllegalArgumentException> {
            JsonCaseAdapter(artifacts).load(caseFile)
        }
        assertTrue(error.message!!.contains("哈希"), "应明确报哈希不符，实际: ${error.message}")
    }

    @Test
    fun `artifact路径越界时拒绝导入 防止目录穿越`() {
        val artifacts = Files.createDirectory(tempDir.resolve("artifacts"))
        val outsideHash = Fingerprint.sha256Hex("x".toByteArray())
        val malicious = validCaseJson
            .replace("HASH_PLACEHOLDER", outsideHash)
            .replace("ecm.bin", "../../etc/passwd")

        val caseFile = tempDir.resolve("case.json")
        caseFile.writeText(malicious)

        assertFailsWith<IllegalArgumentException> {
            JsonCaseAdapter(artifacts).load(caseFile)
        }
    }

    @Test
    fun `无artifact字段时仅做结构校验也可导入`() {
        val minimal = """
        {
          "caseId": "CASE-002",
          "vin": "LSGAB52L7DF000001",
          "make": "M",
          "model": "X",
          "modelYear": 2021,
          "displayedOdometerKm": 1000,
          "collectedAtEpoch": 1704067200000
        }
        """.trimIndent()
        val caseFile = tempDir.resolve("min.json")
        caseFile.writeText(minimal)

        val vehicleCase = JsonCaseAdapter(null).load(caseFile)
        assertEquals(0, vehicleCase.ecuReadings.size)
        // 域模型安全默认：未声明更换史即为 UNKNOWN，绝不默认原厂
        assertEquals(1000L, vehicleCase.displayedOdometerKm)
    }
}
