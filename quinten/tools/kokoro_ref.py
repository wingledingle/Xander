"""Python reference: same token ids through onnxruntime; compares with the Kotlin wav files."""
import sys, numpy as np, onnxruntime as ort, soundfile as sf, time
model, voices, outdir = sys.argv[1], sys.argv[2], sys.argv[3]
s = ort.InferenceSession(model)
v = np.fromfile(f"{voices}/af_heart.bin", dtype=np.float32).reshape(-1, 1, 256)
worst = 0; tt = 0
for n, line in enumerate(open(f"{outdir}/log.tsv", encoding="utf-8")):
    f = line.rstrip("\n").split("\t")
    ids = [int(x) for x in f[4].split(",")]
    if len(ids) > 510: continue
    t0 = time.time()
    a = s.run(None, {"input_ids": np.array([[0, *ids, 0]], dtype=np.int64), "style": v[len(ids)], "speed": np.ones(1, dtype=np.float32)})[0][0]
    tt += time.time() - t0
    k, _ = sf.read(f"{outdir}/{f[0]}.wav", dtype="float32")
    m = min(len(a), len(k))
    d = np.abs(np.clip(a[:m], -1, 1) - k[:m]).max() if m else 1
    worst = max(worst, d)
    if n < 3 or d > 0.01: print(f[0], "len py", len(a), "kt", len(k), "max diff", round(float(d), 5))
print("worst max diff", worst, "python synth s", round(tt, 1))
