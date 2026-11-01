"""把生成好的报错渲染图插到每篇 Java 笔记的标题下面。

规则：
- 图放在 H1 之后、正文之前
- 配一行说明，避免被当成真实终端截图
- 已经有图的跳过（重复执行安全）

用法：python scripts/insert_shots.py
"""

from pathlib import Path

import re

ROOT = Path(__file__).resolve().parent.parent
DOCS = ROOT / "docs"
IMAGES = DOCS / "public" / "images"

OVERRIDES = {
    "Nginx缓冲导致流式失效.md": "nginx-buffering",
    "多用户共用一份ChatMemory.md": "chatmemory-shared",
    "注入ChatClient找不到Bean.md": "chatclient-bean",
    "SSE响应中文乱码.md": "sse-charset",
}

CAPTION = "> 上图按本条「报错」原文渲染，文本逐字一致 —— 可以直接拿去和你的日志比对。"


def slug_for(block: str, name: str) -> str:
    if name in OVERRIDES:
        return "java-" + OVERRIDES[name]
    first = block.splitlines()[0] if block else ""
    tokens = re.findall(r"[A-Za-z][A-Za-z0-9_.$]*", first)
    for t in tokens:
        if re.search(r"Error|Exception|error", t):
            return "java-" + re.sub(r"[^a-z0-9]+", "-", t.split(".")[-1].lower()).strip("-")
    # 没有异常名的（比如以 SLF4J: 或 <html> 开头），退回第一个英文词，与 gen_shots 一致
    if tokens:
        return "java-" + re.sub(r"[^a-z0-9]+", "-", tokens[0].lower()).strip("-")
    return ""


def section(text: str) -> str:
    lines = text.splitlines()
    for i, line in enumerate(lines):
        if line.strip() in ("## 报错", "## 现象"):
            body = []
            for l in lines[i + 1 :]:
                if l.strip().startswith("## "):
                    break
                body.append(l)
            return "\n".join(body)
    return ""


def first_block(sec: str) -> str:
    inside, buf = False, []
    for line in sec.splitlines():
        if line.strip().startswith("```"):
            if not inside:
                inside = True
                continue
            break
        if inside:
            buf.append(line)
    return "\n".join(buf).strip()


def main() -> None:
    inserted = 0
    for md in sorted((DOCS / "java").glob("*.md")):
        if md.name == "index.md":
            continue
        text = md.read_text(encoding="utf-8")
        if "docs/public/images/java-" in text or "/images/java-" in text:
            continue

        slug = slug_for(first_block(section(text)), md.name)
        if not slug or not (IMAGES / f"{slug}.png").exists():
            print(f"跳过（没有对应图）：{md.name}")
            continue

        lines = text.splitlines()
        h1 = next((i for i, l in enumerate(lines) if l.startswith("# ")), None)
        if h1 is None:
            print(f"跳过（没有 H1）：{md.name}")
            continue

        insert = ["", f"![报错原文](/images/{slug}.png)", "", CAPTION]
        lines[h1 + 1 : h1 + 1] = insert
        md.write_text("\n".join(lines) + "\n", encoding="utf-8")
        inserted += 1
        print(f"插入：{md.name} ← {slug}.png")

    print(f"完成，插入 {inserted} 篇")


if __name__ == "__main__":
    main()