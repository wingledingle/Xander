#!/bin/sh
# Builds core/analysis/.../en_lexicon.txt.gz (word list used to tell real words from OCR run-ons)
# from SCOWL 2020.12.07 (http://wordlist.aspell.net, licence: app/src/main/assets/licenses/scowl.txt),
# English/American/British/Canadian/Australian words, names, contractions and abbreviations up to size 70.
set -e
d=$(mktemp -d)
curl -sSL -o "$d/scowl.tar.gz" https://downloads.sourceforge.net/wordlist/scowl-2020.12.07.tar.gz
tar xzf "$d/scowl.tar.gz" -C "$d"
cd "$d/scowl-2020.12.07/final"
cat $(ls | grep -E "^(english|american|british|canadian|australian)(_variant_1)?-(words|upper|contractions|abbreviations)\.(10|20|35|40|50|55|60|70)$") |
  iconv -f iso-8859-1 -t utf-8 | tr 'A-Z' 'a-z' | sort -u | grep -v "[^a-z'’-]" | gzip -9 > "$OLDPWD/core/analysis/src/main/resources/app/quinten/analysis/text/en_lexicon.txt.gz"
