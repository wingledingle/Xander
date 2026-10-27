"""Transcribes KokoroProbe wav files with Whisper and reports word error rate against the text."""
import sys, re, difflib
import numpy as np, soundfile as sf
from faster_whisper import WhisperModel
outdir, wdir = sys.argv[1], sys.argv[2]
m = WhisperModel("base.en", device="cpu", compute_type="int8", download_root=wdir)
ones = "zero one two three four five six seven eight nine".split()
# Spelling that is not speech: British/American, compounds, apostrophes.
same = {"color": "colour", "colors": "colours", "favor": "favour", "honor": "honour", "gray": "grey", "realize": "realise"}
def norm(s):
    s = s.lower().replace("’", "'").replace("‘", "'")
    s = re.sub(r"(?<=[a-z])-(?=[a-z])", "", s)  # to-day = today, red-headed = redheaded
    words = [w.strip("'") for w in re.findall(r"[a-z0-9']+", s)]
    words = [same.get(w, w) for w in words if w]
    # Joined or split compounds ("any more" / "anymore") count as the same.
    return list("".join(words)) if False else words
def joined(ws): return "".join(ws)
tot = err = 0; rows = []
for line in open(f"{outdir}/log.tsv", encoding="utf-8"):
    f = line.rstrip("\n").split("\t")
    a, sr = sf.read(f"{outdir}/{f[0]}.wav", dtype="float32")
    a16 = np.interp(np.arange(0, len(a), sr / 16000), np.arange(len(a)), a).astype(np.float32)
    segs, _ = m.transcribe(a16, language="en", beam_size=5)
    hyp = " ".join(s.text for s in segs)
    r, h = norm(f[5]), norm(hyp)
    sm = difflib.SequenceMatcher(None, r, h, autojunk=False)
    e = sum(0 if joined(r[i1:i2]) == joined(h[j1:j2]) else max(i2 - i1, j2 - j1) for t, i1, i2, j1, j2 in sm.get_opcodes() if t != "equal")
    tot += len(r); err += e
    rows.append((e / max(1, len(r)), f[5], hyp, [(" ".join(r[i1:i2]), " ".join(h[j1:j2])) for t, i1, i2, j1, j2 in sm.get_opcodes() if t != "equal"]))
print(f"words {tot}  errors {err}  WER {100*err/tot:.2f}%")
for w, ref, hyp, d in sorted(rows, reverse=True)[:int(sys.argv[3]) if len(sys.argv) > 3 else 8]:
    print(f"{w:.2f} | {ref}\n     | {hyp.strip()}\n     | {d}")
