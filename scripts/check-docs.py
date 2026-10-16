#!/usr/bin/env python3
"""检查公开文档与 Agent Notes 是否符合项目规范。

规则真源：Docs/AGENTS.md（文档规范）与 .agents/notes/README.md（Agent Note 格式）。
本脚本只做能机械判断的部分：
- Agent Note 的目录、文件名、头部、Status 与目录一致、必备章节；
- 公开文档中的相对链接与 #锚点能解析，且不指向不公开的路径；
- 公开文档中不出现本机路径、IP、凭据形态和开发期内部编号；
- 常驻规范文件的字数上限；
- 个人补充规则文件未被 Git 跟踪。

只依赖 Python 标准库和 git。用法：
  uv run --no-project python scripts/check-docs.py
"""

from __future__ import annotations

import datetime
import os
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path

LIFECYCLES = ("proposed", "implemented", "rejected")
CLASSES = ("feature", "bug-fix", "simplification", "architecture", "process", "testing")
NOTE_ROOT_FILES = {"README.md", "AGENTS.md"}

STATUS = {
    "proposed": re.compile(r"^Status: proposed$"),
    "implemented": re.compile(r"^Status: implemented$"),
    "rejected": re.compile(r"^Status: rejected — \S.*$"),
}
ALTERNATIVES = "## 考虑过的方案"
REQUIRED = {
    "proposed": ["## 提案", ALTERNATIVES, "## 验收标准", "## 风险"],
    "implemented": ["## 决定", ALTERNATIVES, "## 影响"],
    "rejected": ["## 提案", ALTERNATIVES],
}
# implemented 记录描述已交付的现状，不保留提案期的章节。
BANNED_IMPLEMENTED = re.compile(r"^## (提案|计划|迁移计划|验收标准)\b")

# 公开文档：仓根的说明文件、Docs/（不含私有评审记录）、所有 AGENTS.md / CLAUDE.md 与 Agent Notes。
PUBLIC_ROOT_DOCS = ("README.md", "ARCHITECTURE.md", "CONTRIBUTING.md", "SECURITY.md", "CHANGELOG.md")
# 不进入公开快照的路径前缀，公开文档不得链接到这些位置。
PRIVATE_PREFIXES = (".pi/", ".agents/private/", "Docs/reviews/", "FineCat/")
PRIVATE_FILES = {"agents.override.md", "agents.local.md", "claude.local.md", "bug-full.md", "gateway-optim.md"}
LOCAL_AGENT_FILES = {"agents.override.md", "agents.local.md", "claude.local.md"}
# git 不可用时遍历目录需要跳过的目录（构建产物、依赖与本地状态）。
SKIP_DIRS = {".git", ".pi", ".gradle", ".kotlin", ".idea", ".codegraph", "build", "node_modules", "__pycache__", ".venv"}

# 常驻规范文件的字数上限（中文按字、英文按词计）。超限时先迁走或精简，确需空间再提高上限。
BUDGETS = {
    "AGENTS.md": 2000,
    "Docs/AGENTS.md": 1400,
    ".agents/notes/AGENTS.md": 260,
    ".agents/notes/README.md": 1900,
}

# 行内豁免：在同一行写 <!-- check-docs: allow <规则名> -->。
ALLOW_RE = re.compile(r"<!--\s*check-docs:\s*allow\s+([\w-]+)\s*-->")

