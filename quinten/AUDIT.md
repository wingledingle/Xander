# Quinten audit — 2026-10-24

Five read-only code reviews, one per area: formats; reflow, Clean Reading Mode and OCR; reader; library and data; licensing, release and design gaps. Each finding was traced in the code by the reviewer, with file:line. **None of them has been reproduced on a device yet.** Severity: C critical, H high, M medium, L low.

## Fix status (updated as fixes land)

Fixed, with regression tests unless noted:
- #1 lines lost or repeated at section edges (`PagedDocumentTest`: every opening order, chunk sizes 1 to 3).
- #2–#3 Clean mode no longer deletes mid-paragraph "watermark words", play speaker names, first-line dialogue or repeated table headers (`CleanModeTest`). Headers on real scanned and LaTeX books are still removed (checked with `RealPdfProbe`).
- #4–#5 chart labels aren't duplicated; short scanned pages reflow; scan backgrounds are dropped (`PagedDocumentTest`).
- #6–#8 reader keeps its place on Clean-mode/preview toggles and screen recreation; note links apply once; failed sections show their error instead of endless "Loading…". Input is ignored while a jump loads, and the last page turn is saved on exit (render/app tests pass; the recreation and Clean-toggle paths aren't covered by a dedicated test).
- Text pipeline mediums:
  - Number-heavy pages keep their text layer.
  - The re-OCR verdict is saved per book.
  - The page cache is versioned: text layers are redone after updates, OCR is kept per language.
  - One thread per page; unique temp files.
  - Words are learned once per page, and the garbled check isn't fooled by repeated junk.
  - Small-type quotations aren't turned into footnotes.
  - FlowCleaner keeps centred/bold section numbers and speaker names.
  - Ligatures are split into letters, and removals are listed in page order.
  - OCR engine leaks and races are fixed; a native-library failure now disables OCR instead of breaking the book.
- Reader mediums and lows:
  - Highlights redraw at once and include a paragraph's last character.
  - Search cancels cleanly and shows progress.
  - No page turn after a pinch; the zoom bitmap is freed.
  - Indents are capped at huge sizes; bookmarks work in scroll mode.
- TTS:
  - Audio focus, headphone unplug, MediaSession buttons and the Android 13 notification permission.
  - Works with several windows, follows along in scroll mode, uses the book's language.
  - **Compile-checked only; not tested on a device or by a unit test.**

