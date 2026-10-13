package com.finecat.pimobile.android.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** 推理档位 clamp：pi `clampThinkingLevel` 的本地镜像。
 *  场景：新会话预选尚无档位时，UI 用 `DEFAULT_THINKING_LEVEL`（medium）clamp 到
 *  模型支持集展示——若 clamp 结果与 pi 不一致，tag 会显示一个 pi 不会采纳的档位。 */
class ThinkingLevelClampTest {

    @Test
    fun keepsRequestedLevelWhenModelSupportsIt() {
        assertEquals(
            "medium",
            clampThinkingLevel("medium", listOf("off", "minimal", "low", "medium", "high", "max"))
        )
    }

    @Test
    fun climbsUpwardWhenRequestedLevelIsUnsupported() {
        // relayB/example-model-flash 只映射 max：medium 向上落到 max。
        assertEquals("max", clampThinkingLevel("medium", listOf("max")))
        // 只支持高档位（无 medium/off）。
        assertEquals("high", clampThinkingLevel("medium", listOf("high", "max")))
    }

    @Test
    fun fallsBackToLowerLevelWhenNoHigherLevelAvailable() {
        assertEquals("low", clampThinkingLevel("medium", listOf("off", "low")))
    }

    @Test
    fun unknownOrEmptyAvailableFallsBackToFirstOrOff() {
        // 档位名不在 pi 顺序表内 → 取支持集首项。
        assertEquals("off", clampThinkingLevel("bogus", listOf("off", "high")))
        // 支持集为空（目录缺失）→ off，不抛。
        assertEquals("off", clampThinkingLevel("medium", emptyList()))
    }
}
