# Xander Files

An Android file manager (phone, tablet and Android TV), written in Kotlin with Jetpack Compose and Material 3.
Package `com.xander.files` (TV build: `com.xander.files.tv`). minSdk 23, targetSdk 36.

## Build

```
# needs JDK 17+ and the Android SDK (platform 36, build-tools 36)
./gradlew :app:assembleMobileDebug        # phone/tablet APK
./gradlew :app:assembleTvDebug            # Android TV APK
./gradlew :app:testMobileDebugUnitTest    # JVM + Robolectric tests
./gradlew :app:connectedMobileDebugAndroidTest   # on a device/emulator
XANDER_IT=1 ./gradlew :app:testMobileDebugUnitTest --tests '*NetworkIntegrationTest*'  # after scripts/start-test-servers.sh
```

Release builds are minified with R8. Sign them with your own keystore (`signingConfigs` / Play App Signing).

## Features

**Storage and browsing**
- Home dashboard: internal storage, SD card and USB volumes with usage bars, category shortcuts (images, video,
  music, documents, APKs, archives, large files, recent files) and quick access to Download, DCIM,
  Android/data and Android/obb.
- Browser: list and grid views, sorting, per-folder view memory, hidden files, folder item counts, thumbnails,
  tabs, dual pane at ≥720 dp, drag and drop between panes, breadcrumbs, "Go to path", history and favorites.
- Restricted folders: Android/data and Android/obb are reached through the system folder grant (Android 11–12),
  Shizuku (no root, Android 11+), or root. `/system` and `/data` are reachable with root (with remount r/w).
- Search covers all storage, including the restricted folders when Shizuku or root is on. It filters by name
  wildcards, category, size and date, uses an on-device index for speed, then walks the folders live.

**File operations**
- Copy, move, delete, rename, batch rename (pattern, find/replace with regex, case, prefix/suffix), duplicate,
  new file and folder, symlinks, permissions and owner (root), and properties with size calculation.
- Background transfer queue (WorkManager, foreground notification) with pause, resume, cancel and retry.
  Conflicts can overwrite, skip, keep both or merge.
- Recycle Bin with retention, a multi-item clipboard, home-screen shortcuts, and "Open with" with remembered
  defaults.

**Archives and encryption**
- Archives: browse ZIP, 7z, TAR, GZ, BZ2 and XZ as folders; extract all or selected; create ZIP (optionally
  AES-256 password) and 7z.
- RAR is not supported (the UnRAR licence is excluded).
- Encryption: AES-256-GCM, PBKDF2-SHA256, `.xenc`.

**Apps**
- App manager for user and system apps: sizes, back up the APK, share, uninstall, app info and Play Store.
- APK, split APK (APKS/XAPK/APKM) and OBB install.

**Built-in viewers and players** (can be turned off in Settings)
- Images: zoom and pan, swipe through the folder, slideshow, rotate, GIF/WebP animation, EXIF details.
- Video: Media3, subtitles (SRT/VTT/ASS/TTML, auto-detected next to the video), playback speed,
  picture-in-picture and resume position. Plays from any backend (LAN, cloud, TV).
- Music: library of songs, albums, artists, folders and playlists, plus queue, shuffle and repeat, a
  media-session notification and lock-screen controls.
- Text and code editor: syntax highlighting, line numbers, undo/redo, find/replace, encodings, and large-file
  paging.
- PDF viewer: pages, go to page, night mode.
- Office viewer (converted on the device): DOCX/XLSX/PPTX, ODT/ODS/ODP, legacy DOC/XLS/PPT, CSV and RTF.
- Book reader: EPUB 2/3 and TXT with chapters, table of contents, font size, light/sepia/dark themes, and it
  remembers your position.

**Network and cloud**
- LAN scan (port probe, NetBIOS, mDNS).
- LAN protocols:
  - SMB 2/3. SMB1 is not supported because the only SMB1 library is LGPL.
  - FTP and FTPS, and SFTP (password or private key, host-key trust).
  - WebDAV (Basic/Digest auth) and NFS v3.
  - HTTP directory listings.
- Cloud, with several accounts per service: Google Drive, Dropbox, OneDrive, Box, Yandex Disk, MEGA and Nextcloud.
- Share links where the service supports them.

**Access from PC**
- An FTP server written in this codebase: passive/active mode, resume, anonymous or user/password login, a
  shared folder of your choice (including Android/data through Shizuku), file-name encoding and a read-only option.
- A web server, also written here: browse, download with seeking, upload (drag and drop), new folder,
  rename and delete.
- A foreground notification shows the address and has a Stop button.

