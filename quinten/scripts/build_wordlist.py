#!/usr/bin/env python3
"""Builds the English word-frequency list used to repair OCR text (core/analysis resources).

Counts words in public-domain Project Gutenberg books (only the counts are kept; the
Gutenberg header/footer is stripped). Usage: build_wordlist.py <download dir>
"""
import collections, gzip, os, re, sys, time, urllib.request

IDS = [1342, 1661, 345, 2701, 84, 98, 76, 1400, 2600, 11, 174, 5200, 2554, 1260, 768, 4300, 1184, 120,
       36, 35, 1232, 2852, 1497, 1727, 25344, 219, 74, 2591, 1998, 64317, 730, 158, 161, 1023, 3825, 2814]
OUT = os.path.join(os.path.dirname(__file__), '..', 'core', 'analysis', 'src', 'main', 'resources', 'app', 'quinten', 'analysis', 'text', 'en_words.txt.gz')

def main(cache):
    os.makedirs(cache, exist_ok=True)
    counts = collections.Counter()
    for i in IDS:
        path = os.path.join(cache, f'pg{i}.txt')
        if not os.path.exists(path):
            url = f'https://www.gutenberg.org/cache/epub/{i}/pg{i}.txt'
            req = urllib.request.Request(url, headers={'User-Agent': 'QuintenReader wordlist builder'})
            try:
                open(path, 'wb').write(urllib.request.urlopen(req, timeout=120).read())
            except Exception as e:
                print('skip', i, e); continue
            time.sleep(1)
        t = open(path, encoding='utf-8', errors='replace').read()
        s = t.find('*** START'); e = t.find('*** END')
        if s >= 0: t = t[t.index('\n', s) + 1:]
        if e >= 0: t = t[:t.find('*** END')]
        t = t.replace('’', "'")
        for w in re.findall(r"[a-zà-öø-ÿ]+(?:'[a-z]+)?", t.lower()):
            counts[w] += 1
    words = [(w, c) for w, c in counts.items() if c >= 3 or len(w) == 1]
    words.sort(key=lambda x: (-x[1], x[0]))
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with gzip.open(OUT, 'wt', encoding='utf-8', compresslevel=9) as f:
        for w, c in words: f.write(f'{w}\t{c}\n')
    print(len(words), 'words from', sum(counts.values()), 'tokens ->', os.path.normpath(OUT))

if __name__ == '__main__':
    main(sys.argv[1])
