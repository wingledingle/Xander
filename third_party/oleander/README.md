# Oleander (GBA emulator)

Vendored from [wingledingle/Oleander](https://github.com/wingledingle/Oleander) at `25bc798`, dual-licensed MIT / Apache-2.0
(see `LICENSE-MIT`, `LICENSE-APACHE`).

- `assets/web/`: the web app (`web/` minus the service worker) with `oleander.wasm` built from source by
  `scripts/build-web.sh` (cargo, `wasm32-unknown-unknown`, profile `release-wasm`).
- `java/dev/oleander/app/`: the Android host Activity and the link-cable hub. Changes: the launcher icon comes from
  Xander's resources, and a ROM opened from a file waits for the page's `oleanderOpenNative` (defined only after the
  wasm core loads) instead of being dropped on a cold start.

To update: rebuild the wasm in an Oleander checkout and re-copy both folders.
Xander opens it through `OleanderModule` (app/src/main/java/com/xander/files/modules/).
