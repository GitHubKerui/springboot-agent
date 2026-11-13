#!/usr/bin/env python3
"""Generate README indexes for docs/changes.

The record files are the source of truth. This script only builds navigation:
    docs/changes/README.md
    docs/changes/<category>/README.md
    docs/changes/<category>/<subcategory>/README.md

Usage:
    python scripts/change_index.py
    python scripts/change_index.py --check
"""

from __future__ import annotations

import argparse
import re
import sys
from dataclasses import dataclass
from pathlib import Path

GENERATED = "<!-- 自动生成，请勿手改；运行 python scripts/change_index.py 重新生成。 -->"
STATUSES = ("已完成", "部分完成", "未验证", "已回滚")
FILENAME_RE = re.compile(
    r"^(?P<date>\d{4}-\d{2}-\d{2})-(?P<seq>\d{2})-(?P<slug>[a-z0-9][a-z0-9-]*)\.md$"
)
NAME_RE = re.compile(r"^[a-z0-9][a-z0-9-]*$")


@dataclass(frozen=True)
class Record:
    path: Path
    category: str
    subcategory: str
    date: str
    status: str
    title: str


def parse_front_matter(text: str) -> tuple[dict[str, str], str]:
    """Read simple `key: value` front matter without a YAML dependency."""
    lines = text.splitlines()
    if not lines or lines[0].strip() != "---":
        return {}, text

    meta: dict[str, str] = {}
    for index, line in enumerate(lines[1:], start=1):
        if line.strip() == "---":
            return meta, "\n".join(lines[index + 1 :])
        if ":" in line:
            key, value = line.split(":", 1)
            meta[key.strip()] = value.strip().strip('"').strip("'")
    return {}, text


def first_heading(text: str) -> str:
    for line in text.splitlines():
        stripped = line.strip()
        if stripped.startswith("# "):
            return stripped[2:].strip()
    return ""


def load_records(changes_dir: Path) -> tuple[list[Record], list[str]]:
    records: list[Record] = []
    errors: list[str] = []

    for path in sorted(changes_dir.rglob("*.md")):
        if path.name == "README.md":
            continue

        rel = path.relative_to(changes_dir)
        if len(rel.parts) != 3:
            errors.append(f"{rel}: 必须放在 <大类>/<子类>/ 下（当前 {len(rel.parts)} 层）")
            continue

        category, subcategory, filename = rel.parts
        if not NAME_RE.match(category) or not NAME_RE.match(subcategory):
            errors.append(f"{rel}: 大类/子类目录名只能用小写字母、数字、连字符")
            continue

        match = FILENAME_RE.match(filename)
        if not match:
            errors.append(
                f"{rel}: 文件名必须为 YYYY-MM-DD-NN-slug.md，slug 只用小写字母、数字、连字符"
            )
            continue

        text = path.read_text(encoding="utf-8")
        meta, body = parse_front_matter(text)
        status = meta.get("status", "")
        if status not in STATUSES:
            errors.append(f"{rel}: status 必须是 {' / '.join(STATUSES)}")
            continue

        title = first_heading(body)
        if not title:
            errors.append(f"{rel}: 缺少一级标题（# 需求标题）")
            continue

        records.append(
            Record(
                path=path,
                category=category,
                subcategory=subcategory,
                date=match.group("date"),
                status=status,
                title=title,
            )
        )

    return records, errors


def cell(value: str) -> str:
    return value.replace("|", "\\|").replace("\n", " ").strip()


def render_root(records: list[Record]) -> str:
    categories = sorted({record.category for record in records})
    lines = [
        GENERATED,
        "",
        "# 变更记录",
        "",
        "按“大类 → 子类 → 具体记录”浏览。",
        "",
        "| 大类 | 记录数 | 入口 |",
        "| --- | ---: | --- |",
    ]
    for category in categories:
        count = sum(1 for record in records if record.category == category)
        lines.append(f"| `{category}` | {count} | [{category}]({category}/README.md) |")
    return "\n".join(lines) + "\n"


def render_category(category: str, records: list[Record]) -> str:
    subcategories = sorted(
        {record.subcategory for record in records if record.category == category}
    )
    lines = [
        GENERATED,
        "",
        f"# {category}",
        "",
        "| 子类 | 记录数 | 入口 |",
        "| --- | ---: | --- |",
    ]
    for subcategory in subcategories:
        count = sum(
            1
            for record in records
            if record.category == category and record.subcategory == subcategory
        )
        lines.append(
            f"| `{subcategory}` | {count} | [{subcategory}]({subcategory}/README.md) |"
        )
    return "\n".join(lines) + "\n"


def render_leaf(category: str, subcategory: str, records: list[Record]) -> str:
    rows = sorted(
        (
            record
            for record in records
            if record.category == category and record.subcategory == subcategory
        ),
        key=lambda record: (record.date, record.path.name),
        reverse=True,
    )
    lines = [
        GENERATED,
        "",
        f"# {category} / {subcategory}",
        "",
        "| 日期 | 需求 | 状态 | 记录 |",
        "| --- | --- | --- | --- |",
    ]
    for record in rows:
        lines.append(
            f"| {record.date} | {cell(record.title)} | {record.status} | "
            f"[{record.path.name}]({record.path.name}) |"
        )
    return "\n".join(lines) + "\n"


def build_indexes(changes_dir: Path, records: list[Record]) -> dict[Path, str]:
    indexes: dict[Path, str] = {
        changes_dir / "README.md": render_root(records),
    }
    for category in sorted({record.category for record in records}):
        indexes[changes_dir / category / "README.md"] = render_category(
            category, records
        )
        for subcategory in sorted(
            {record.subcategory for record in records if record.category == category}
        ):
            indexes[
                changes_dir / category / subcategory / "README.md"
            ] = render_leaf(category, subcategory, records)
    return indexes


def is_current(path: Path, content: str) -> bool:
    return path.exists() and path.read_text(encoding="utf-8") == content


def write(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="\n") as handle:
        handle.write(content)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="生成 docs/changes 的分层索引")
    parser.add_argument(
        "--root",
        type=Path,
        default=Path("docs/changes"),
        help="记录根目录，默认 docs/changes",
    )
    parser.add_argument(
        "--check",
        action="store_true",
        help="只检查索引是否最新，不写文件；过期时返回非零",
    )
    args = parser.parse_args(argv)

    changes_dir = args.root.resolve()
    if not changes_dir.is_dir():
        print(f"目录不存在：{changes_dir}", file=sys.stderr)
        return 2

    records, errors = load_records(changes_dir)
    if errors:
        print("发现记录格式错误：", file=sys.stderr)
        for error in errors:
            print(f"  - {error}", file=sys.stderr)
        return 1

    indexes = build_indexes(changes_dir, records)
    stale = [path for path, content in indexes.items() if not is_current(path, content)]

    if args.check:
        if stale:
            print("索引不是最新：", file=sys.stderr)
            for path in stale:
                print(f"  - {path.relative_to(changes_dir)}", file=sys.stderr)
            return 1
        print(f"索引已是最新，共 {len(records)} 条记录。")
        return 0

    for path in stale:
        write(path, indexes[path])
    print(f"已更新 {len(stale)} 个索引，共 {len(records)} 条记录。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
