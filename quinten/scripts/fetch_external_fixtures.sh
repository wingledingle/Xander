#!/bin/sh
# Downloads third-party sample files used by optional tests. They are not committed:
# Project Gutenberg public-domain books (MOBI/AZW3/EPUB) and libarchive's RAR test vectors.
set -e
DIR="$(cd "$(dirname "$0")/.." && pwd)/core/formats/build/external-fixtures"
mkdir -p "$DIR"
cd "$DIR"
fetch() { [ -s "$2" ] || curl -sSfL -o "$2" "$1"; }
fetch https://www.gutenberg.org/ebooks/11.kf8.images alice.azw3
fetch https://www.gutenberg.org/ebooks/11.kindle.images alice.mobi
fetch https://www.gutenberg.org/ebooks/11.epub3.images alice.epub
fetch https://www.gutenberg.org/cache/epub/11/pg11.txt alice.txt
LA=https://raw.githubusercontent.com/libarchive/libarchive/master/libarchive/test
for f in test_read_format_rar.rar test_read_format_rar_compress_normal.rar test_read_format_rar_compress_best.rar \
         test_read_format_rar5_stored.rar test_read_format_rar5_compressed.rar test_read_format_rar5_multiple_files.rar \
         test_read_format_rar5_solid.rar test_read_format_rar_multi_lzss_blocks.rar test_read_format_rar_filter.rar \
         test_read_format_rar5_multiple_files_solid.rar; do
  if [ ! -s "$f" ]; then
    curl -sSfL -o "$f.uu" "$LA/$f.uu"
    python3 -c "import binascii,sys
lines=open(sys.argv[1]).read().splitlines()
out=bytearray()
started=False
for l in lines:
    if l.startswith('begin '): started=True; continue
    if l.strip()=='end': break
    if started and l: out+=binascii.a2b_uu(l)
open(sys.argv[2],'wb').write(out)" "$f.uu" "$f"
    rm -f "$f.uu"
  fi
done
ls -la "$DIR"
