# Web modules

Web apps that ship inside the Xander APK and open full screen from the file manager. Each lives in
`www/modules/<id>/` and is bundled as `assets/modules/<id>/` (see `sourceSets` in `app/build.gradle.kts`).
No build step and no npm dependencies: plain HTML, CSS and ES modules.

| Module | What it is |
|---|---|
| `studio/` | **Groovesmith Studio**: step sequencer with velocity, pads, synth with piano roll, sampler with chopping, video sampler with video/audio FX, sample library, mixer, WAV/project export. |
| `host/xander.js` | The bridge every module imports (see below). |

```bash
npm test          # unit tests (node:test) for the pure modules
npm run serve     # http://localhost:8790/modules/studio/ in a desktop browser
npm run proof     # Playwright walkthrough at phone size; screenshots → ../docs/proof/studio/
```

## Adding a module

Any module (web or native Kotlin) is one object listed in `Modules.all`
(`app/src/main/java/com/xander/files/modules/XanderModule.kt`). That gives it a drawer entry under
**Studio & apps**, an **Open in …** action for the file types in `opens`, and tap-to-open for `defaultFor`.

**Web module** (e.g. Oleander's GBA web app, a GLB/STL viewer):

1. Put the app in `www/modules/<id>/` with an `index.html`. Use relative URLs.
2. Add strings and the object:
   ```kotlin
   object GbaModule : WebModule("gba", R.string.module_gba, R.string.module_gba_summary, Icons.Filled.SportsEsports,
       opens = setOf("gba", "zip"), defaultFor = setOf("gba"))
   ```
3. List it in `Modules.all`. Read the launched file in JS:
   ```js
   import { host } from '../host/xander.js';
   if (host.launch) { const bytes = await (await fetch(host.launch.url)).arrayBuffer(); /* … */ }
   ```

**Native module** (e.g. an e-reader built from Quinten's Android modules): implement `XanderModule` with a
`@Composable Content(file, onBack)`.

## Bridge (`host/xander.js`)

| Call | Does |
|---|---|
| `host.launch` | The file the module was opened with: `{ url, name, mime, size, token, path }` or `null`. |
| `host.pick({ accept, multiple })` | System file picker. Resolves to file objects. |
| `fetch(file.url)` | Streams the file; Range requests work, so `<video src>` can seek. Files on SMB, cloud, etc. stream through the file manager's backends. |
| `host.decodeAudio(file, { startSec, maxSec })` | Decodes the audio track of any audio/video file with the phone's codecs → `{ url }` of a WAV. |
| `host.save(name, blob, { folder })` | Writes to `Music/<folder>/` and returns `{ path }`. |
| `host.openPath(path)` | Reopens a file by its file-manager path (e.g. a sample's source video). |
| `host.toast`, `host.keepScreenOn`, `host.exit` | Small UI helpers. |

In a desktop browser the same calls fall back to `<input type=file>`, `decodeAudioData` and downloads.

Pages run at `https://appassets.androidplatform.net/assets/modules/<id>/` with JavaScript, DOM storage and
IndexedDB, no file:// access, and external links opening in the browser. `window.XanderModule.onBack()`
returning `true` keeps the module open when the user presses Back.

## Low-latency audio

WebView audio has roughly 20–100 ms of output latency. That doesn't matter for anything scheduled ahead (the
sequencer runs on the audio clock), but finger drumming feels it. A module created with `lowLatencyAudio = true`
also gets `XanderHost.padLoad/padTrigger/padStopAll`, backed by `LowLatencyPads.kt`: an `AudioTrack` in
low-latency mode on an urgent-audio thread, mixing in the device's native burst size. Studio pre-renders
each track's hit through its channel strip (`studio/native.js`), uploads it once, and every pad touch is then
one bridge call. Delay/reverb sends are skipped on that path. Turn it off in Studio's menu.
