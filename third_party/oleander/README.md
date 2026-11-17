# Oleander (GBA emulator)

Vendored from [wingledingle/Oleander](https://github.com/wingledingle/Oleander) at `25bc798`, dual-licensed MIT / Apache-2.0
(see `LICENSE-MIT`, `LICENSE-APACHE`).

- `assets/web/`: the web app (`web/` minus the service worker) with `oleander.wasm` built from source by
  `scripts/build-web.sh` (cargo, `wasm32-unknown-unknown`, profile `release-wasm`).
- `java/dev/oleander/app/`: the Android host Activity and the link-cable hub. Changes: the launcher icon is the hosting app's, and a ROM opened from a file waits for the page's `oleanderOpenNative` (defined only after the
  wasm core loads) instead of being dropped on a cold start.
- Xander additions: 15 full themes (`css/themes.css`), a bezel-less "edge to edge" screen layout, battery saver
  (30 fps picture, no effects; no frame callbacks while paused; 60 Hz display mode on 90/120 Hz screens), boot
  chime only on first launch by default, touch-control size, quick save/load and rewind touch buttons, Android 16
  back gesture, and inside Xander the game list also comes from Xander's storage index (`ExternalRoms`, set by `OleanderModule`),
  including ROMs in zips.

To update: rebuild the wasm in an Oleander checkout and re-copy both folders.
Xander opens it through `OleanderModule` (app/src/main/java/com/xander/files/modules/).
It also builds as its own app, `dev.oleander.app`, from `apps/oleander` (same code and web app, own folder scan).
