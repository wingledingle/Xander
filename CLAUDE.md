# Notes for AI agents working on Xander

- **Every build handed to the owner gets a build number.** Bump `build` in `version.properties` by one before each
  build you deliver. It sets `versionCode`, the version name ("1.0.0 build N", shown in Settings > About) and the APK
  file name (`xander-build-N-...apk`). Deliver APKs named `xander-build-N-<abi>.apk` and say the number in the message.
- The apps (Xander Files, Xander Suite, Groovesmith, Oleander, Quinten) and how they share code: `apps/README.md`.
  Deliver each as `<app>-build-N-arm64.apk` (e.g. `xander-files-build-17-arm64.apk`).
- Web modules: see `modules-web/README.md`. Vendored apps: `third_party/oleander/README.md`, `quinten/VENDORED.md`.
