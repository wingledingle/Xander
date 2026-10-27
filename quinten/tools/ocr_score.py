"""Scores reflowed text from a scan against the real book text (Project Gutenberg).
usage: score.py <output.txt> <gutenberg.txt> [--show N]"""
import re, sys, difflib, collections
out_path, gt_path = sys.argv[1], sys.argv[2]
show = int(sys.argv[sys.argv.index('--show') + 1]) if '--show' in sys.argv else 25

def norm(t):
    t = t.replace('’', "'").replace('‘', "'").replace('“', '"').replace('”', '"').replace('—', ' - ').replace('–', ' - ')
    t = t.replace('­', '')
    return t

def words(t):
    return re.findall(r"[A-Za-z0-9]+(?:'[A-Za-z]+)?|[^\sA-Za-z0-9]", norm(t))

gt = open(gt_path, encoding='utf-8', errors='replace').read()
s = gt.find('*** START'); e = gt.find('*** END')
gt = gt[gt.index('\n', s) + 1:e] if s >= 0 else gt
out = open(out_path, encoding='utf-8').read()
ow = words(out); gw = words(gt)
ol = [w.lower() for w in ow]; gl = [w.lower() for w in gw]
alpha = lambda w: re.match(r'[a-z0-9]', w) is not None
# Locate the region of the book the output covers: anchor on 6-word runs from the start and end.
def find(seq, start_hint=0):
    k = 6
    idx = {}
    for i in range(len(gl) - k):
        idx.setdefault(tuple(gl[i:i+k]), i)
    for i in range(0, len(ol) - k):
        j = idx.get(tuple(ol[i:i+k]))
        if j is not None: return i, j
    return None
idx = {}
for i in range(len(gl) - 6): idx.setdefault(tuple(gl[i:i+6]), i)
first = next(((i, idx[tuple(ol[i:i+6])]) for i in range(len(ol) - 6) if tuple(ol[i:i+6]) in idx), None)
last = next(((i, idx[tuple(ol[i:i+6])]) for i in range(len(ol) - 7, 0, -1) if tuple(ol[i:i+6]) in idx), None)
if not first or not last: print("could not align"); sys.exit(1)
o0, g0 = first; o1, g1 = last[0] + 6, last[1] + 6
O = [w for w in ow[o0:o1]]; G = [w for w in gw[g0:g1]]
Oa = [w.lower() for w in O if alpha(w.lower())]; Ga = [w.lower() for w in G if alpha(w.lower())]
sm = difflib.SequenceMatcher(None, Ga, Oa, autojunk=False)
ins, sub, dele = [], [], []
for tag, i1, i2, j1, j2 in sm.get_opcodes():
    if tag == 'insert': ins += Oa[j1:j2]
    elif tag == 'delete': dele += Ga[i1:i2]
    elif tag == 'replace': sub += list(zip(Ga[i1:i2], Oa[j1:j2])) + [(g, '') for g in Ga[i1+ (j2-j1):i2]] + [('', o) for o in Oa[j1 + (i2-i1):j2]]
errors = len(ins) + len(dele) + len(sub)
isnum = lambda w: re.fullmatch(r'\d+|[ivxlcdm]+', w) is not None and w not in ('i', 'mix', 'mild', 'dim', 'lid', 'civil', 'id', 'mid', 'vi', 'di')
digitword = lambda w: re.search(r'[a-z]', w) is not None and re.search(r'\d', w) is not None
inserted_numbers = [w for w in ins if isnum(w)] + [o for g, o in sub if o and isnum(o) and not isnum(g)]
digit_words = [w for w in Oa if digitword(w)]
gt_sym = collections.Counter(w for w in G if not alpha(w.lower()))
out_sym = collections.Counter(w for w in O if not alpha(w.lower()))
junk = {c: n - gt_sym.get(c, 0) for c, n in out_sym.items() if n - gt_sym.get(c, 0) > 0 and c not in '.,;:!?"\'-()'}
print(f"words: real {len(Ga)}  output {len(Oa)}  word error rate {100*errors/max(1,len(Ga)):.2f}%  (inserted {len(ins)}, missing {len(dele)}, wrong {len(sub)})")
print(f"page numbers / stray numbers left in: {len(inserted_numbers)}  {collections.Counter(inserted_numbers).most_common(12)}")
print(f"words with digits in them: {len(digit_words)}  {collections.Counter(digit_words).most_common(12)}")
print(f"junk symbols not in the real text: {sum(junk.values())}  {sorted(junk.items(), key=lambda x:-x[1])[:12]}")
print("sample wrong words (real -> output):", [x for x in sub if x[0] and x[1]][:show])
print("sample inserted:", collections.Counter(ins).most_common(show))
print("sample missing:", collections.Counter(dele).most_common(15))
