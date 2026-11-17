# The apps

Everything here builds from this one repository. Shared code is never copied: each app is a thin shell around the
same modules Xander's all-in-one build uses.

| App | Package | Gradle | What's in it |
|---|---|---|---|
| **Xander Files** | `com.xander.files` | `:app` flavor `mobileFiles` | The file manager, its viewers, the 3D viewer and Quick Look. Hands files to the apps below when they're installed. |
| **Xander Suite** | `com.xander.files.suite` | `:app` flavor `mobileSuite` | Xander with the Studio, Oleander and Quinten inside (for your own phone). Installs next to Xander Files. |
| **Groovesmith** | `dev.groovesmith.studio` | `:apps:groovesmith` | The Studio (`modules-web/www/modules/studio`) on the shared native host (`:modules-host`). |
| **Oleander** | `dev.oleander.app` | `:apps:oleander` | The GBA emulator (`third_party/oleander`). |
| **Quinten Reader** | `app.quinten` | `:apps:quinten` | The reader (`quinten/`, unchanged library `:quinten:app`). |

Shared pieces:
- `modules-host/`: the web module host (page bridge, file streaming, native video + FX, low-latency pads, audio
  decoding). The app supplies storage through `HostStorage`: Xander's file systems in Xander
  (`XanderHostStorage`), plain phone storage in Groovesmith (`LocalHostStorage`).
- `third_party/oleander/`: Oleander's Activity and web app, used by both the suite and the Oleander app. Inside
  Xander its game list also gets Xander's index through `ExternalRoms`.
- `quinten/`: Quinten's modules, used by both the suite and the Quinten app. Inside Xander its library gets
  Xander's index through `ExternalBookIndex`.

Build all five (one ABI for sideloading) and check what each contains:
```bash
./gradlew :app:assembleMobileFilesRelease :app:assembleMobileSuiteRelease \
  :apps:groovesmith:assembleRelease :apps:oleander:assembleRelease :apps:quinten:assembleRelease -Pxander.abi=arm64-v8a
scripts/check-apks.sh
```
All five share the build number in `version.properties`.
