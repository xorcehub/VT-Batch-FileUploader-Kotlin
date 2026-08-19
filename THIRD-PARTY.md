# Third-Party Dependencies

Complete runtime dependency set (resolved `runtimeClasspath` of `shared`, `cli`,
and `desktop`, transitives included), each license verified from the published
artifacts — see [Regenerating this file](#regenerating-this-file).

### Apache License 2.0 — 65 artifacts
- `androidx.annotation:annotation-jvm:1.9.1`
- `androidx.arch.core:core-common:2.2.0`
- `androidx.collection:collection-jvm:1.5.0`
- `androidx.lifecycle:lifecycle-common-jvm:2.8.5`
- `androidx.lifecycle:lifecycle-runtime-desktop:2.8.5`
- `androidx.lifecycle:lifecycle-viewmodel-desktop:2.8.5`
- `com.squareup.okhttp3:okhttp:4.12.0`
- `com.squareup.okhttp3:okhttp-sse:4.12.0`
- `com.squareup.okio:okio-jvm:3.10.2`
- `info.picocli:picocli:4.7.6`
- `io.github.oshai:kotlin-logging-jvm:7.0.7`
- `io.ktor:ktor-client-content-negotiation-jvm:3.1.3`
- `io.ktor:ktor-client-core-jvm:3.1.3`
- `io.ktor:ktor-client-okhttp-jvm:3.1.3`
- `io.ktor:ktor-events-jvm:3.1.3`
- `io.ktor:ktor-http-cio-jvm:3.1.3`
- `io.ktor:ktor-http-jvm:3.1.3`
- `io.ktor:ktor-io-jvm:3.1.3`
- `io.ktor:ktor-network-jvm:3.1.3`
- `io.ktor:ktor-serialization-jvm:3.1.3`
- `io.ktor:ktor-serialization-kotlinx-json-jvm:3.1.3`
- `io.ktor:ktor-serialization-kotlinx-jvm:3.1.3`
- `io.ktor:ktor-sse-jvm:3.1.3`
- `io.ktor:ktor-utils-jvm:3.1.3`
- `io.ktor:ktor-websocket-serialization-jvm:3.1.3`
- `io.ktor:ktor-websockets-jvm:3.1.3`
- `org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose-desktop:2.8.4`
- `org.jetbrains.compose.animation:animation-core-desktop:1.8.1`
- `org.jetbrains.compose.animation:animation-desktop:1.8.1`
- `org.jetbrains.compose.desktop:desktop-jvm:1.8.1`
- `org.jetbrains.compose.foundation:foundation-desktop:1.8.1`
- `org.jetbrains.compose.foundation:foundation-layout-desktop:1.8.1`
- `org.jetbrains.compose.material:material-desktop:1.8.1`
- `org.jetbrains.compose.material:material-icons-core-desktop:1.7.3`
- `org.jetbrains.compose.material:material-icons-extended-desktop:1.7.3`
- `org.jetbrains.compose.material:material-ripple-desktop:1.8.1`
- `org.jetbrains.compose.material3:material3-desktop:1.8.1`
- `org.jetbrains.compose.runtime:runtime-desktop:1.8.1`
- `org.jetbrains.compose.runtime:runtime-saveable-desktop:1.8.1`
- `org.jetbrains.compose.ui:ui-backhandler-desktop:1.8.1`
- `org.jetbrains.compose.ui:ui-desktop:1.8.1`
- `org.jetbrains.compose.ui:ui-geometry-desktop:1.8.1`
- `org.jetbrains.compose.ui:ui-graphics-desktop:1.8.1`
- `org.jetbrains.compose.ui:ui-text-desktop:1.8.1`
- `org.jetbrains.compose.ui:ui-tooling-preview-desktop:1.8.1`
- `org.jetbrains.compose.ui:ui-unit-desktop:1.8.1`
- `org.jetbrains.compose.ui:ui-util-desktop:1.8.1`
- `org.jetbrains.kotlin:kotlin-stdlib:2.1.21`
- `org.jetbrains.kotlin:kotlin-stdlib-jdk7:1.8.21`
- `org.jetbrains.kotlin:kotlin-stdlib-jdk7:2.1.10`
- `org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.8.21`
- `org.jetbrains.kotlin:kotlin-stdlib-jdk8:2.1.10`
- `org.jetbrains.kotlinx:atomicfu-jvm:0.23.2`
- `org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.2`
- `org.jetbrains.kotlinx:kotlinx-coroutines-slf4j:1.10.2`
- `org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2`
- `org.jetbrains.kotlinx:kotlinx-datetime-jvm:0.6.0`
- `org.jetbrains.kotlinx:kotlinx-io-bytestring-jvm:0.7.0`
- `org.jetbrains.kotlinx:kotlinx-io-core-jvm:0.7.0`
- `org.jetbrains.kotlinx:kotlinx-serialization-core-jvm:1.8.1`
- `org.jetbrains.kotlinx:kotlinx-serialization-json-io-jvm:1.8.1`
- `org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:1.8.1`
- `org.jetbrains.skiko:skiko-awt:0.9.4.2`
- `org.jetbrains.skiko:skiko-awt-runtime-windows-x64:0.9.4.2`
- `org.jetbrains:annotations:23.0.0`

### MIT License — 1 artifact

- `org.slf4j:slf4j-api:2.0.17`

*License not declared in the POM; confirmed from `META-INF/LICENSE.txt` inside
the shipped sources jar (QOS.ch, MIT).*

### EPL-1.0 / LGPL-2.1 (dual) — 2 artifacts

- `ch.qos.logback:logback-classic:1.5.18`
- `ch.qos.logback:logback-core:1.5.18`

*License not declared in the POM; confirmed from the `Bundle-License` manifest
header of both jars: [EPL-1.0](https://www.eclipse.org/legal/epl-v10.html),
[LGPL-2.1](https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html).*

## Binary distributions

The full texts ship inside every installer (MSI/DMG/DEB/RPM) via
`desktop/packaging/common/licenses/` (wired through `appResourcesRootDir`);
they land in the installed app image at `app/resources/licenses/`.
Verified with `:desktop:createDistributable` + `cmp` — all files byte-identical.

## Regenerating this file

Licenses are read from the published POMs of the exact resolved versions:

```
./gradlew -q -I scripts/license-report.init.gradle \
  :shared:licenseReport :cli:licenseReport :desktop:licenseReport \
  | grep '^ARTIFACT' | sed 's/^ARTIFACT //; s/ |.*//' | grep -v ':unspecified$' | sort -u \
  | bash scripts/fetch-licenses.sh
```

The two `NO-LICENSE-ELEMENT` rows (logback, slf4j) are confirmed from the
artifacts directly: `unzip -p <jar> META-INF/MANIFEST.MF | grep Bundle-License`
and the sources jar's `META-INF/LICENSE.txt`.

Notes:

- `skiko-awt-runtime-*` is platform-specific (`windows-x64` here; `linux-*` /
  `macos-*` on other platforms).
- Test-only dependencies (`kotlin-test`, `kotlinx-coroutines-test`,
  `ktor-client-mock`, `junit`, `hamcrest`) are not distributed and are omitted.

---

This project is licensed under the MIT License — see [LICENSE](LICENSE).
