#!/usr/bin/env python3
"""Build both checked-in offline guides. Requires reportlab; plugin builds use the bundled PDFs."""
import argparse
import hashlib
import html
import json
import re
import shutil
import subprocess
import sys
from pathlib import Path

from reportlab.lib import colors
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import SimpleDocTemplate, Paragraph, Spacer, Table, TableStyle, Preformatted, PageBreak, Flowable
from reportlab.platypus.tableofcontents import TableOfContents

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--language", choices=["hu", "en", "all"], default="all", help="Build both languages by default")
parser.add_argument("--output", type=Path, help="Custom output PDF; requires --language hu or en")
parser.add_argument("--font-dir", type=Path, help="Directory with DejaVuSans, DejaVuSans-Bold and DejaVuSansMono TTF files, or Arial/Courier New TTF files")
args = parser.parse_args()
if args.language == "all":
    if args.output:
        parser.error("--output requires a single --language (hu or en)")
    for language in ("hu", "en"):
        command = [sys.executable, str(Path(__file__).resolve()), "--language", language]
        if args.font_dir:
            command += ["--font-dir", str(args.font_dir)]
        subprocess.run(command, check=True)
    raise SystemExit(0)

SOURCE = ROOT / f"docs/repl-help-{args.language}.md"
RESOURCE = ROOT / f"src/main/resources/help/spring-boot-repl-guide-{args.language}.pdf"
if args.output is None:
    args.output = ROOT / f"output/pdf/spring-boot-repl-guide-{args.language}.pdf"

def localized(hu, en):
    return hu if args.language == "hu" else en

version = re.search(r'^version = "([^"]+)"', (ROOT / "build.gradle.kts").read_text(), re.M)[1]
mcp_tool_count = sum(len(re.findall(r'McpTool\("repl_', path.read_text())) for path in
                     (ROOT / "src/main/kotlin/hu/baader/repl/mcp").glob("*Tools.kt"))
source = SOURCE.read_text().replace("{{version}}", version)
assert not any(c in source for c in "\u2010\u2011\u2012\u2013\u2014"), "Use ASCII hyphens in the PDF source"
font_dirs = [args.font_dir] if args.font_dir else [Path("/usr/share/fonts/truetype/dejavu"), Path("/System/Library/Fonts/Supplemental")]
for directory in font_dirs:
    for files in [("DejaVuSans.ttf", "DejaVuSans-Bold.ttf", "DejaVuSansMono.ttf"), ("Arial.ttf", "Arial Bold.ttf", "Courier New.ttf")]:
        if all((directory / name).is_file() for name in files):
            font_paths = [directory / name for name in files]
            break
    else:
        continue
    break
else:
    raise SystemExit("Pass --font-dir with DejaVu Sans/Mono or Arial/Courier New fonts (including Hungarian accents)")
for name, path in zip(["Guide", "GuideBold", "GuideMono"], font_paths):
    pdfmetrics.registerFont(TTFont(name, str(path)))
    assert all(ord(c) in pdfmetrics.getFont(name).face.charToGlyph for c in "ÁÉÍÓÖŐÚÜŰáéíóöőúüű"), name
pdfmetrics.registerFontFamily("Guide", normal="Guide", bold="GuideBold", italic="Guide", boldItalic="GuideBold")
pdfmetrics.registerFontFamily("GuideMono", normal="GuideMono", bold="GuideMono", italic="GuideMono", boldItalic="GuideMono")

INK = colors.HexColor("#153044")
ACCENT = colors.HexColor("#087F8C")
MUTED = colors.HexColor("#526879")
PALE = colors.HexColor("#F1F6F8")
RULE = colors.HexColor("#D8E4EA")
WIDTH = A4[0] - 96
styles = {
    "body": ParagraphStyle("Body", fontName="Guide", fontSize=10.2, leading=14.2, textColor=INK, spaceAfter=7, allowWidows=0, allowOrphans=0),
    "title": ParagraphStyle("Title", fontName="GuideBold", fontSize=22, leading=27, textColor=INK, spaceAfter=15, keepWithNext=True),
    "cover": ParagraphStyle("Cover", fontName="GuideBold", fontSize=40, leading=46, textColor=INK, spaceBefore=24, spaceAfter=24, keepWithNext=True),
    "heading": ParagraphStyle("Heading", fontName="GuideBold", fontSize=12.8, leading=16, textColor=ACCENT, spaceBefore=10, spaceAfter=6, keepWithNext=True),
    "cell": ParagraphStyle("Cell", fontName="Guide", fontSize=9.3, leading=12.5, textColor=INK),
    "headcell": ParagraphStyle("HeadCell", fontName="GuideBold", fontSize=9.3, leading=12.5, textColor=colors.white),
    "code": ParagraphStyle("Code", fontName="GuideMono", fontSize=9.1, leading=12.3, textColor=INK),
    "list": ParagraphStyle("List", fontName="Guide", fontSize=10.2, leading=14.2, textColor=INK, leftIndent=15, firstLineIndent=-15, spaceAfter=5),
}

