package com.vehicleaudit.core.engine

import com.vehicleaudit.core.domain.RuleFinding
import com.vehicleaudit.core.domain.TrustLevel
import com.vehicleaudit.core.domain.VehicleCase

/**
 * 鉴定规则接口。每条规则：
 * 1. 纯函数：相同 VehicleCase 永远产出相同 finding（可重放、可质证）
 * 2. 自包含：不依赖其他规则的结论，融合交给 Engine 做
 * 3. 不适用时返回 notApplicable=true，而不是抛异常或给中性分
 */
interface AuditRule {
    val id: com.vehicleaudit.core.domain.RuleId
    fun evaluate(case: VehicleCase): RuleFinding
}

/**
 * 可信度 → 融合权重。
 *
 * 设计原则：仪表盘自身读数在任何规则里都不能成为"未调表"的正向证据
 * （它正是待证伪对象）；密码学/厂家后台证据权重数量级高于纸面单据。
 */
object TrustWeights {
    fun of(level: TrustLevel): Double = when (level) {
        TrustLevel.CRYPTOGRAPHIC -> 10.0
        TrustLevel.HIGH -> 6.0
        TrustLevel.MEDIUM -> 3.0
        TrustLevel.LOW -> 1.0
        TrustLevel.UNTRUSTED -> 0.2
    }

    /**
     * 里程差值映射为 tamperScore 的连续函数，避免硬阈值带来的边界争议。
     * 采用饱和曲线：差值越大分数越趋近 1，但永远可解释。
     *
     * @param diffKm      推断里程与表显的差值（公里）
     * @param halfSaturation 达到 0.5 分所需的差值，按规则类型标定
     */
    fun divergenceScore(diffKm: Long, halfSaturation: Double): Double {
        if (diffKm <= 0) return 0.0
        return diffKm / (diffKm + halfSaturation)
    }

    /** 正常测量/取整误差带：100km 以内的差异不视为证据 */
    const val NOISE_BAND_KM = 100L
}
