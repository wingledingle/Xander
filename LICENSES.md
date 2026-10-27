# Third-party license policy

Open-source dependencies must be Apache-2.0 or MIT (or MIT-equivalent). Nothing in the APK requires
you to publish your own source code or pay a licence fee. Audited from `mobileReleaseRuntimeClasspath`.

| Library | Purpose | License |
|---|---|---|
| AndroidX / Jetpack Compose / Room / WorkManager / DataStore / Media3 / Leanback / MediaRouter | App framework, players, TV | Apache-2.0 |
| Hilt / Dagger, javax.inject, jakarta.inject | Dependency injection | Apache-2.0 |
| Kotlin stdlib, kotlinx.coroutines, JetBrains annotations, JSpecify | Language/runtime | Apache-2.0 |
| Guava (transitive) | Utilities used by Media3/Cast | Apache-2.0 |
| OkHttp, Okio | HTTP, WebDAV, cloud REST, TV client | Apache-2.0 |
| Coil 3 | Thumbnails and images | Apache-2.0 |
| smbj, asn-one (hierynomus) | SMB 2/3 client | Apache-2.0 |
| MBassador (transitive of smbj) | Event bus | MIT |
| SLF4J API | Logging facade used by smbj/sshj | MIT |
| Apache Commons Net / IO / Codec / Compress | FTP/FTPS, archives | Apache-2.0 |
| sshj | SFTP client | Apache-2.0 |
| Bouncy Castle | Crypto provider for sshj / MEGA | MIT (Bouncy Castle licence) |
| EdDSA-Java (net.i2p.crypto, transitive of sshj) | Ed25519 SSH keys | CC0-1.0 (public-domain dedication, no conditions) |
| EMC nfs-client, Netty (transitive) | NFS v3 client | Apache-2.0 |
| XZ for Java 1.9 (pinned) | LZMA for 7z / .xz | Public domain |
| Zip4j | ZIP with AES passwords | Apache-2.0 |
| TensorFlow Lite | On-device image classifier runtime | Apache-2.0 |
| GantMan nsfw_model (MobileNetV2 224, bundled asset) | Adult-content classes: drawings/hentai/neutral/porn/sexy | MIT |
| libsu | Root shell | Apache-2.0 |
| Shizuku API | Non-root privileged access | MIT |
| AppAuth | OAuth for Dropbox / OneDrive / Box / Yandex | Apache-2.0 |
| Accompanist | Compose helpers | Apache-2.0 |
| Firebase encoders / Google datatransport (transitive of Play Billing) | Play telemetry plumbing | Apache-2.0 |

Bundled web modules (`modules-web/www/modules/`):
- Groovesmith Studio: Apache-2.0. Built on Groovesmith by the Callback contributors (Apache-2.0). No third-party code,
  fonts or samples except the sound packs below: sounds are synthesized, come from the user's own files, or are CC0.
- Studio sound packs (`studio/sounds/`): 473 effects from Kenney's Impact, Interface, Sci-fi, Digital, RPG and Casino
  audio packs, CC0 1.0 (public domain, no attribution required; credited anyway). License files ship next to each pack.

Vendored apps (`third_party/`):
- Oleander GBA emulator (`third_party/oleander`): MIT / Apache-2.0, Oleander contributors. Web app, wasm core built
  from source, and its Android host Activity. No BIOS or games included.

Excluded on purpose:
- `com.google.code.findbugs:jsr305` (BSD-3, annotations only) is excluded from the runtime classpath.
- `org.tukaani:xz` 1.10+ (0BSD) is replaced by 1.9 (public domain).
- jcifs-ng (LGPL, SMB1), junrar (UnRAR licence), NanoHTTPD (BSD-3), Readium (BSD-3) and libarchive (BSD) are not used.
  The FTP server, web server, Cast stream server, TV companion API, EPUB reader and Office converters are written in this codebase.

Proprietary Google SDKs (free of charge, closed source, no source-release obligation; Google's Android SDK terms apply):
- Google Play Billing (`com.android.billingclient`) – subscriptions.
- Google Play services Auth (`play-services-auth`) – Google Drive sign-in.
- Google Cast framework (`play-services-cast-framework`) – Chromecast.

These cannot be replaced with Apache/MIT code if you want Play subscriptions, Google Drive and Chromecast.
On devices without Google Play services the app still runs; those three features are simply unavailable.