PRIVACY_RULES = [
    ("ipv4", re.compile(r"(?<![\d.])(?!127\.0\.0\.1\b|0\.0\.0\.0\b)(?:\d{1,3}\.){3}\d{1,3}(?![\d.]|/\d)"),
     "出现 IP 地址（网段 CIDR 除外），示例用 <LAN_IP>、<TS_HOST> 等记号"),
    ("home-path", re.compile(r"(?:/home/[\w.-]+|/Users/[\w.-]+|[A-Za-z]:\\Users\\|/mnt/[a-z]/)"),
     "出现本机绝对路径"),
    ("tailnet-host", re.compile(r"\b[\w-]+\.[\w-]+\.ts\.net\b"), "出现 tailnet 主机名"),
    ("secret", re.compile(r"\b(?:sk-[A-Za-z0-9_-]{16,}|ghp_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|AKIA[0-9A-Z]{16})\b"),
     "出现疑似凭据"),
    ("email", re.compile(r"\b(?!git@[\w.-]+:)[\w.+-]+@(?!users\.noreply\.github\.com)[\w-]+(?:\.[\w-]+)+\b"),
     "出现邮箱地址"),
]
# 只在规范文件与 Agent Notes 中检查的开发期痕迹。
INTERNAL_RULES = [
    ("internal-id", re.compile(r"\b(?:code\d{1,3}|r\d{2}|build-\d+)\b"), "出现内部构建号或候选编号"),
    ("todo-id", re.compile(r"\bTODO\s+[A-Z]{2,}\d+"), "出现内部 TODO 编号"),
    ("process-word", re.compile(r"负责人|候选包|HANDOFF"), "出现开发过程叙述，改写为机制与条件"),
]


@dataclass
class Report:
    errors: list[str]
    summary: str = ""

    def add(self, path: str, line: int | None, message: str) -> None:
        where = f"{path}:{line}" if line else path
        self.errors.append(f"{where}: {message}")


def strip_fences(lines: list[str]) -> list[tuple[int, str]]:
    """返回不在代码围栏内的 (行号, 文本)。"""
    out, fence = [], None
    for number, line in enumerate(lines, 1):
        marker = re.match(r"^\s*(```+|~~~+)", line)
        if marker:
            token = marker.group(1)[0] * 3
            if fence is None:
                fence = token
            elif token == fence:
                fence = None
            continue
        if fence is None:
            out.append((number, line))
    return out


def strip_inline_code(text: str) -> str:
    return re.sub(r"`[^`]*`", "", text)


def git_files(root: Path) -> list[str]:
    """已跟踪与未被忽略的新增文件。不在 Git 仓库中时退回遍历目录。"""
    try:
        proc = subprocess.run(
            ["git", "-C", str(root), "ls-files", "-z", "--cached", "--others", "--exclude-standard"],
            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, check=True,
        )
    except (OSError, subprocess.CalledProcessError):
        return sorted(p.relative_to(root).as_posix() for p in root.rglob("*")
                      if p.is_file() and not SKIP_DIRS.intersection(p.relative_to(root).parts))
    names = {n for n in proc.stdout.decode("utf-8", "replace").split("\0") if n}
    return sorted(n for n in names if (root / n).is_file())


def tracked_files(root: Path) -> set[str]:
    try:
        proc = subprocess.run(["git", "-C", str(root), "ls-files", "-z"],
                              stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, check=True)
    except (OSError, subprocess.CalledProcessError):
        return set()
    return {n for n in proc.stdout.decode("utf-8", "replace").split("\0") if n}


def is_private(rel: str) -> bool:
    return rel.startswith(PRIVATE_PREFIXES) or Path(rel).name.lower() in PRIVATE_FILES


def public_docs(files: list[str]) -> list[str]:
    docs = []
    for rel in files:
        if not rel.endswith(".md") or is_private(rel) or (rel == "CLAUDE.md"):
            continue
        name = Path(rel).name
        if rel in PUBLIC_ROOT_DOCS or rel.startswith(("Docs/", ".agents/notes/")) or name in ("AGENTS.md", "CLAUDE.md"):
            docs.append(rel)
    return docs


def slugify(heading: str) -> str:
    """GitHub 风格的标题锚点。"""
    text = re.sub(r"!?\[([^\]]*)\]\([^)]*\)", r"\1", heading)
    text = text.replace("`", "").replace("*", "").strip().lower()
    text = re.sub(r"<[^>]+>", "", text)
    text = re.sub(r"[^\w\- ]", "", text)
    return text.replace(" ", "-")


