# Third-party notices / 第三方声明

Copyright © 2026 smbplayer contributors. Original project code is licensed under GPL-3.0-or-later; see [LICENSE](LICENSE). Dependencies and generated tooling retain their own copyright and license terms. No endorsement by their authors is implied.

| Component | Version | License / upstream |
| --- | --- | --- |
| VideoLAN LibVLC Android (`libvlc-all`) | 3.6.5 | LGPL-2.1-or-later; [exact Android source tag](https://code.videolan.org/videolan/vlc-android/-/tree/libvlc-3.6.5), [LibVLC](https://www.videolan.org/vlc/libvlc.html) |
| jCIFS-NG | 2.1.10 | LGPL-2.1-or-later; [source and license](https://github.com/AgNO3/jcifs-ng/tree/jcifs-ng-2.1.10) |
| NanoHTTPD | 2.3.1 | BSD-3-Clause; [source / license](https://github.com/NanoHttpd/nanohttpd/tree/nanohttpd-project-2.3.1) |
| SLF4J API (transitive) | 1.7.36 | MIT; [license](https://www.slf4j.org/license.html) |
| Bouncy Castle bcprov (transitive) | 1.77 | Bouncy Castle license (MIT-style); [license](https://www.bouncycastle.org/licence.html) |
| AndroidX support libraries (transitive) | resolved by Gradle | Apache-2.0; [source](https://android.googlesource.com/platform/frameworks/support/) |
| desugar_jdk_libs (build / runtime support) | 2.1.4 | GPL-2.0 with Classpath Exception; [source / notices](https://github.com/google/desugar_jdk_libs) |
| Gradle Wrapper | 8.10.2 | Apache-2.0; [source / notices](https://github.com/gradle/gradle/tree/v8.10.2) |

The prebuilt LibVLC artifact is used unmodified. Its native engine includes additional upstream components and license notices; the Android source tag contains native build scripts and references to the corresponding VLC / contrib sources. Rebuilding or redistributing the native artifact requires preserving those component notices and providing the corresponding sources required by their licenses; this application's source alone is not a replacement for the engine sources. Use the exact version's upstream source and build instructions rather than the latest development branch.

Dependency resolution can be inspected with `./gradlew :app:dependencies --configuration debugRuntimeClasspath`. License texts shipped with upstream artifacts remain authoritative for those components. The `licenses/` directory contains copies of the principal dependency license texts; it is not a claim that all transitive dependencies share one license.