def inline(value):
    parts = []
    for index, part in enumerate(value.split("`")):
        if index % 2:
            parts.append('<font name="GuideMono" size="9">' + html.escape(part) + '</font>')
        else:
            parts.append(html.escape(part))
    value = "".join(parts)
    value = re.sub(r'\*\*(.+?)\*\*', r'<b>\1</b>', value)
    return re.sub(r'\[([^]]+)\]\(((?:#|https?://)[^)]+)\)', r'<link href="\2" color="#087F8C">\1</link>', value)

titles = [line for line in source.splitlines() if line.startswith("# ")]
class GuideDoc(SimpleDocTemplate):
    def beforeDocument(self):
        self.section_pages = []

    def afterFlowable(self, flowable):
        if hasattr(flowable, "section_key"):
            self.canv.bookmarkPage(flowable.section_key)
            self.canv.addOutlineEntry(flowable.getPlainText(), flowable.section_key, level=0, closed=False)
            self.section_pages.append((flowable.getPlainText(), self.page))
            if flowable.section_key not in ("start", "contents"):
                self.notify("TOCEntry", (0, html.escape(flowable.getPlainText()), self.page, flowable.section_key))


class UiMap(Flowable):
    """Code-derived layout map, intentionally not presented as an IDEA screenshot."""
    def __init__(self):
        super().__init__()
        self.width = WIDTH
        self.height = 208

    def draw(self):
        canvas = self.canv
        canvas.saveState()
        canvas.setFillColor(PALE)
        canvas.setStrokeColor(RULE)
        canvas.roundRect(0, 0, self.width, self.height, 6, fill=1, stroke=1)
        labels = ["Java REPL", "Variables", "Snapshots", "Inspector", "Tap / Trace", "Cases / Reload",
                  "Debugger", "HTTP", "AI", "Imports", "MCP", "Beans"]
        tab_width = (self.width - 16) / 6
        for index, label in enumerate(labels):
            x, y = 8 + index % 6 * tab_width, self.height - 24 - (index // 6) * 22
            canvas.setFillColor(ACCENT if index == 0 else colors.white)
            canvas.roundRect(x, y, tab_width - 3, 19, 3, fill=1, stroke=0)
            canvas.setFillColor(colors.white if index == 0 else INK)
            canvas.setFont("GuideBold" if index == 0 else "Guide", 7.5)
            canvas.drawCentredString(x + (tab_width - 3) / 2, y + 6, label)

        def box(x, y, width, height, title, detail=""):
            canvas.setFillColor(colors.white)
            canvas.setStrokeColor(RULE)
            canvas.roundRect(x, y, width, height, 3, fill=1, stroke=1)
            canvas.setFillColor(INK)
            canvas.setFont("GuideBold", 8)
            canvas.drawString(x + 8, y + height - 13, title)
            if detail:
                canvas.setFont("Guide", 7.5)
                canvas.setFillColor(MUTED)
                canvas.drawString(x + 8, y + height - 27, detail)

        box(8, 136, self.width - 16, 22, localized("1. Connect, Run, Interrupt, Save; műveletmenük", "1. Connect, Run, Interrupt, Save; action menus"))
        box(8, 110, self.width - 16, 22, localized("2. Alkalmazás, profilok, visszaigazolt futtatási mód", "2. Application, profiles, confirmed execution mode"))
        box(8, 43, 302, 62, localized("3. Java-munkafüzet", "3. Java workbook"), localized("Cellák, helyi eredmények és kódellenőrzés", "Cells, individual results and code checking"))
        box(315, 43, self.width - 323, 62, localized("4. Eredmény", "4. Result"), "Value / Output / Cells / Watches")
        box(8, 8, self.width - 16, 30, localized("5. Transcript és kapcsolati státusz", "5. Transcript and connection status"))
        canvas.restoreState()


class CoverSummary(Flowable):
    def __init__(self):
        super().__init__()
        self.width, self.height = WIDTH, 162

    def draw(self):
        canvas = self.canv
        canvas.saveState()
        card = (self.width - 16) / 3
        for index, (number, label) in enumerate([("12", localized("fő fül, gombmagyarázatokkal", "main tabs, buttons explained")),
                                                 (str(mcp_tool_count), localized("MCP-eszköz Claude számára", "MCP tools for Claude")),
                                                 ("200 MiB", localized("alap DATA snapshotkorlát", "default DATA snapshot limit"))]):
            x = index * (card + 8)
            canvas.setFillColor(PALE)
            canvas.roundRect(x, 67, card, 83, 5, fill=1, stroke=0)
            canvas.setFillColor(ACCENT)
            canvas.setFont("GuideBold", 24)
            canvas.drawString(x + 12, 113, number)
            canvas.setFillColor(INK)
            canvas.setFont("Guide", 8)
            canvas.drawString(x + 12, 89, label)
        canvas.setStrokeColor(RULE)
        canvas.line(12, 33, self.width - 12, 33)
        for index, label in enumerate(localized(["Indítás", "Kísérlet", "Snapshot", "Ellenőrzés"], ["Start", "Experiment", "Snapshot", "Verify"])):
            x = 12 + index * (self.width - 24) / 3
            canvas.setFillColor(ACCENT)
            canvas.circle(x, 33, 4, fill=1, stroke=0)
            canvas.setFillColor(INK)
            canvas.setFont("GuideBold", 9)
            if index == 0:
                canvas.drawString(x, 12, label)
            elif index == 3:
                canvas.drawRightString(x, 12, label)
            else:
                canvas.drawCentredString(x, 12, label)
        canvas.restoreState()

def frame(canvas, doc):
    canvas.saveState()
    canvas.setFillColor(ACCENT)
    canvas.rect(48, A4[1] - 31, 24, 3, fill=1, stroke=0)
    canvas.setFont("GuideBold", 8.5)
    canvas.drawString(80, A4[1] - 31, "SPRING BOOT REPL")
    canvas.setFillColor(MUTED)
    canvas.setFont("Guide", 8.2)
    canvas.drawRightString(A4[0] - 48, A4[1] - 31, localized("FELHASZNÁLÓI KÉZIKÖNYV  /  HU", "USER MANUAL  /  EN"))
    canvas.setStrokeColor(RULE)
    canvas.line(48, 37, A4[0] - 48, 37)
    canvas.drawString(48, 24, localized("Használati útmutató", "User guide") + f"  |  {version}")
    if doc.page > 2:
        canvas.setFillColor(ACCENT)
        canvas.drawCentredString(A4[0] / 2, 24, localized("Tartalomjegyzék", "Contents"))
        canvas.linkRect("", "contents", (A4[0] / 2 - 45, 20, A4[0] / 2 + 45, 34), relative=0, thickness=0)
    canvas.drawRightString(A4[0] - 48, 24, str(doc.page))
    canvas.restoreState()

story = []
lines = source.splitlines()
i = 0
section = 0
while i < len(lines):
    line = lines[i].strip()
    if not line:
        i += 1
        continue
    if line.startswith("# "):
        if section: story.append(PageBreak())
        title = line[2:]
        match = re.search(r' \{#([^}]+)\}$', title)
        key = match[1] if match else "start"
        title = title[:match.start()] if match else title
        paragraph = Paragraph(inline(title), styles["cover"] if key == "start" else styles["title"])
        paragraph.section_key = key
        story.append(paragraph)
        section += 1
    elif line.startswith("## "):
        heading_style = ParagraphStyle("McpHeading", parent=styles["heading"], keepWithNext=False) if key == "mcp-tools" else styles["heading"]
        story.append(Paragraph(inline(line[3:]), heading_style))
    elif line == "{{contents}}":
        toc = TableOfContents()
        toc.levelStyles = [ParagraphStyle("ContentsEntry", fontName="Guide", fontSize=9.8, leading=13,
                                         textColor=INK, leftIndent=0, firstLineIndent=0, rightIndent=24,
                                         spaceBefore=0, spaceAfter=0)]
        toc.tableStyle = TableStyle([("LEFTPADDING", (0, 0), (-1, -1), 0),
                                     ("RIGHTPADDING", (0, 0), (-1, -1), 0),
                                     ("TOPPADDING", (0, 0), (-1, -1), 1),
                                     ("BOTTOMPADDING", (0, 0), (-1, -1), 1)])
        toc.dotsMinLevel = 0
        story.append(toc)
    elif line == "{{ui-map}}":
        story.extend([UiMap(), Spacer(1, 10)])
    elif line == "{{cover-summary}}":
        story.extend([Spacer(1, 30), CoverSummary()])
    elif line.startswith("```"):
        code = []
        i += 1
        while i < len(lines) and not lines[i].strip().startswith("```"):
            code.append(lines[i]); i += 1
        assert all(pdfmetrics.stringWidth(text, "GuideMono", 9.1) <= WIDTH - 24 for text in code), "Code line too wide: " + str(code)
        block = Table([[Preformatted("\n".join(code), styles["code"])]], colWidths=[WIDTH])
        block.setStyle(TableStyle([("BACKGROUND", (0, 0), (-1, -1), PALE), ("BOX", (0, 0), (-1, -1), .5, RULE),
                                   ("LEFTPADDING", (0, 0), (-1, -1), 12), ("RIGHTPADDING", (0, 0), (-1, -1), 12),
                                   ("TOPPADDING", (0, 0), (-1, -1), 9), ("BOTTOMPADDING", (0, 0), (-1, -1), 9)]))
        story.extend([block, Spacer(1, 8)])
    elif line.startswith("|"):
        rows = []
        while i < len(lines) and lines[i].strip().startswith("|"):
            row = [cell.strip() for cell in lines[i].strip().strip("|").split("|")]
            if not all(re.fullmatch(r'[:\- ]+', cell) for cell in row): rows.append(row)
            i += 1
        i -= 1
        assert len(rows[0]) in (2, 3), "Guide tables must have two or three columns"
        assert all(len(row) == len(rows[0]) for row in rows), "Inconsistent table columns"
        ratios = [0.34, 0.66] if len(rows[0]) == 2 else [0.25, 0.29, 0.46]
        table = Table([[Paragraph(inline(cell), styles["headcell" if index == 0 else "cell"]) for cell in row] for index, row in enumerate(rows)],
                      colWidths=[WIDTH * ratio for ratio in ratios], repeatRows=1, hAlign="LEFT")
        padding = {"notebook-state": 5, "limits": 6}.get(key, 7)
        table.setStyle(TableStyle([("BACKGROUND", (0, 0), (-1, 0), ACCENT), ("ROWBACKGROUNDS", (0, 1), (-1, -1), [PALE, colors.white]),
                                  ("VALIGN", (0, 0), (-1, -1), "TOP"), ("LINEBELOW", (0, 0), (-1, 0), .7, ACCENT),
                                  ("LINEBELOW", (0, 1), (-1, -1), .35, RULE),
                                  ("LEFTPADDING", (0, 0), (-1, -1), 8), ("RIGHTPADDING", (0, 0), (-1, -1), 8),
                                  ("TOPPADDING", (0, 0), (-1, -1), padding), ("BOTTOMPADDING", (0, 0), (-1, -1), padding)]))
        story.extend([table, Spacer(1, 8)])
    elif line.startswith("- ") or re.match(r'^\d+\. ', line):
        if line.startswith("- "): line = "• " + line[2:]
        story.append(Paragraph(inline(line), styles["list"]))
    else:
        paragraph = [line]
        while i + 1 < len(lines) and lines[i + 1].strip() and not re.match(r'^(#|\||```|- |\d+\. )', lines[i + 1]):
            i += 1; paragraph.append(lines[i].strip())
        story.append(Paragraph(inline(" ".join(paragraph)), styles["body"]))
    i += 1

args.output.parent.mkdir(parents=True, exist_ok=True)
# SimpleDocTemplate's frame adds 6 pt of horizontal padding; align text/tables with the 48 pt header margin.
document = GuideDoc(str(args.output), pagesize=A4, leftMargin=42, rightMargin=42, topMargin=55, bottomMargin=49,
                    title=f"Spring Boot REPL {version} - " + localized("használati útmutató", "user guide"), author="Spring Boot REPL", subject=localized("Funkciók, gyorsbillentyűk, snapshotok és debugger", "Features, shortcuts, snapshots and debugger"), lang=args.language, pageCompression=1)
document.multiBuild(story, onFirstPage=frame, onLaterPages=frame)
for title, page in document.section_pages: print(f"{page}: {title}")
assert len(document.section_pages) == len(titles), "A guide section was not rendered"
layout_report = ROOT / f"tmp/pdfs/ui-guide/{args.language}/layout.json"
layout_report.parent.mkdir(parents=True, exist_ok=True)
layout_report.write_text(json.dumps({"pages": document.page,
    "sections": [{"title": title, "page": page} for title, page in document.section_pages]}, ensure_ascii=False, indent=2) + "\n")
RESOURCE.parent.mkdir(parents=True, exist_ok=True)
if args.output.resolve() != RESOURCE.resolve():
    shutil.copyfile(args.output, RESOURCE)
RESOURCE.with_suffix(".source.sha256").write_text(hashlib.sha256(source.encode()).hexdigest() + "\n")
print(args.output)
print("Bundled:", RESOURCE)
