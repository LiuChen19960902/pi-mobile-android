# AGENTS.md — Agent Notes

Agent Note 是代码库的设计决策记录。目录、格式与何时必须写见 [README.md](README.md)；文字规则见 [文档规范](../../Docs/AGENTS.md)。

- **新增记录前先查重**：用 `rg` 按符号、文件名和症状搜索已有记录。同一决策更新原记录；部分取代时两份都保留并互相链接；完全取代时按 README 的“取代与合并”处理。
- **`implemented/` 记录与代码保持一致**：改动移动了文件、改了符号名、默认值或机制时，在同一提交中就地更新记录中的事实，不追加变更历史。决策本身改变时新建记录，不把旧记录改写成相反的结论。
- 写完运行 `uv run --no-project python scripts/check-docs.py`。
