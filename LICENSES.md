# Third-party license policy

Only Apache-2.0 or MIT (or MIT-equivalent) open-source dependencies are allowed.

| Library | Purpose | License |
|---|---|---|
| AndroidX / Jetpack Compose / Room / WorkManager / DataStore / Media3 | App framework, players | Apache-2.0 |
| Hilt / Dagger | DI | Apache-2.0 |
| Kotlin, kotlinx.coroutines, kotlinx.serialization | Language/runtime | Apache-2.0 |
| OkHttp | HTTP, WebDAV, cloud REST clients | Apache-2.0 |
| Coil | Thumbnails | Apache-2.0 |
| smbj | SMB 2/3 client | Apache-2.0 |
| Apache Commons Net | FTP/FTPS client | Apache-2.0 |
| sshj | SFTP client | Apache-2.0 |
| Bouncy Castle | Crypto provider for sshj | MIT (Bouncy Castle licence) |
| EMC nfs-client | NFS v3 client | Apache-2.0 |
| Apache Commons Compress | 7z / tar / gz / bz2 / xz | Apache-2.0 |
| XZ for Java | LZMA for 7z | 0BSD (public-domain equivalent) |
| Zip4j | ZIP with AES passwords | Apache-2.0 |
| libsu | Root shell | Apache-2.0 |
| Shizuku API | Non-root privileged access | Apache-2.0 |
| AppAuth | OAuth for cloud drives | Apache-2.0 |

Proprietary Google SDKs required for platform features (not open source, but free to use):
Google Play Billing, Google Cast framework, Google Play services Auth (Drive sign-in).

Removed to comply with the policy: jcifs-ng (LGPL, SMB1), junrar (UnRAR licence), NanoHTTPD (BSD-3).
The built-in FTP and HTTP servers are implemented in this codebase.