def anchors_of(path: Path) -> set[str]:
    seen: dict[str, int] = {}
    anchors = set()
    for _, line in strip_fences(path.read_text(encoding="utf-8").splitlines()):
        match = re.match(r"^(#{1,6})\s+(.+?)\s*#*\s*$", line)
        if match:
            base = slugify(match.group(2))
            count = seen.get(base, 0)
            seen[base] = count + 1
            anchors.add(base if count == 0 else f"{base}-{count}")
        for explicit in re.findall(r'<a\s+(?:id|name)="([^"]+)"', line):
            anchors.add(explicit)
    return anchors


LINK_RE = re.compile(r"(?<!\\)!?\[(?:[^\]\\]|\\.)*\]\(\s*<?([^)\s>]+)>?(?:\s+\"[^\"]*\")?\s*\)")


def check_links(root: Path, docs: list[str], report: Report) -> None:
    cache: dict[Path, set[str]] = {}
    for rel in docs:
        path = root / rel
        for number, line in strip_fences(path.read_text(encoding="utf-8").splitlines()):
            for target in LINK_RE.findall(strip_inline_code(line)):
                if re.match(r"^[a-zA-Z][a-zA-Z0-9+.-]*:", target) or target.startswith("/"):
                    continue
                file_part, _, fragment = target.partition("#")
                file_part = file_part.split("?")[0]
                dest = (path.parent / file_part).resolve() if file_part else path.resolve()
                try:
                    dest_rel = dest.relative_to(root.resolve()).as_posix()
                except ValueError:
                    report.add(rel, number, f"链接指向仓库之外：{target}")
                    continue
                if is_private(dest_rel) or is_private(dest_rel + "/"):
                    report.add(rel, number, f"链接指向不公开的路径：{target}")
                    continue
                if not dest.exists():
                    report.add(rel, number, f"链接目标不存在：{target}")
                    continue
                if fragment and dest.suffix == ".md":
                    anchors = cache.setdefault(dest, anchors_of(dest))
                    if fragment not in anchors:
                        report.add(rel, number, f"锚点不存在：{target}")


def check_text_rules(root: Path, docs: list[str], report: Report) -> None:
    for rel in docs:
        internal = rel.startswith(".agents/notes/") or Path(rel).name in ("AGENTS.md", "CLAUDE.md")
        rules = PRIVACY_RULES + (INTERNAL_RULES if internal else [])
        for number, line in strip_fences((root / rel).read_text(encoding="utf-8").splitlines()):
            allowed = set(ALLOW_RE.findall(line))
            for name, pattern, message in rules:
                if name not in allowed and pattern.search(line):
                    report.add(rel, number, f"[{name}] {message}")
            for prefix in ("Docs/reviews/", ".agents/private/"):
                if prefix in line and "private-ref" not in allowed:
                    report.add(rel, number, f"[private-ref] 提到不公开的路径 {prefix}")


def check_note_tree(root: Path, files: list[str], report: Report) -> int:
    notes = 0
    for rel in files:
        if not rel.startswith(".agents/notes/"):
            continue
        parts = rel.split("/")[2:]
        if len(parts) == 1 and parts[0] in NOTE_ROOT_FILES:
            continue
        if len(parts) == 2 and parts[0] in LIFECYCLES and parts[1] in NOTE_ROOT_FILES:
            continue
        if len(parts) != 3 or parts[0] not in LIFECYCLES or parts[1] not in CLASSES:
            report.add(rel, None, f"路径应为 {{{'|'.join(LIFECYCLES)}}}/{{{'|'.join(CLASSES)}}}/yyyy-mm-dd-topic.md")
            continue
        match = re.fullmatch(r"(\d{4}-\d{2}-\d{2})-[a-z0-9]+(?:-[a-z0-9]+)*\.md", parts[2])
        if not match:
            report.add(rel, None, "文件名应为 yyyy-mm-dd-小写英文短横线.md")
            continue
        try:
            datetime.date.fromisoformat(match.group(1))
        except ValueError:
            report.add(rel, None, f"文件名中的日期无效：{match.group(1)}")
        notes += 1
        check_note_format(root, rel, parts[0], report)
    return notes


