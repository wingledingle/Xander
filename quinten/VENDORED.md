# Quinten (Dingo Reader) inside Xander

Vendored from [wingledingle/Quinten](https://github.com/wingledingle/Quinten) at `844a8b3`. Its Gradle modules are
included from `settings.gradle.kts` as `:quinten:*`; Xander opens the reader through `QuintenModule`.

Changes from upstream:
- `app` builds as an Android library; Xander owns the Application class, so `AppContainer` is created on first use
  (`Quinten.container`). Its launcher entry and "Open with" filters are removed (Xander routes files to it).
- Library versions (`libs.versions.toml`) aligned with Xander's toolchain: one Kotlin/AGP/KSP, Room 2.8, Compose BOM.
- Export `FileProvider` is its own subclass and `xml/quinten_export_paths`, so it doesn't merge with Xander's.
- **Incremental scans**: the scanner remembers every folder it listed (date, subfolders, book files) and every
  file it could not read. A rescan only lists folders whose date changed and never reopens rejected files that
  are unchanged; new books are still found. "Full rescan" (library menu) re-examines everything, and so does
  the first scan each week. Tests: `android/data/src/test/.../IncrementalScanTest.kt`.

Known: `android/render` `NavigationTest.screenReaderScrollActionsTurnPages` fails here and also in the upstream
repo with its own toolchain (same numbers), so it predates this integration.
