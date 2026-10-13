package com.finecat.pimobile.shared.domain

/**
 * P10b 模型数值输入解析（纯函数，S11-P/R20）：
 * token 数按十进制缩写：K=1000，M=1000000（2026-10-08 真机反馈）。
 * 空白=未设置（null）；无效输入=错误（调用方提示，不静默 0）。
 *
 * K/M 对应十进制位移3/6位；格式化用1000/1000000整倍率，旧整数不整除时回显原数。
 * 落在 [MAX_TOKEN_COUNT] 以内的正整数满足 parse(format(v)) == v。
 */
object ModelNumberParsing {

    private const val KILO: Long = 1000L
    private const val MEGA: Long = KILO * KILO

    /**
     * 保留原始整数上限，避免单位切换使旧合法配置无法再次保存。
     */
    const val MAX_TOKEN_COUNT: Long = 104857600L

    private val SUFFIX = Regex("^([0-9]+(?:\\.[0-9]+)?)\\s*([KkMm]?)$")

    /**
     * 解析 K/M/原始数值输入。返回：
     * - Unset：输入空白（未设置）
     * - Ok(long)：解析成功
     * - Invalid(message)：格式或范围无效（调用方必须展示，不能静默 0）
     */
    fun parseTokenCount(raw: String): ModelNumberResult {
        val text = raw.trim()
        if (text.isEmpty()) return ModelNumberResult.Unset
        val match = SUFFIX.find(text) ?: return ModelNumberResult.Invalid("格式无效：用数字或 K/M（如 500K、1.5M）")
        val scale = when (match.groupValues[2].uppercase()) {
            "K" -> 3
            "M" -> 6
            else -> 0
        }
        val parts = match.groupValues[1].split('.')
        val fraction = parts.getOrElse(1) { "" }
        if (fraction.drop(scale).any { it != '0' }) {
            return ModelNumberResult.Invalid("必须是整数 token 数")
        }
        // 按小数位移而非浮点乘法，1.001K 必须精确等于 1001。
        val digits = (parts[0] + fraction.take(scale).padEnd(scale, '0')).trimStart('0').ifEmpty { "0" }
        val value = digits.toLongOrNull()
            ?: return ModelNumberResult.Invalid("超出合理范围（上限 $MAX_TOKEN_COUNT）")
        if (value <= 0) return ModelNumberResult.Invalid("必须大于 0")
        if (value > MAX_TOKEN_COUNT) {
            return ModelNumberResult.Invalid("超出合理范围（上限 $MAX_TOKEN_COUNT）")
        }
        return ModelNumberResult.Ok(value)
    }

    /** 反向格式化：token 数 → 展示（K/M 取整；非整倍率保留原始数字，不近似成假值）。 */
    fun formatTokenCount(value: Long): String = when {
        value >= MEGA && value % MEGA == 0L -> "${value / MEGA}M"
        value >= KILO && value % KILO == 0L -> "${value / KILO}K"
        else -> value.toString()
    }
}

sealed interface ModelNumberResult {
    data class Ok(val value: Long) : ModelNumberResult
    data class Invalid(val message: String) : ModelNumberResult
    data object Unset : ModelNumberResult
}
