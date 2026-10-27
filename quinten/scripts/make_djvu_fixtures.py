#!/usr/bin/env python3
"""Builds a multi-page DjVu test book from the PDF fixture using DjVuLibre tools
(cjb2, c44, djvused, djvm, ddjvu) which are only needed to *create* fixtures.
Pages 1-3: bitonal JB2 with a hidden text layer; page 4: IW44 colour photo-like page.
Also writes reference renders (PGM) made by ddjvu for decoder comparison."""
import os, re, subprocess, tempfile, html

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PDF = os.path.join(ROOT, "core/analysis/src/test/resources/fixtures/indent.pdf")
OUT = os.path.join(ROOT, "core/formats/src/test/resources/fixtures")
DPI = 150

def run(*a, **k):
    subprocess.check_call(list(a), stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, **k)

def text_layer(page, w, h, tmp):
    """Builds a djvused text expression with line and word zones from pdftotext -bbox-layout."""
    xml = os.path.join(tmp, "bbox.html")
    run("pdftotext", "-f", str(page), "-l", str(page), "-bbox-layout", PDF, xml)
    s = open(xml, encoding="utf-8").read()
    pm = re.search(r'<page width="([\d.]+)" height="([\d.]+)"', s)
    sx = w / float(pm.group(1)); sy = h / float(pm.group(2))
    def box(m):
        x0, y0, x1, y1 = (float(m.group(i)) for i in range(1, 5))
        return int(x0 * sx), int(h - y1 * sy), int(x1 * sx), int(h - y0 * sy)
    out = ["(page 0 0 %d %d" % (w, h)]
    for lm in re.finditer(r'<line xMin="([\d.]+)" yMin="([\d.]+)" xMax="([\d.]+)" yMax="([\d.]+)">(.*?)</line>', s, re.S):
        lb = box(lm)
        words = []
        for wm in re.finditer(r'<word xMin="([\d.]+)" yMin="([\d.]+)" xMax="([\d.]+)" yMax="([\d.]+)">(.*?)</word>', lm.group(5), re.S):
            wb = box(wm)
            t = html.unescape(wm.group(5)).replace("\\", "\\\\").replace('"', '\\"')
            words.append(' (word %d %d %d %d "%s")' % (wb[0], wb[1], wb[2], wb[3], t))
        out.append(" (line %d %d %d %d%s)" % (lb + ("".join(words),)))
    out.append(")")
    return "\n".join(out)

def main():
    tmp = tempfile.mkdtemp()
    pages = []
    for p in (1, 2, 3):
        base = os.path.join(tmp, "p%d" % p)
        run("pdftoppm", "-f", str(p), "-l", str(p), "-r", str(DPI), "-mono", "-singlefile", PDF, base)
        run("cjb2", "-dpi", str(DPI), base + ".pbm", base + ".djvu")
        hdr = open(base + ".pbm", "rb").read(64).split()
        w, h = int(hdr[1]), int(hdr[2])
        with open(base + ".txt", "w", encoding="utf-8") as f:
            f.write(text_layer(p, w, h, tmp))
        run("djvused", base + ".djvu", "-e", "select 1; set-txt %s" % (base + ".txt"), "-s")
        pages.append(base + ".djvu")
    # Colour page (IW44 only, no text layer)
    base = os.path.join(tmp, "p4")
    run("pdftoppm", "-f", "4", "-l", "4", "-r", "60", "-singlefile", PDF, base)
    run("c44", "-dpi", "60", base + ".ppm", base + ".djvu")
    pages.append(base + ".djvu")
    out = os.path.join(OUT, "sample.djvu")
    run("djvm", "-c", out, *pages)
    outline = os.path.join(tmp, "outline.txt")
    with open(outline, "w") as f:
        f.write('(bookmarks ("Chapter One" "#1") ("Photo page" "#4"))')
    run("djvused", out, "-e", "set-outline %s" % outline, "-s")
    # Reference renders of page 1 (bitonal) and page 4 (colour), scaled to 400 px width.
    for p in (1, 4):
        run("ddjvu", "-format=pgm", "-page=%d" % p, "-size=400x2000", out, os.path.join(OUT, "sample-djvu-p%d.pgm" % p))
    print("ok", out)

main()
