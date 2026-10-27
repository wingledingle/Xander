#!/usr/bin/env python3
"""Generates small test books in every flowing format from one Markdown source.
Requires pandoc and LibreOffice (soffice). Output: core/formats/src/test/resources/fixtures."""
import os, struct, subprocess, sys, tempfile, zlib, shutil

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "core/formats/src/test/resources/fixtures")

def png(w, h, rgb):
    raw = b"".join(b"\x00" + bytes(rgb) * w for _ in range(h))
    def chunk(t, d):
        return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xffffffff)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)) + \
        chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b"")

MD = """---
title: The Test Voyage
author: Ada Example
lang: en
---

# Chapter One

It was a *bright* cold day in April, and the clocks were **striking** thirteen.[^1]

"Is anyone there?" she asked.

![A red square](red.png)

# Chapter Two

The second chapter begins here with a [link to chapter one](#chapter-one).

> A quotation, set apart from the text.

- First item
- Second item

[^1]: This is the footnote text.
"""

def main():
    os.makedirs(OUT, exist_ok=True)
    tmp = tempfile.mkdtemp()
    with open(os.path.join(tmp, "red.png"), "wb") as f:
        f.write(png(8, 6, (255, 0, 0)))
    src = os.path.join(tmp, "book.md")
    with open(src, "w") as f:
        f.write(MD)
    def pandoc(fmt, name, *extra):
        subprocess.check_call(["pandoc", src, "-s", "-o", os.path.join(OUT, name), "--resource-path", tmp, *extra], cwd=tmp)
    pandoc("epub", "sample.epub")
    pandoc("docx", "sample.docx")
    pandoc("rtf", "sample.rtf")
    pandoc("fb2", "sample.fb2")
    # .doc via LibreOffice
    subprocess.check_call(["soffice", "--headless", "--convert-to", "doc", "--outdir", tmp, os.path.join(OUT, "sample.docx")],
                          stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    shutil.copy(os.path.join(tmp, "sample.doc"), os.path.join(OUT, "sample.doc"))
    # LibreOffice-made RTF (different dialect than pandoc's)
    subprocess.check_call(["soffice", "--headless", "--convert-to", "rtf", "--outdir", tmp, os.path.join(OUT, "sample.docx")],
                          stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    shutil.copy(os.path.join(tmp, "sample.rtf"), os.path.join(OUT, "sample-lo.rtf"))
    # CBZ with three pages
    import zipfile
    with zipfile.ZipFile(os.path.join(OUT, "sample.cbz"), "w") as z:
        for i, c in [(10, (255, 0, 0)), (2, (0, 255, 0)), (1, (0, 0, 255))]:
            z.writestr("pages/page%d.png" % i, png(4, 6, c))
    print("fixtures written to", OUT)

if __name__ == "__main__":
    main()