def check_note_format(root: Path, rel: str, lifecycle: str, report: Report) -> None:
    lines = (root / rel).read_text(encoding="utf-8").splitlines()
    while len(lines) < 4:
        lines.append("")
    if not re.match(r"^# Agent Note: \S", lines[0]):
        report.add(rel, 1, "第 1 行应为 `# Agent Note: <标题>`")
    if lines[1] != "":
        report.add(rel, 2, "第 2 行应为空行")
    if not STATUS[lifecycle].match(lines[2]):
        report.add(rel, 3, f"第 3 行的 Status 与目录 {lifecycle}/ 不一致")
    if lines[3] != "":
        report.add(rel, 4, "第 4 行应为空行")
    prose = strip_fences(lines)
    if sum(1 for _, line in prose if line.startswith("Status:")) != 1:
        report.add(rel, None, "Status 行只能出现一次")
    h2s = [line.rstrip() for _, line in prose if line.startswith("## ")]
    if not h2s or h2s[0] != "## 问题":
        report.add(rel, None, "第一个章节应为 `## 问题`")
    for required in REQUIRED[lifecycle]:
        if required not in h2s:
            report.add(rel, None, f"缺少必备章节 `{required}`")
    if lifecycle == "implemented":
        for heading in h2s:
            if BANNED_IMPLEMENTED.match(heading):
                report.add(rel, None, f"implemented 记录不保留提案期章节 `{heading}`")


def count_words(text: str) -> int:
    cjk = len(re.findall(r"[\u3400-\u9fff\uf900-\ufaff]", text))
    latin = len(re.findall(r"[A-Za-z0-9_][A-Za-z0-9_'./-]*", text))
    return cjk + latin


def check_budgets(root: Path, report: Report) -> None:
    for rel, ceiling in BUDGETS.items():
        path = root / rel
        if not path.is_file():
            report.add(rel, None, "字数预算登记的文件不存在")
            continue
        words = count_words(path.read_text(encoding="utf-8"))
        if words > ceiling:
            report.add(rel, None, f"字数 {words} 超过上限 {ceiling}：先迁走或精简，确需空间再提高 scripts/check-docs.py 中的上限")


def check_local_files(root: Path, report: Report) -> None:
    tracked = tracked_files(root)
    for rel in sorted(tracked):
        if Path(rel).name.lower() in LOCAL_AGENT_FILES:
            report.add(rel, None, "个人补充规则文件不能提交，请 git rm --cached")
    claude = root / "CLAUDE.md"
    if claude.is_symlink():
        if os.readlink(claude) != "AGENTS.md":
            report.add("CLAUDE.md", None, "CLAUDE.md 应是指向 AGENTS.md 的符号链接")
    elif claude.exists():
        report.add("CLAUDE.md", None, "CLAUDE.md 应是指向 AGENTS.md 的符号链接，不要写成普通文件")


def run(root: Path) -> Report:
    report = Report([])
    files = git_files(root)
    docs = public_docs(files)
    notes = check_note_tree(root, files, report)
    check_links(root, docs, report)
    check_text_rules(root, docs, report)
    check_budgets(root, report)
    check_local_files(root, report)
    report.summary = f"{len(docs)} 份公开文档、{notes} 份 Agent Note"
    return report


def main(argv: list[str] | None = None) -> int:
    root = Path(argv[0] if argv else Path(__file__).resolve().parents[1]).resolve()
    report = run(root)
    if report.errors:
        print("check-docs: 发现问题（规则见 Docs/AGENTS.md 与 .agents/notes/README.md）：", file=sys.stderr)
        for error in report.errors:
            print(f"  {error}", file=sys.stderr)
        return 1
    print(f"check-docs: 通过，检查了 {report.summary}。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
