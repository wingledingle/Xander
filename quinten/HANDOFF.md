# Dingo Reader — Handoff

Offline Android e-reader with universal text reflow, OCR and Clean Reading Mode.
Project code and direct dependencies are Apache-2.0 / MIT; some bundled third-party pieces carry
other permissive licences, and the RAR decoder's provenance is under review (see "Licensing").

_"Done" means implemented **and** covered by passing automated tests. Items verified only by
compilation are listed separately. No physical device or emulator was available in the build
environment (no KVM), so Android behaviour was tested with Robolectric (real text layout via
native graphics mode), not on hardware._

## Build & test

```
export ANDROID_HOME=/opt/android-sdk                # platform 35, build-tools 35
./gradlew test                                     # JVM tests (core modules)
./gradlew testDebugUnitTest                        # Robolectric tests (android modules + app)
./gradlew :app:assembleDebug                       # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease                     # R8-minified (signed with the debug key; replace for distribution)
python3 scripts/make_fixtures.py                   # flowing-format fixtures (pandoc + LibreOffice)
python3 scripts/make_pdf_fixtures.py               # PDF fixtures (python-docx + LibreOffice)
python3 scripts/make_djvu_fixtures.py              # DjVu fixtures (DjVuLibre tools, test-only)
sh scripts/fetch_external_fixtures.sh              # optional: Gutenberg MOBI/AZW3/EPUB/TXT + libarchive RAR vectors
```
Robolectric downloads its android-all jar through the Maven Central mirror configured in each
module's `build.gradle.kts`.

## Architecture

| Module | Kind | Contents |
|---|---|---|
| `core:model` | JVM | Block/span document model, stable `Locator`s (chapter, unit, offset), `CleanOptions`, `ContentBuilder` |
| `core:analysis` | JVM | `FlowCleaner`; page layout analysis (`PageLayout` XY-cut columns, `PageReconstructor` paragraphs/dehyphenation/headings/footnotes/figures, `PageCleaner`); `PagedBookDocument` (chunked reflow of fixed-layout sources, OCR fallback + on-disk cache, illustration detection); `Sentences` |
| `core:formats` | JVM | EPUB 2/3, FB2/FB2.ZIP, TXT, RTF, DOC, DOCX, MOBI/AZW3 (KF8), CBZ/CBR (own RAR 2.9 + RAR5 decoders), DjVu (ZP/BZZ/JB2/IW44), `OriginalPagesDocument`, `ComicPageSource` |
| `android:pdf` | lib | `PdfPageSource`: PdfBox-Android text/fonts/images/vector-figure regions + PdfRenderer rasterisation |
| `android:ocr` | lib | `TesseractOcrEngine` (Tesseract4Android), bundled `eng.traineddata`, user-importable languages |
| `android:data` | lib | Room DB (books, collections, bookmarks, highlights/notes, history, per-book prefs, scan folders), `LibraryRepository` (discovery, move/missing handling, metadata & covers) |
| `android:render` | lib | `BlockLayouter` (StaticLayout + spans), `ReaderController` (anchored pagination, async chapter loading, image cache, progress), `ReaderView` (paged with slide animation / continuous scroll, taps, links, selection) |
| `app` | app | Compose UI: library, reader, panels, notes, history, settings; TTS controller + foreground service; DataStore settings |

Design points
* Every format becomes one block model, so font size (10–96 sp), family (system or imported
  TTF/OTF), bold, line/paragraph/letter spacing, indent, margins, alignment and hyphenation apply
  identically to all books, and text always reflows to the screen width (no horizontal scroll).
* Fixed-layout sources (PDF, DjVu, scans, comics in text mode) are reconstructed into paragraphs
  across columns and pages; pages without usable text are OCR'd; illustrations (embedded images,
  vector figures, picture regions on scans) are cropped and kept. "Original pages" view is available.
* Pagination is anchored: a page starts at any position and fills forward, so jumps are instant and
  huge books never need full pagination. Positions are stable *units*, so reading positions,
  bookmarks and notes survive font changes, clean-mode toggles and re-layout.
* Clean Reading Mode is computed on the fly; sources are opened read-only and never modified.

## Done (implemented and tested)

Formats (`core:formats` tests)
* EPUB, FB2, TXT, RTF (pandoc + LibreOffice dialects), DOC, DOCX, CBZ: text, italics, images,
  footnote links resolving to note text, TOC — `FormatsTest`.
