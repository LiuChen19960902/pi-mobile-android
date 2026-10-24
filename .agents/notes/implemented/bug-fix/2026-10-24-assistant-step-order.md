# Agent Note: 正文与步骤保留投影顺序

Status: implemented

## 问题

助手可能先给出一段说明，再进行思考或调用工具。若展示层把整回合的步骤统一提到正文之前，手机就会反转真实执行顺序；流式中的“正在执行”也会出现在先前正文上方。

## 决定

`groupAssistantRounds` 只组织布局回合，不排序回合成员。FineCat 的实际展示链是 `makeConversationDisplayEntries` → `mergeFineCatProcessEntries` → `groupAssistantRounds`；输出展平后必须与合并后的输入顺序一致。

历史投影同样保留语义位置：同一条助手记录中的思考、正文和工具调用按投影顺序显示，不能为了视觉统一把正文后的工具搬到正文前。可见正文仍是步骤合并边界，连续空助手记录之间的步骤仍可合并。

本决定仅取代[内联步骤记录](../feature/2026-10-13-inline-step-disclosure.md)中的“回合内步骤提前”规则，保留其分组、稳定键、紧凑间距与详情交互。

## 考虑过的方案

**维持步骤统一提前。** 原有测试和布局注释把它当成正确行为，但它会颠倒正文与后续操作的实际顺序。只调整正在运行的组，历史重载和完成态仍会错序，因此不按运行状态分别排序。

## 影响

步骤可能位于正文前后，取决于实际投影。测试需要覆盖真实根入口的完整展示链，并以实际历史投影生成思考、正文、工具记录；只测试步骤组件或沿用旧排序期望无法发现这个问题。

## 核对入口

- `ConversationDisplayGroups.kt`：`groupAssistantRounds`、`displayRoundKey`。
- `FineCatApp.kt`：`displayEntries`、`displayRounds` 与相邻类型间距。
- `FineCatProcessMergeTest`：流式尾组识别、历史投影、可见正文屏障。
- `ConversationDisplayGroupsTest`：回合成员顺序和回合边界。
- `FineCatConversationF02DeviceTest`：正文与后续步骤的几何顺序。
