package com.finecat.pimobile.android.ui.finecat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.finecat.pimobile.android.ui.finecat.components.FcHeader
import com.finecat.pimobile.android.ui.finecat.components.FcIconButton
import com.finecat.pimobile.android.ui.finecat.components.FcTextButton
import com.finecat.pimobile.android.ui.finecat.components.FineCatIcons
import com.finecat.pimobile.android.ui.finecat.conversation.FcMarkdownText
import com.finecat.pimobile.android.ui.finecat.theme.LocalFineCatColors

/**
 * P3 Markdown 实验 demo（仅 debug）：F4 fixture——10 列宽表、同文不同宽两表、
 * 标题/列表/引用/链接/行内代码/围栏代码、流式未闭合→闭合（模拟打字）。
 * 渲染走路线 A 的 [FcMarkdownText]；真源仍是原型 tokens。
 */
@Composable
fun FineCatMarkdownDemoScreen(
    isDark: Boolean,
    onToggleTheme: () -> Unit,
    onBackToComponents: () -> Unit,
) {
    val colors = LocalFineCatColors.current
    var streamed by remember { mutableStateOf(MARKDOWN_FULL) }
    var streaming by remember { mutableStateOf(false) }
    var styleSample by remember { mutableStateOf(false) }

    // 流式模拟：从未闭合逐字补齐到完整文档（M4 用例）
    LaunchedEffect(streaming) {
        if (streaming) {
            var i = MARKDOWN_TABLE_PART.length
            while (i <= MARKDOWN_FULL.length) {
                streamed = MARKDOWN_FULL.take(i)
                i += 7
                kotlinx.coroutines.delay(30)
            }
            streamed = MARKDOWN_FULL
            streaming = false
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(colors.bg).testTag("md.root")) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
            ) {
                FcTextButton(text = "组件", onClick = onBackToComponents, modifier = Modifier.testTag("md.backToComponents"))
                FcTextButton(text = if (isDark) "切浅色" else "切深色", onClick = onToggleTheme, modifier = Modifier.testTag("md.themeToggle"))
                FcTextButton(
                    text = if (styleSample) "表格回归" else "排版示例",
                    onClick = {
                        streaming = false
                        styleSample = !styleSample
                        streamed = if (styleSample) MARKDOWN_STYLE_SAMPLE else MARKDOWN_FULL
                    },
                    modifier = Modifier.testTag("md.styleToggle"),
                )
                FcTextButton(
                    text = if (streaming) "流式中…" else "模拟流式",
                    onClick = { styleSample = false; streaming = true },
                    modifier = Modifier.testTag("md.streamToggle"),
                )
            }
            FcHeader(
                title = "Markdown 实验",
                leading = {
                    FcIconButton(onClick = onBackToComponents, icon = FineCatIcons.Back, contentDescription = "返回", solid = true)
                },
            )
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .testTag("md.scroller")
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            ) {
                FcMarkdownText(
                    markdown = streamed,
                    tableTagPrefix = "md.table",
                    modifier = Modifier.fillMaxWidth().testTag("md.content"),
                )
            }
        }
    }
}

private val MARKDOWN_STYLE_SAMPLE = """
修好了，验证通过。

**改动：** 在 `config.yaml` 的 `name: newapi` 前补了一行 `newapi:`，两个配置段现在各自生效。

**验证结果**（YAML 解析确认）：

- `finecat-2`：恢复为 `openai-completions`，4 个模型完整。
- `newapi`：独立生效，使用 `anthropic-messages`。
- 严格模式解析通过，没有重复键。

长路径同样自然换行：`/workspace/project/config/providers/example/config.yaml`。

### 项目目录

```text
AGENTS.md             ← 协作入口
prototype/            ← 视觉输入
docs/tokens.md        ← 主题与尺寸
docs/components.md    ← 组件清单
docs/screens.md       ← 页面说明
docs/api-map.md       ← 接口映射
```

> 文档只保留规则和指针，避免同一份尺寸与颜色维护两遍。

接下来：

1. 核对 **接口映射**，补齐缺失字段。
2. 打开项目目录，确认 `README.md` 中的构建入口。
""".trimIndent()

private val MARKDOWN_TABLE_PART = """
### 宽表（流式未闭合）

| 列一 | 列二 | 列三 | 列四 | 列五 | 列六 | 列七 | 列八 | 列九 | 列十 |
|---|---|---|---|---|---|---|---|---|---|
""".trim()

private val MARKDOWN_FULL = (MARKDOWN_TABLE_PART + """
| 行1-A | 行1-B | 行1-C | 行1-D | 行1-E | 行1-F | 行1-G | 行1-H | 行1-I | 行1-J |
| 行2-A | 行2-B | 行2-C | 行2-D | 行2-E | 行2-F | 行2-G | 行2-H | 行2-I | 行2-J |

### 同文两表（独立横向偏移）

| 名称 | 数值 |
|---|---|
| alpha | 12 |
| beta | 3456 |

| 名称 | 数值 |
|---|---|
| gamma | 78 |
| delta | 90 |

### 其他块

- 有序列表第一项
- 第二项带 **粗体** 与 `inline_code()`
- [链接示例](https://example.com)

> 引用块：完整 Markdown 能力不退化为原型的 `md()`。

行内样式：**bold**、*italic*、`code_span` 与普通文本混排。

```kotlin
fun main() {
    println("代码块内容，长行测试：abcdefghijklmnopqrstuvwxyz0123456789")
}
```
""").trim()