* MOBI, AZW3, EPUB3, TXT on real Gutenberg books — `ExternalFixturesTest` (optional fixtures).
* RAR 2.9 LZ (+E8/E8E9/DELTA/RGB/AUDIO filters) and RAR5 (+filters), solid archives — CRC-verified.
* DjVu bundled documents: JB2 (incl. refinement), IW44 colour (pixel correlation 0.9999 vs
  DjVuLibre), text layer, outline, reflow — `DjvuTest`.

Reflow & Clean Reading Mode (`core:analysis` tests)
* LibreOffice PDFs (indented single column; two-column block style): all 52 paragraphs intact
  across pages/columns; headings; running headers, page numbers, watermark removed; preview,
  per-item restore, per-category disable — `ReconstructionTest`.
* Footnote linking, chapter numbers / dialogue / refrains preserved, flow-format cleaner —
  `CleanModeTest`; chunked engine, OCR fallback + cache, illustration detection, comic/picture
  pages shown whole instead of reflowed bubble text — `PagedDocumentTest`.
* Sentence segmentation for TTS — `SentencesTest`.
* Running headers on real books (archive.org scan with OCR-misread roman numerals, LaTeX books
  with section titles in headers) — `CleanModeTest`; inspect any PDF with
  `PROBE_PDF=book.pdf ./gradlew :core:analysis:test --tests '*RealPdfProbe*' --rerun`.