**Chromecast**
- Casts video (with subtitles), images and music queues to Chromecast / Google TV using Google's Default Media
  Receiver. Files on any backend are streamed from the phone over the LAN.

**Bluetooth**
- Paired and nearby devices, pairing and unpairing, and visibility to nearby devices.
- Sending works two ways: the system Bluetooth share (works with any phone or PC), or a direct Xander-to-Xander
  transfer (RFCOMM) with progress.
- Receiving works through the same two routes.

**USB OTG**
- A prompt appears when a drive is attached.
- Safe eject uses root or Shizuku (`sm unmount`); otherwise the app opens the system Storage screen.

**TV management** (install the TV build on the Android TV)
- On the TV: a companion service (starts at boot) shows a 6-digit pairing code. The TV app is a normal Leanback
  launcher app, and you can browse it with the D-pad.
- On the phone:
  - Discover TVs (mDNS) or add one by IP, then pair.
  - Files: browse, copy to and from the TV, rename, delete.
  - Apps: list with icons, launch, uninstall, back up the APK, install an APK from the phone.
  - Remote: D-pad, Back, Home, Recents, media keys, volume, and typing text. This needs the "Xander remote
    control" accessibility service enabled once on the TV.

**Settings and app**
- Themes: system, light, dark and AMOLED black, plus accent colours, skins, wallpaper colours and small-text mode.
- Language: English, Arabic (RTL), German, Spanish, French, Greek, Italian, Portuguese and Russian.
- App lock: PIN, fingerprint or face.
- Premium: Google Play Billing subscriptions `xander_premium_monthly` / `xander_premium_yearly` remove ads and
  unlock premium skins.
- Ads: the slot sits behind `AdProvider`. No ad network SDK is bundled. Plug one in by binding your own
  `AdProvider` in `di/AppModule.kt` and building with `-Pxander.adsEnabled=true`.

## Configuring the cloud OAuth client IDs

Put the keys in `local.properties` (never commit it) or pass them as `-P` Gradle properties or environment
variables:

```
xander.dropboxAppKey=...
xander.onedriveClientId=...
xander.boxClientId=...
xander.boxClientSecret=...
xander.yandexClientId=...
```

Every AppAuth service uses the redirect URI **`com.xander.files:/oauth2redirect`**. If a service's key is
empty, picking it under "Add account" shows a message that the key is missing.

| Service | Where | What to create | Notes |
|---|---|---|---|
| Google Drive | console.cloud.google.com → APIs & Services | Enable **Google Drive API**. Create an OAuth client of type **Android**: package `com.xander.files` (and `com.xander.files.tv` if you want Drive on TV), plus the **SHA-1 of your signing certificate** (debug and release/Play App Signing each need their own client). Configure the OAuth consent screen with the `.../auth/drive` scope. | No key goes into the build. Google matches the package name and certificate. The full `drive` scope needs Google verification before public release. |
| Dropbox | dropbox.com/developers/apps | Scoped app, "Full Dropbox". Permissions: `files.content.read/write`, `files.metadata.read/write`, `sharing.write`, `account_info.read`. Add the redirect URI. | `xander.dropboxAppKey` = App key. PKCE, so there is no secret. |
| OneDrive | portal.azure.com → App registrations | New registration, "Personal + work/school accounts". Add the platform **Mobile and desktop applications** with the redirect URI. Delegated permissions: `Files.ReadWrite.All`, `User.Read`, `offline_access`. | `xander.onedriveClientId` = Application (client) ID. No secret. |
| Box | app.box.com/developers/console | Custom App → **User Authentication (OAuth 2.0)**. Add the redirect URI. Scopes: read/write all files. | `xander.boxClientId` + `xander.boxClientSecret` (Box requires the secret at the token step). |
| Yandex Disk | oauth.yandex.com | New app with Disk permissions `cloud_api:disk.read`, `disk.write`, `disk.info`, plus `login:email`/`login:info`. Add the redirect URI as the callback URL. | `xander.yandexClientId` = ClientID. |
| MEGA | — | Nothing to register | Sign in with email + password (MEGA's own client-side crypto). |
| Nextcloud / ownCloud | — | Nothing to register | Login Flow v2 in the browser, or user name + app password. |

The Chromecast receiver needs no registration (Default Media Receiver). Play Billing needs the two
subscription products created in Play Console with the IDs above.

## Licences

See [LICENSES.md](LICENSES.md). All open-source dependencies are Apache-2.0, MIT, or public domain / CC0.
Three closed but free Google SDKs (Play Billing, Play services Auth, Cast) are used for subscriptions, Google
Drive and Chromecast.
