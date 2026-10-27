#!/usr/bin/env python3
"""Builds core/speech/.../us_lexicon.tsv.gz from misaki's us_gold.json and us_silver.json
(https://github.com/hexgrad/misaki, Apache-2.0), the pronunciations Kokoro was trained with.
Lines: word<TAB>phonemes, or word<TAB>DEFAULT=p;NOUN=p;VERB=p... for words read by part of speech.
Gold entries win over silver ones. Usage: build_kokoro_lexicon.py us_gold.json us_silver.json out.tsv.gz"""
import gzip, json, sys

gold = json.load(open(sys.argv[1], encoding='utf-8'))
silver = json.load(open(sys.argv[2], encoding='utf-8'))
merged = dict(silver)
merged.update(gold)
with gzip.open(sys.argv[3], 'wt', encoding='utf-8', compresslevel=9) as out:
    for k in sorted(merged):
        v = merged[k]
        if '\t' in k or '\n' in k:
            continue
        if isinstance(v, str):
            out.write(f'{k}\t{v}\n')
        else:
            parts = [f'{t}={p}' for t, p in v.items() if p is not None]
            out.write(f'{k}\t' + ';'.join(parts) + '\n')
