# Agent Note: 助手正文的 Markdown 渲染路径

Status: implemented

## 问题

助手正文需要中文友好的排版、行内代码底色、可横滑复制的代码块，以及不提前折行。正文由 Compose 与 Android TextView 混合渲染，很多样式不会自动从 Compose 主题传到 TextView；行内代码底色在软换行处还会画错位置。

## 决定

- FineCat 正文走 `FcAiMessage → FcMarkdownText`：围栏代码块和表格是 Compose 容器，其余交给 Markwon 与 TextView。旧的 `PiMarkdownText` 是另一条入口，不能直接替换，否则表格、选择和主题语义一起变化。
- 设置顺序固定为 `setTextSize → TextViewCompat.setLineHeight → Markwon.setParsedMarkdown`。`LocalFineCatType.prose.lineHeight` 不会自动进入 TextView，需按 `LocalDensity` 换算后显式传入；`setParsedMarkdown` 会执行 `beforeSetText`，让有序编号按最终 paint 测量。直接赋值 `view.text` 会绕开插件钩子。
- 标题使用倍率而不是固定像素，层级保持递减；列表按层叠加 `LeadingMarginSpan`。
- 行内代码是 `MetricAffectingSpan`，底色按每条实际 layout 行绘制。逐行底色取 `getSelectionPath` 后裁到当前行的水平与垂直范围；不用 `getPrimaryHorizontal(segmentEnd)`，软换行的 lineEnd 恰是下一行起点，平台会返回下一行的位置，使上一行底色消失或误染前文。字符和 span 完整的检查抓不到这类绘制错误。
- 段落 `fillMaxWidth` 使用对话宽度，TextView 使用 `BREAK_STRATEGY_SIMPLE` 与关闭自动连字符，减少整段优化引起的提前断行；普通词边界仍可换行。不插入零宽空格或人工软换行，保持复制和链接语义。
- 两端对齐依据真实软换行，末行和显式换行不拉伸：API 35+ 用 `JUSTIFICATION_MODE_INTER_CHARACTER` 覆盖中文；API 26–34 的 `INTER_WORD` 只拉词间距，对无空格的中文无效果；API 24 / 25 保持左对齐。
- 正文中的围栏代码块使用局部的 `FcMdCodeBlock`（语言栏、横滑、复制），工具详情继续用 `FcCodeBlock`，避免改变工具详情的布局。复制取点击时刻的原文，流式新增内容后重新提示未复制。
- Markwon 与渲染缓存随颜色或字体密度变化更新。`FineCatColors` 是 data class，同值的新实例不一定触发 remember 重建。
- 复制按钮放在正文下方右侧，正文满宽；放在正文旁边会让全文右侧始终少一截宽度。

## 考虑过的方案

**插入零宽空格控制换行。** 破坏复制原文和链接识别。

**直接把旧 `PiMarkdownText` 的默认样式拿来用。** 两条入口的表格、选择与主题实现不同。

## 影响

- 不同 API 级别的对齐效果不一致。
- 旧 Pi 入口共用行内底色绘制，修改时两条入口一起受影响。
- 排版效果只能在 Android 截图上确认，浏览器示意不能代替。

## 核对入口

- `FcMarkdownText`、`FcMdCodeBlock`、`PiMarkdownTextView`、`PiInlineCodeSpan`；`FcMarkdownSplitTest`。
- 平台：`TextView#setBreakStrategy`、`LineBreaker#BREAK_STRATEGY_SIMPLE`、`TextView#setJustificationMode`、`Layout#getSelectionPath`。
