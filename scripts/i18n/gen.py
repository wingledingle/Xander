"""Builds res/values-<lang>/strings.xml from scripts/i18n/<lang>.txt (key=text, one per line).

Checks that every translation keeps the English placeholders (%s, %1$d, %%, {name}) and escapes
apostrophes, quotes and XML characters. Missing keys are reported and fall back to English at runtime.
"""
import re, sys, pathlib

root = pathlib.Path(__file__).resolve().parents[2]
res = root / "app/src/main/res"
en_xml = (res / "values/strings.xml").read_text(encoding="utf-8")
en = {m.group(1): m.group(3) for m in re.finditer(r'<string name="([^"]+)"([^>]*)>(.*?)</string>', en_xml, re.S) if 'translatable="false"' not in m.group(2)}
ph = re.compile(r'%(\d+\$)?[sd%]|\{[a-z]+\}')

def unescape_en(v):
    return v.replace("\\'", "'").replace('\\"', '"').replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("\\n", "\n")

def esc(v):
    v = v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    v = v.replace("\\", "\\\\").replace("'", "\\'").replace('"', '\\"').replace("\n", "\\n")
    if v.startswith("@") or v.startswith("?"): v = "\\" + v
    return v

def build(lang):
    rows = {}
    for n, line in enumerate((root / f"scripts/i18n/{lang}.txt").read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip() or line.startswith("#"): continue
        if "=" not in line: sys.exit(f"{lang}.txt:{n}: missing =")
        k, v = line.split("=", 1)
        rows[k.strip()] = v.replace("\\n", "\n")
    problems = []
    for k, v in rows.items():
        if k.startswith("plural:"): continue
        if k not in en: problems.append(f"unknown key {k}"); continue
        if sorted(ph.findall(unescape_en(en[k]))) != sorted(ph.findall(v)): problems.append(f"placeholder mismatch in {k}: {v!r}")
    missing = [k for k in en if k not in rows and k != "app_name"]
    out = ['<?xml version="1.0" encoding="utf-8"?>', "<resources>"]
    for k in en:
        if k in rows: out.append(f'    <string name="{k}">{esc(rows[k])}</string>')
    plurals = {}
    for k, v in rows.items():
        if k.startswith("plural:"):
            _, name, qty = k.split(":"); plurals.setdefault(name, []).append((qty, v))
    for name, items in plurals.items():
        out.append(f'    <plurals name="{name}">')
        for qty, v in items: out.append(f'        <item quantity="{qty}">{esc(v)}</item>')
        out.append("    </plurals>")
    out.append("</resources>\n")
    d = res / f"values-{lang}"; d.mkdir(exist_ok=True)
    (d / "strings.xml").write_text("\n".join(out), encoding="utf-8")
    print(f"{lang}: {len(rows)} strings, {len(missing)} missing, {len(problems)} problems")
    for p in problems: print("  ", p)
    for m in missing: print("   missing", m)
    return not problems

ok = all(build(l) for l in (sys.argv[1:] or [p.stem for p in (root / "scripts/i18n").glob("*.txt")]))
sys.exit(0 if ok else 1)