* OCR text repair, measured against Project Gutenberg's text of the same book (archive.org
  Sherlock Holmes scan, pages 121-160; scorer `tools/ocr_score.py` aligns and counts word errors, stray
  numbers, digits in words and junk symbols): scan text layer 1.95 % → 1.85 % word errors;
  Tesseract (app model, 1800 px) 2.19 % → 1.99 %, stray numbers 14 → 6, junk symbols 14 → 5;
  1000 px render 2.00 %. Most of what remains is edition differences (color/colour, "5 ft. 7 in.").
  Run-together words ("didnot", "wellto-do", "ina") are split when unknown to a 160k-word SCOWL
  dictionary and one half is a little word; "j"/"$" misread for "i"/"S" are repaired. Latest:
  scan text layer 1.80 %, Tesseract 1.93 %, 1000 px render 1.95 %. Clean-text probe: 84 of 580,441
  lines change, 43 of them Joyce's deliberate run-ons in Ulysses ("shesaw"), the rest dialect.
  Repairs run only on recognised text (OCR, a scan's invisible text layer, DjVu text): born-digital
  PDFs are left exactly as written (code like `part1`, `t.lt(90)` used to be "repaired").
  Zero changes on 580,441 lines of clean Gutenberg text (`CorrectorProbe`, `PROBE_TEXTS=dir`).
  Re-run: `EVAL_PDF=scan.pdf EVAL_FROM=120 EVAL_N=40 EVAL_MODE=layer|ocr EVAL_TSV_DIR=tsv
  EVAL_OUT=out.txt ./gradlew :core:analysis:test --tests '*ScanEvalProbe*' --rerun` (TSV from
  `tesseract page.png p-<n> tsv` with the app's model). OCR lines are built word by word
  (`OcrLines`, shared by the app engine and the probe): specks dropped, size from typical word height.
* Clean-mode detection speed (was ~1.5 s per 3-page section, now ~20 ms) — `CleanModeTest`;
  per-step PDF timings: `PROBE_PDF=book.pdf ./gradlew :android:pdf:testDebugUnitTest --tests '*PdfSpeedProbe*' --rerun -i`.

Read-aloud with Kokoro (`core:speech`, `app/.../tts`)
* Kokoro-82M (Apache-2.0) through ONNX Runtime 1.31 (MIT); pronunciation by a Kotlin port of
  misaki's lexicon with its US/GB dictionaries (Apache-2.0) and own spelling rules for unknown
  words (no espeak/GPL). Kotlin output equals the Python onnxruntime reference to 5e-5
  (`tools/kokoro_ref.py`). Intelligibility, Whisper base.en on 30 Sherlock Holmes sentences
  (`tools/asr_score.py`, spelling variants ignored): af_heart 1.2 % word errors, am_michael 0.55 %,
  bm_george 1.6 % (10 sentences each); errors are foreign names. Synthesis 0.39× real time on 4
  x86 cores — phone speed NOT measured (no device/emulator here).
* Model + 27 English voices (≈106 MB) download once from a pinned Hugging Face revision with a
  SHA-256 check (`KokoroModels`), or "Import zip" (`scripts/make_kokoro_zip.sh`). INTERNET
  permission is used only for that download.
* TtsController drives a `SpeechBackend` (phone voices or `KokoroSpeech`: worker thread a few
  sentences ahead, AudioTrack, playback position drives highlighting); queue tops up before it
  runs dry — `ReaderFeaturesTest.readAloudQueueKeepsAheadAndSwitchesVoices` (fake voice).
  KokoroSpeech itself (AudioTrack, Android ONNX Runtime) is compiled but not run on a device.

Android (Robolectric)
* PDF via PdfBox-Android: text extraction + reflow of the full fixture — `PdfPageSourceTest`.
* Library: scan of folder trees, metadata & covers, moved-file re-linking keeps progress, missing
  files flagged (notes kept), collections, bookmarks, history, per-book prefs, notes export;
  300 books in 20-level-deep trees with an unreadable folder and a symlink loop — `LibraryRepositoryTest`.
  Scan covers every storage volume (SD cards) + Android's media index when all-files access is
  granted; the "Allow" banner stays in the library until access is given (it used to vanish after
  the first book, leaving only one picked folder scanned — the cause of "only 40 of 1000 books").
* Pagination: every line of a whole book shown exactly once at 18 sp and 72 sp; backward paging;
  locator/progress round trips; page-count estimate within 1 % — `PaginationTest`.
* App flow: empty library → add book → open → page turns → 72 sp keeps position → contents panel
  — `AppFlowTest`; contents/slider jumps land on the target even while chapters load, switching
  pages ↔ vertical scrolling keeps the place — `NavigationTest`; bookmarks, highlights+notes, in-book search, footnote popup text, reversible
  Clean Reading Mode on a scanned DjVu (on/preview/restore one/off) — `ReaderFeaturesTest`.

## Implemented, compiled, not yet verified on a device

* Picture-page thresholds (`PagedBookDocument.isPicturePage`) are tuned on synthetic comic pages
  and the prose fixtures; check against real comic PDFs.
* Tesseract OCR on Android (native code cannot run under Robolectric; the OCR pipeline itself is
  tested with a fake engine). PdfRenderer page rendering (original-pages view, PDF figure crops).
* Text-to-speech playback (device TTS engine), foreground service notification.
* Multiple windows (`BookWindowActivity`, "Open in a new window" in book details and the reader
  menu): each book is its own task in recents / split screen.
* App colour schemes + light/dark/black (`ui/theme/Theme.kt`); 23 reading themes + custom colours
  (round trip tested in `ThemeTest`).
* Touch gestures (swipe animation, long-press selection drag), volume-key paging, immersive mode,
  SAF folder picking and the all-files-access permission flow, "Open with" intents.

## Remaining / known limitations

* On-device QA pass on real phones (performance on very large PDFs, OCR speed, memory).
* Selection handles are drag-to-extend after long press (no separate draggable handles).
* No password prompt for encrypted PDFs; DRM-protected books are refused with a message.
* RAR: PPMd-compressed blocks and multi-volume archives unsupported. DjVu: JPEG (BGjp/FGjp)
  layers and indirect multi-file documents outside the same folder untested.
* Hyphen ambiguity at line ends ("pine-|apple") is resolved by a document-vocabulary heuristic
  without a dictionary; rare compounds may lose their hyphen.
* APK is ~104 MB debug (ARM only: arm64-v8a, armeabi-v7a; ONNX Runtime + Tesseract native libs); an app bundle would split per ABI.
* Release signing uses the debug key; configure a real keystore.

## Licensing

See `THIRD_PARTY_NOTICES.md` (authoritative) and the in-app Licences screen (full texts in
`app/src/main/assets/licenses/`). Summary:
Apache-2.0: Kotlin, kotlinx, AndroidX/Compose/Room/DataStore, PdfBox-Android,
Tesseract4Android, Tesseract, tessdata_fast `eng.traineddata`. MIT: jsoup, djvu-rs (DjVu codecs
ported to Kotlin; see `THIRD_PARTY_NOTICES.md`). PdfBox-Android pulls BouncyCastle (MIT-style
licence). Tesseract4Android bundles native Leptonica, libjpeg and libpng (BSD/IJG/libpng permissive
licences) — flag for legal review if the Apache/MIT-only rule must cover transitive native code.
PdfBox-Android also bundles Liberation Sans (OFL 1.1), Adobe glyph-list/AFM data and Unicode data.
Theme fonts are Apache-2.0; wallpapers CC0 (AI Horde). **Open question:** the CBR/RAR decoder
follows UnRAR's structure; its provenance must be settled (keep, replace, or limit CBR support).
Test-only: JUnit (EPL), Robolectric (MIT), Apache PDFBox desktop (Apache-2.0).
