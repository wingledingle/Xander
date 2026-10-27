#!/bin/sh
# Makes kokoro-voices.zip for "Import zip" in the read-aloud panel (phones without internet):
# the Kokoro-82M ONNX model and the English voices, from the same pinned revision the app downloads.
set -e
REV=1939ad2a8e416c0acfeecc08a694d14ef25f2231
B=https://huggingface.co/onnx-community/Kokoro-82M-v1.0-ONNX/resolve/$REV
d=$(mktemp -d); mkdir -p "$d/voices"
curl -sSL -o "$d/model_quantized.onnx" "$B/onnx/model_quantized.onnx"
echo "fbae9257e1e05ffc727e951ef9b9c98418e6d79f1c9b6b13bd59f5c9028a1478  $d/model_quantized.onnx" | sha256sum -c
for v in af_heart af_bella af_nicole af_sarah af_sky af_alloy af_aoede af_jessica af_kore af_nova af_river \
         am_michael am_fenrir am_puck am_adam am_echo am_eric am_liam am_onyx \
         bf_emma bf_isabella bf_alice bf_lily bm_george bm_fable bm_daniel bm_lewis; do
  curl -sSL -o "$d/voices/$v.bin" "$B/voices/$v.bin"
done
(cd "$d" && zip -q -r kokoro-voices.zip model_quantized.onnx voices)
mv "$d/kokoro-voices.zip" . && rm -rf "$d" && ls -la kokoro-voices.zip
