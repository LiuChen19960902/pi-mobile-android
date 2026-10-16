"""scripts/check-docs.py 的回归测试。

运行：uv run --no-project python -m unittest discover -s scripts -p 'test_*.py'
"""

from __future__ import annotations

import importlib.util
import subprocess
import sys
import tempfile
import textwrap
import unittest
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parent


def load(name: str, filename: str):
    spec = importlib.util.spec_from_file_location(name, SCRIPTS / filename)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


check = load("check_docs", "check-docs.py")

GOOD_NOTE = textwrap.dedent("""\
    # Agent Note: 示例

    Status: implemented

    ## 问题

    问题描述。

    ## 决定

    决定描述。

    ## 考虑过的方案

    原始记录未保留其他方案。

    ## 影响

    影响描述。
    """)

NOTE_PATH = ".agents/notes/implemented/architecture/2026-01-01-sample.md"


class CheckDocsTest(unittest.TestCase):
    # 每个用例在临时 git 仓库中构造文件，check.run 读取工作区中已跟踪与未忽略的文件。
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        subprocess.run(["git", "init", "-q", str(self.root)], check=True)
        # 预算登记的文件需要存在，内容尽量短。
        for rel in check.BUDGETS:
            self.write(rel, "# 标题\n")
        (self.root / "CLAUDE.md").symlink_to("AGENTS.md")
        self.write(NOTE_PATH, GOOD_NOTE)

    def tearDown(self) -> None:
        self.tmp.cleanup()

    def write(self, rel: str, text: str) -> None:
        path = self.root / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")

    def errors(self) -> list[str]:
        return check.run(self.root).errors

    def assertError(self, fragment: str) -> None:
        errors = self.errors()
        self.assertTrue(any(fragment in e for e in errors), f"缺少包含 {fragment!r} 的报错：{errors}")

    def test_clean_tree_passes(self) -> None:
        self.assertEqual(self.errors(), [])

    def test_status_must_match_lifecycle(self) -> None:
        self.write(NOTE_PATH, GOOD_NOTE.replace("Status: implemented", "Status: proposed"))
        self.assertError("Status 与目录 implemented/ 不一致")

    def test_alternatives_section_is_required(self) -> None:
        self.write(NOTE_PATH, GOOD_NOTE.replace("## 考虑过的方案", "## 其他"))
        self.assertError("缺少必备章节 `## 考虑过的方案`")

    def test_implemented_rejects_proposal_headings(self) -> None:
        self.write(NOTE_PATH, GOOD_NOTE + "\n## 验收标准\n\n- x\n")
        self.assertError("不保留提案期章节")

    def test_headings_inside_fences_are_ignored(self) -> None:
        self.write(NOTE_PATH, GOOD_NOTE + "\n```markdown\n## 验收标准\nStatus: proposed\n```\n")
        self.assertEqual(self.errors(), [])

    def test_unknown_class_and_bad_filename(self) -> None:
        self.write(".agents/notes/implemented/refactor/2026-01-01-x.md", GOOD_NOTE)
        self.write(".agents/notes/implemented/feature/Bad_Name.md", GOOD_NOTE)
        self.write(".agents/notes/implemented/feature/2026-13-40-bad-date.md", GOOD_NOTE)
        errors = self.errors()
        self.assertTrue(any("refactor" in e and "路径应为" in e for e in errors), errors)
        self.assertTrue(any("Bad_Name" in e and "文件名" in e for e in errors), errors)
        self.assertTrue(any("日期无效" in e for e in errors), errors)

    def test_missing_link_target_and_anchor(self) -> None:
        self.write("README.md", "# 读我\n\n## 安装 步骤\n\n[a](missing.md) [b](#安装-步骤) [c](#不存在)\n")
        errors = self.errors()
        self.assertTrue(any("链接目标不存在：missing.md" in e for e in errors), errors)
        self.assertTrue(any("锚点不存在：#不存在" in e for e in errors), errors)
        self.assertFalse(any("#安装-步骤" in e for e in errors), errors)

    def test_link_to_private_path_is_rejected(self) -> None:
        self.write(".pi/x.md", "x\n")
        self.write("Docs/guide.md", "[x](../.pi/x.md)\n")
        self.assertError("链接指向不公开的路径")

    def test_privacy_patterns(self) -> None:
        self.write("Docs/guide.md", "地址 192.168.1.20，目录 /home/alice/x，网段 100.64.0.0/10，回环 127.0.0.1\n")
        errors = [e for e in self.errors() if e.startswith("Docs/guide.md")]
        self.assertTrue(any("[ipv4]" in e for e in errors), errors)
        self.assertTrue(any("[home-path]" in e for e in errors), errors)
        self.assertEqual(sum("[ipv4]" in e for e in errors), 1, errors)

    def test_internal_ids_only_in_instruction_docs(self) -> None:
        self.write(NOTE_PATH, GOOD_NOTE.replace("决定描述。", "见 code53 与 TODO NET06。"))
        self.write("Docs/guide.md", "code53\n")
        errors = self.errors()
        self.assertTrue(any(NOTE_PATH in e and "[internal-id]" in e for e in errors), errors)
        self.assertTrue(any(NOTE_PATH in e and "[todo-id]" in e for e in errors), errors)
        self.assertFalse(any(e.startswith("Docs/guide.md") for e in errors), errors)

    def test_allow_marker_is_line_scoped(self) -> None:
        self.write(NOTE_PATH, GOOD_NOTE.replace(
            "决定描述。", "不写“负责人”。 <!-- check-docs: allow process-word -->\n\n负责人"))
        errors = self.errors()
        self.assertEqual(sum("[process-word]" in e for e in errors), 1, errors)

    def test_budget(self) -> None:
        self.write("AGENTS.md", "字" * (check.BUDGETS["AGENTS.md"] + 1))
        self.assertError("超过上限")

    def test_fallback_walk_skips_build_dirs(self) -> None:
        (self.root / ".git").rename(self.root / ".git-off")
        self.write("build/AGENTS.md", "/home/alice\n")
        self.write("node_modules/pkg/CLAUDE.md", "/home/alice\n")
        self.assertEqual(self.errors(), [])
        self.write("Docs/guide.md", "/home/alice\n")
        self.assertError("[home-path]")

    def test_git_ssh_url_is_not_email(self) -> None:
        self.write("Docs/guide.md", "`git clone git@github.com:owner/repo.git`，联系 a.b@example.org\n")
        errors = [e for e in self.errors() if "[email]" in e]
        self.assertEqual(len(errors), 1, errors)

    def test_tracked_override_variant_is_rejected(self) -> None:
        self.write("agents.override.md", "私人\n")
        subprocess.run(["git", "-C", str(self.root), "add", "-f", "agents.override.md"], check=True)
        self.assertError("个人补充规则文件不能提交")

    def test_claude_md_must_be_symlink_to_agents(self) -> None:
        (self.root / "CLAUDE.md").unlink()
        self.write("CLAUDE.md", "@AGENTS.md\n")
        self.assertError("符号链接")
        (self.root / "CLAUDE.md").unlink()
        (self.root / "CLAUDE.md").symlink_to("README.md")
        self.assertError("符号链接")
        (self.root / "CLAUDE.md").unlink()
        (self.root / "CLAUDE.md").symlink_to("./AGENTS.md")
        self.assertError("符号链接")

    def test_tracked_override_file_is_rejected(self) -> None:
        self.write("AGENTS.override.md", "私人\n")
        subprocess.run(["git", "-C", str(self.root), "add", "-f", "AGENTS.override.md"], check=True)
        self.assertError("个人补充规则文件不能提交")


if __name__ == "__main__":
    unittest.main()
