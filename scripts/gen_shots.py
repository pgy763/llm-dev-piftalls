"""把每篇 Java 踩坑笔记「## 报错」里的原始报错，渲染成一张终端风格的图。

为什么要做这件事：
README 里承诺了「扫一眼截图就知道是不是同一个错」，但 Java 那 16 条一直没配图。
真实终端截图需要逐条复现环境（有的要装 Nginx、有的要两套 JDK），成本太高；
所以这里退一步：**渲染报错原文**，文本逐字一致，风格统一。

注意：这**不是**真实终端截图，正文和 README 里都按「报错原文渲染图」标注。
新增条目请优先用真机截图（见 CONTRIBUTING.md）。

用法：
    python scripts/gen_shots.py            # 生成缺图的条目
    python scripts/gen_shots.py --force    # 全部重新生成
"""

from __future__ import annotations

import hashlib
import re
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent
DOCS = ROOT / "docs"
OUT = DOCS / "public" / "images"

WIDTH, PAD, LINE_H = 1180, 28, 30
BG = (30, 30, 30)
BAR = (45, 45, 45)
FG = (220, 220, 220)
RED = (255, 123, 114)
GREEN = (126, 231, 135)
COMMENT = (140, 140, 140)

FONT_CANDIDATES = [
    r"C:\Windows\Fonts\msyh.ttc",
    r"C:\Windows\Fonts\consola.ttf",
    "/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf",
]


def pick_font(size: int) -> ImageFont.FreeTypeFont:
    for path in FONT_CANDIDATES:
        if Path(path).exists():
            try:
                return ImageFont.truetype(path, size)
            except OSError:
                continue
    return ImageFont.load_default()


FONT = pick_font(19)
SMALL = pick_font(15)


def read_section(text: str, headings: tuple[str, ...]) -> list[str]:
    lines = text.splitlines()
    start = None
    for i, line in enumerate(lines):
        if line.strip() in headings:
            start = i + 1
            break
    if start is None:
        return []
    out = []
    for line in lines[start:]:
        if line.strip().startswith("## "):
            break
        out.append(line)
    return out


def first_block(section: list[str]) -> str:
    inside = False
    buf: list[str] = []
    for line in section:
        if line.strip().startswith("```"):
            if not inside:
                inside = True
                continue
            break
        if inside:
            buf.append(line.rstrip())
    while buf and not buf[0].strip():
        buf.pop(0)
    while buf and not buf[-1].strip():
        buf.pop()
    return "\n".join(buf)


def slugify(block: str, name: str) -> str:
    first = block.splitlines()[0] if block else ""
    tokens = re.findall(r"[A-Za-z][A-Za-z0-9_.$]*", first)
    # 优先用异常类名，比如 LinkageError / NoSuchMethodError
    for t in tokens:
        if any(k in t for k in ("Error", "Exception", "exception", "error")):
            short = t.split(".")[-1]          # java.lang.LinkageError → LinkageError
            slug = re.sub(r"[^a-z0-9]+", "-", short.lower()).strip("-")
            if slug:
                return slug[:40]
    if tokens:
        slug = re.sub(r"[^a-z0-9]+", "-", tokens[0].lower()).strip("-")
        if slug:
            return slug[:40]
    return "case-" + hashlib.sha1(name.encode("utf-8")).hexdigest()[:8]


# 少数条目第一行不是异常名（有的以命令开头、有的是静默错误），手动给个能读的文件名
OVERRIDES = {
    "Nginx缓冲导致流式失效.md": "nginx-buffering",
    "多用户共用一份ChatMemory.md": "chatmemory-shared",
    "注入ChatClient找不到Bean.md": "chatclient-bean",
}

def wrap(line: str, font: ImageFont.FreeTypeFont, max_px: int) -> list[str]:
    if not line:
        return [""]
    out, cur = [], ""
    for ch in line:
        if font.getlength(cur + ch) > max_px:
            out.append(cur)
            cur = "    " + ch  # 续行缩进，像终端
        else:
            cur += ch
    out.append(cur)
    return out


def render(block: str, dest: Path) -> None:
    lines: list[tuple[str, tuple[int, int, int]]] = []
    for raw in block.splitlines():
        for part in wrap(raw.replace("\t", "    "), FONT, WIDTH - PAD * 2):
            color = FG
            if re.search(r"Exception|ERROR|FATAL|error:", part):
                color = RED
            elif re.match(r"\s*at ", part):
                color = COMMENT
            elif part.startswith("$ ") or part.startswith("C:\\"):
                color = GREEN
            lines.append((part, color))

    if not lines:
        return
    lines = lines[:16]
    height = 54 + LINE_H * len(lines) + PAD

    img = Image.new("RGB", (WIDTH, height), BG)
    d = ImageDraw.Draw(img)
    d.rectangle([0, 0, WIDTH, 40], fill=BAR)
    for i, c in enumerate([(255, 95, 86), (255, 189, 46), (39, 201, 63)]):
        d.ellipse([18 + i * 22, 14, 30 + i * 22, 26], fill=c)
    d.text((100, 11), "报错原文 · 摘自本条笔记", font=SMALL, fill=COMMENT)

    y = 56
    for text, color in lines:
        d.text((PAD, y), text, font=FONT, fill=color)
        y += LINE_H

    img.save(dest, "PNG", optimize=True)


def main() -> None:
    force = "--force" in sys.argv
    OUT.mkdir(parents=True, exist_ok=True)
    used: set[str] = set()
    made = 0
    for md in sorted((DOCS / "java").glob("*.md")):
        if md.name == "index.md":
            continue
        text = md.read_text(encoding="utf-8")
        section = read_section(text, ("## 报错", "## 现象"))
        block = first_block(section)
        if not block:
            # 静默错误类没有异常输出：取「## 现象」下的正文前几行
            prose = [
                l.strip()
                for l in section
                if l.strip() and not l.strip().startswith(("![", "```", "|"))
            ][:5]
            block = "\n".join(prose)
        if not block:
            print(f"跳过（这一段是空的）：{md.name}")
            continue

        slug = OVERRIDES.get(md.name) or slugify(block, md.name)
        base, n = slug, 2
        while slug in used:
            slug = f"{base}-{n}"
            n += 1
        used.add(slug)

        dest = OUT / f"java-{slug}.png"
        if dest.exists() and not force:
            print(f"已有：{dest.name}")
            continue
        render(block, dest)
        print(f"生成：{md.name} -> {dest.name}")
        made += 1
    print(f"完成，新生成 {made} 张")


if __name__ == "__main__":
    main()