- Library/data (#9–#11 and mediums), with 12 new tests in `LibraryRepositoryFixesTest`:
  - "Open with" keeps a lasting location (a permission check, then a real path, then an app copy); lost permission gets a clear message.
  - No numeric titles; targeted DB updates, so progress is never overwritten.
  - Missing books recover; no address flip-flopping.
  - Library work runs off the main thread with batched writes; metadata is read 3 at a time.
  - Collection names are validated; a corrupt settings file is replaced.
  - A scan requested mid-scan runs next; pipe-backed files work.
  - Removed books come back when opened.
  - Notes grouped per book, with Markdown export including bookmarks and an "export all".
  - OCR language import off the main thread; covers refresh.
  - Removing a folder releases its permission; "forget book" cleans caches; opened copies are capped at 500 MB.
- Formats (#12–#14 and mediums):
  - DjVu: page memory bounded; JB2 colours mapped while decoding; zoom sharp.
  - TXT: decoded once; CJK and Central European encodings.
  - CSS @media and combinators; unclosed void tags keep their text.
  - Huge EPUB files split; non-UTF-8 zip names.
  - FB2: BOM and encoding guessing.
  - RAR: windows sized to the data, LRU for solid archives, filename fixes.
  - MOBI/AZW3: read on demand; AZW3 detected by header; clear KFX/Topaz message.
  - DOC: fast, real table rows, cycle-safe.
  - DOCX: moves, text boxes, list numbers.
  - RTF: field and `\bin` fixes.
  - Zero-width spaces dropped; .avif comics.
  - Also found and fixed: **DjVu colour palettes were painted with the wrong colours** (index count read from the wrong place), checked against DjVuLibre.
- Release/licensing:
  - WorkManager and WAKE_LOCK removed; backups off (offline app); unused Bouncy Castle data excluded (APK 62 → 58 MB).
  - Accurate THIRD_PARTY_NOTICES, with full licence texts in the app's Licences screen.

Full suite: 152 tests, 0 failures (6 manual probes skipped). Nothing above has been run on a device yet.

- Regression round (after user report): read-aloud stutter (focus loop) and wrong-language voice; PDF characters broken by over-eager glyph splitting; sideways chart labels in sentences; OCR-misread roman page numbers; header detection on freshly opened sections; centred page numbers in converted e-books; over-eager OCR re-reading. Verified by comparing the previous build with the new one on 6 EPUBs, Kindle/TXT copies and 3 real PDFs (desktop and Android PDF paths).
- Added since: password prompt for protected PDFs (remembered per book), reading brightness, read-aloud sleep timer, auto-scroll, backup/restore of reading data (works across phones), TalkBack page turning, "Look up" in an installed dictionary app.

Needs a decision from the owner: the RAR decoder's provenance (see Licensing).

## Fix first (the most likely causes of what users see)

| # | Sev | Problem | Where |
|---|---|---|---|
| 1 | C | **Scanned and PDF books lose or duplicate lines every 3 pages.** A paragraph that crosses a section boundary goes to one section only if its *first* line is there, and the neighbouring page is read only when it happens to be cached. The top lines of a section vanish (or appear twice) depending on reading order. Probably a large part of the "garbled text". | `PagedBookDocument.kt` loadChapter, `PageReconstructor.kt` include() |
| 2 | H | **Clean mode deletes real sentences.** Any line containing "confidential", "watermark", "do not copy", "downloaded from" or "scanned by" counts as a watermark, even mid-paragraph. Repeated small lines (table headers, captions) count as watermarks too. | `PageCleaner.kt` watermark rules |
| 3 | H | **Clean mode deletes play speaker names and short dialogue** that open two pages ("HAMLET.", "“Yes.”"): one matching page is enough when few pages are loaded. | `PageCleaner.kt` running headers |
| 4 | H | **Chart and table text appears twice:** once in the picture and once as junk lines. | `PdfPageSource.kt` vector figures |
| 5 | H | **Short scanned pages** (chapter ends, poems, title pages) are shown as fixed pictures instead of reflowed text, which breaks large print. | `PdfPageSource.kt:146`, `isPicturePage` |
| 6 | H | **Reading position jumps when Clean mode or preview is toggled** (block numbers change under the saved position). | `ReaderController.clean`, `ReaderViewModel` |
| 7 | H | **Position resets to the start of the book** after changing the phone's font size, display size or language (activity recreated; the new view starts at 0 and saves it). | `ReaderScreen` factory, manifest configChanges |
| 8 | H | **A section that fails to load leaves the book stuck on "Loading…" forever.** | `ReaderView.tryNavigate` |
| 9 | H | **"Open with" on a book already in the library** replaces its file path with a temporary address, so it won't open after a restart. | `LibraryRepository.addBook/register` |
| 10 | H | **Books from picked folders or "Open with" get numbers as titles** (e.g. "57"), from the temporary file-descriptor name. | parsers' title fallback, `BookFiles.open` |
| 11 | H | **Metadata loading can overwrite reading progress** saved meanwhile (it writes back a stale copy of the whole row). | `LibraryRepository.loadMetadata` |
| 12 | H | **Memory crashes on big files:** DjVu keeps every page it has ever loaded in memory; TXT, MOBI and AZW3 load the whole file several times over; RAR5 allocates its full dictionary per page; zoomed DjVu needs over 100 MB per page. | `DjvuDocument`, `Jb2`, `TxtParser`, `MobiParser`, `RarArchive` |
| 13 | H | **EPUB/AZW3 text disappears** with Kindle-style `@media` CSS (both versions hidden) or `.x span{display:none}` (hides every span). | `MiniCss.kt:255,263` |
| 14 | H | **Unclosed `<br>`/`<img>` in XHTML EPUBs drop the rest of the paragraph.** | `HtmlConverter.kt` void tags |

## Text pipeline (reflow, clean, OCR)
- M: Number-heavy pages (tables, indexes, maths) are treated as broken, and their exact text is replaced by slower, worse OCR (`looksBroken`).
- M: Non-English PDFs re-run OCR on about 3 pages at every open; one lucky win then means OCR on every page (the verdict isn't saved per book).
- M: Cached page text is never invalidated when the algorithms or the OCR language change (`PageTextCodec` VERSION=1).
- M: Threads can OCR the same page twice and both write the same temp file. Book words are counted again on every reload, which weakens the new OCR repair.
- M: Margin line numbers and marginalia get merged into the text. Small-type passages low on a page become "footnotes".
- M: FlowCleaner (EPUB/DOCX) removes standalone section numbers ("1", "2") and repeated pseudo-headings.
- L: Ligatures (ﬁ) aren't normalised for search and TTS. Rotated margin text lands in the body. Removal list order is "p:100" before "p:2". There are small resource leaks in the OCR/PDF code.
- Gaps: no password prompt for PDFs; no right-to-left or vertical text; no orientation detection for sideways scans; OCR footnotes are never linked; positions and restored removals shift when the text source changes (OCR mode, language, rescue).

## Reader
- M: Input while a jump is still loading overwrites the saved position. The last page turn before Back can be lost (600 ms debounce cancelled).
- M: An old search can't be cancelled and overwrites newer results; search parses (and OCRs) the whole book.
- M: New highlights don't appear until the next redraw.
- M: TTS has no audio focus (talks over calls and music), no pause on headphone unplug, no MediaSession, and doesn't request notification permission on Android 13+. It breaks with two windows, and doesn't follow along in scroll mode.
- M: After a pinch, the remaining finger can trigger a page turn or a big scroll.
- M: Main-thread jank: image sizes are read during layout, and every TTS sentence or selection step re-lays out the whole paragraph.
- L: A highlight misses the final character of a paragraph. Bookmarks can duplicate in scroll mode. The zoomed bitmap isn't released. Indents become huge at 96 sp. TTS language is never set.
- Gaps: TalkBack can't turn pages. Pinch zoom on reflowed text scrolls sideways (ReadEra changes the font size instead). Selection can't cross pages and has no handles. No RTL tap zones. Pages reached by going backwards are under-filled.

## Library and data
- OK: database version 1 matches the entities with no destructive migration; Room is off the main thread; storage permissions are right on every Android version.
- M: A missing picked-folder book is never marked present again. A file reachable two ways switches address on every scan.
- M: Library UI janks during big scans (full re-sort on the main thread for every update). Metadata loading is one book at a time and fully parses each book.
- M: Renaming a collection to an existing name crashes the app. A corrupt settings file crashes every launch.
- L/M: Scan requests during a running scan are dropped. Pipe-backed content providers all get the same fingerprint. Re-added removed books stay hidden. Notes screen headers are wrong. OCR language import runs on the main thread. "Reload cover" shows the old cover.
- Gaps: full storage walk on every launch (no incremental scan); slow DocumentFile walk; removing a folder doesn't drop its permission or books; caches (covers, OCR, opened copies) never shrink; reading sessions lost when the process is killed; no backup or export of all reading data.

## Formats
- M: Zip files with non-UTF-8 names (old Windows tools, Japanese CBZ) fail to open. Single huge EPUB files aren't split into sections. Solid CBR is slow paging backwards. DOC is O(chars × pieces), DOC tables collapse into one block, and a corrupt DOC can OOM. FB2 ignores its BOM and guesses no codepage. DOCX loses moved text, text boxes and drawings.
- M: TXT encoding guessing has no Chinese, Japanese or Central European codepages.
- L: zero-width space becomes a visible space (Thai/CJK); RAR4 Unicode filenames decode wrongly; RTF fields make empty links and `\bin` isn't handled; FB2 notes lookup is quadratic; OCR repair in EPUBs can vary slightly with chapter load order.
- Gaps: DjVu JPEG (photo) layers render blank; no SVG; no CSS text-indent, RTL, lang or ruby; DOCX numbered lists show as bullets; tables are flattened; no CJK chapter detection in TXT; RAR older than 2.9 isn't supported; KFX/Topaz show a misleading error; no .avif/.jxl/.tif in comics; ComicInfo page order is ignored.

## Licensing and release
- **C: the RAR decoder's origin.** Its structure and names follow RARLAB's UnRAR source, whose licence is neither Apache nor MIT, and RAR compression has no public specification. Needs a decision: verify it as clean-room, or replace or limit CBR support (no Apache/MIT RAR decoder exists).
- **H: "Apache/MIT only" isn't literally true for the APK.** Tesseract4Android bundles Leptonica (BSD-2), libjpeg-turbo (IJG/BSD-3/zlib) and libpng. PdfBox-Android bundles Bouncy Castle (MIT-style), Liberation Sans (OFL), Adobe AFM/glyph-list data and Unicode data. The wallpapers are CC0. HANDOFF and THIRD_PARTY_NOTICES overclaim this.
- M: the in-app licence screen lacks the full notice texts these licences require.
- H: release builds are signed with the debug key; the shipped APK is a debug build.
- H: Google Play restricts MANAGE_EXTERNAL_STORAGE (fine for sideloading).
- M: Android auto-backup is on with no rules, so the library DB uploads to Google Drive (against "offline"). No ABI splits, about 15 MB of x86 libraries, about 8 MB of unused Bouncy Castle data. The release (R8) build hasn't been re-verified since Oct 16.
- L: unused WorkManager dependency (adds permissions and receivers); unused WAKE_LOCK; overly broad "Open with" filters; undocumented icon origin.
- OK: no INTERNET permission and no network code; fonts are Apache; djvu-rs is MIT; eng.traineddata is tessdata_fast (Apache).

## Requirement status
| Requirement | Status |
|---|---|
| Library, discovery, collections | Done (tested); MediaStore/SD scan untested |
| Bookmarks, highlights, notes, search, progress, history, TOC | Done (Robolectric), with the bugs above |
| Night mode, themes | Done |
| TTS | Partial: never tested on device, missing audio focus and controls |
| Typography, large print, no horizontal scroll | Done; pinch zoom breaks "no horizontal scroll" |
| EPUB, FB2, TXT, RTF, DOC, DOCX, CBZ | Done (tested) |
| MOBI/AZW3 | Done (optional external tests only) |
| CBR | Partial (decoder tested; licence question) |
| PDF | Partial (no password prompt; PdfRenderer untested on device) |
| DjVu | Done for bundled files; photo layers missing |
| OCR | Partial (pipeline tested with a fake engine; not run on device; not applied to images inside EPUB/DOCX) |
| Clean Reading Mode | Done and reversible, but deletes some real content (above) |
| Large-book efficiency | Not verified on device; several OOM risks (above) |
| Apache/MIT only | Not met as stated (above) |

Missing compared with ReadEra (now): two-page landscape, reading statistics, per-book typography, right-to-left text. Done since the audit: brightness, dictionary lookup (via installed apps), auto-scroll, TTS sleep timer, backup/import of reading data, PDF password prompt, TalkBack paging.
