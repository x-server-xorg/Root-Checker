<div align="center">

# 🛡️ Root Checker

**A Material 3 root-detection app for Android 8.0+**

`minSdk 26` · `targetSdk 34` · Kotlin · 14 independent checks · Light & Dark theme

[![Platform](https://img.shields.io/badge/Android-8.0%20%2B-3DDC84?logo=android&logoColor=white)](#)
[![Min SDK](https://img.shields.io/badge/minSdk-26-orange)](#)
[![Language](https://img.shields.io/badge/Kotlin-2.0-7F52FF?logo=kotlin&logoColor=white)](#)
[![Build](https://img.shields.io/badge/Gradle-8.9%20%2B%20AGP%208.7.3-02303A?logo=gradle&logoColor=white)](#)
[![License](https://img.shields.io/badge/debug--signed-Android%20Debug-b3261e)](#)

</div>

---

## ✨ Overview

Root Checker scans a device using **14 independent heuristics** and reports whether it is rooted —
or might be. Every check runs in isolation, so one failure never breaks the rest, and each result is
shown to the user with a human-readable explanation instead of a bare true/false.

**Design highlights**

- Material 3 (`Theme.Material3.DayNight`) with a custom indigo / teal palette
- Fully adaptive **light and dark themes**, including status-bar icon contrast
- **In-app language switcher** — Русский / English / Українська (native per-app locales, persisted)
- Animated hero card: pulsating shield while scanning, **color transitions** on verdict
- Results appear as **staggered, animated cards** with status chips
- Adaptive launcher icon, Russian UI, auto-start on launch

## 📦 Download

| Artifact | Path |
|---|---|
| **Release APK (debug-signed)** | `/home/valance78/RootChecker.apk` |
| Build output | `RootChecker/app/build/outputs/apk/debug/app-debug.apk` |
| **Application ID** | `com.valance78.rootcheck` (namespace for sources: `com.fluent.rootchecker`) |

> The APK is signed with the standard Android debug key
> (`~/.android/debug.keystore`, alias `androiddebugkey`, `CN=Android Debug`).

## 🔍 Detection checks

| # | Check | Type | Signals |
|---:|---|---|---|
| 1 | `su` binaries in 12 standard paths | Filesystem | 🔴 Root |
| 2 | `which su` (PATH lookup) | Shell | 🔴 Root |
| 3 | `su -c id` — real root session attempt (3 s timeout) | Shell | 🔴 Root |
| 4 | `Build.TAGS` contains `test-keys` | Build | 🔴 Root |
| 5 | 20 root managers (Magisk, KernelSU, SuperSU, KingRoot, Framaroot, Towelroot…) | Packages | 🔴 Root |
| 6 | Magisk / KernelSU traces in `/data`, `/sbin` | Filesystem | 🔴 Root |
| 7 | SUID binaries in system folders | Filesystem | 🔴 Root |
| 8 | `/system` mounted read-write | Mounts | 🟡 Warning |
| 9 | `ro.secure = 0` | Props | 🟡 Warning |
| 10 | `ro.debuggable = 1` | Props | 🟡 Warning |
| 11 | `ro.build.type` ≠ `user` | Props | 🟡 Warning |
| 12 | BusyBox present | Filesystem | 🟡 Warning |
| 13 | Unlocked bootloader / verified-boot state | Props | 🟡 Warning |
| 14 | Root-cloaking apps (Root Cloak, Hide Root…) | Packages | 🟡 Warning |

**Verdict logic**

| Condition | Result |
|---|---|
| Any 🔴 check hits | `Root обнаружен` (Root detected) |
| Only 🟡 checks hit | `Возможен root` (Root possible) |
| Nothing hits | `Root не обнаружен` (No root detected) |
| Check throws | `Н/Д` (N/A) — never blocks the scan |

## 🌍 Localization

| Language | Code | Location |
|---|---|---|
| English (default) | `en` | `res/values/strings.xml` |
| Русский | `ru` | `res/values-ru/strings.xml` |
| Українська | `uk` | `res/values-uk/strings.xml` |

- Every UI string is translated into all three languages (27 strings × 3).
- The switcher sits in the header next to the title and opens a checkable popup menu;
  the chosen locale is applied instantly via `AppCompatDelegate.setApplicationLocales`
  and persisted across launches (system settings entry on Android 13+ via
  `android:localeConfig` → `res/xml/locales_config.xml`).
- If the user never opens the menu, the app follows the device locale —
  falling back to English for anything outside `ru` / `uk`.

## 🏗️ Build

The toolchain lives entirely in `$HOME` — **no root / sudo required**.

| Component | Location |
|---|---|
| JDK 17 (Temurin) | `~/opt/jdk17` |
| Android SDK (platform-34, build-tools 34.0.0) | `~/opt/android-sdk` |
| Gradle 8.9 | `~/opt/gradle` |

```bash
export JAVA_HOME=$(readlink -f ~/opt/jdk17)
export ANDROID_HOME=$HOME/opt/android-sdk
export PATH="$JAVA_HOME/bin:$PATH"

cd /home/valance78/RootChecker
./gradlew assembleDebug
```

<details>
<summary><b>Verify the artifact</b></summary>

```bash
BT=$ANDROID_HOME/build-tools/34.0.0
APK=app/build/outputs/apk/debug/app-debug.apk

$BT/aapt dump badging "$APK" | grep -E "package|sdkVersion|launchable-activity"
$BT/apksigner verify --print-certs "$APK"
$BT/zipalign -c 4 "$APK" && echo "zipalign OK"
```

Expected: `sdkVersion:'26'`, `targetSdkVersion:'34'`,
`CN=Android Debug`, `zipalign OK`.

</details>

<details>
<summary><b>Other build variants</b></summary>

```bash
./gradlew assembleRelease   # unsigned release build
./gradlew clean             # clean outputs
./gradlew lint              # static analysis
```

</details>

## 📁 Project structure

```
RootChecker/
├── app/src/main/java/com/fluent/rootchecker/
│   ├── MainActivity.kt        # UI, animations, scan orchestration
│   ├── CheckAdapter.kt        # staggered result cards
│   └── RootScanner.kt         # all 14 detection checks
├── app/src/main/res/
│   ├── layout/                # activity_main.xml, item_check.xml
│   ├── values/ · values-night/# colors, themes, strings
│   ├── drawable/              # vector icons
│   └── mipmap-anydpi-v26/     # adaptive launcher icon
├── build.gradle               # AGP 8.7.3 + Kotlin 2.0.21
├── gradle.properties          # 1.5 GB heap for low-RAM hosts
└── README.md
```

## 🧭 Architecture notes

- **Threading** — checks execute on `Dispatchers.IO`; the UI observes them through
  `lifecycleScope`, one check at a time, with a 90 ms cadence for a readable animation.
- **Resilience** — every check is wrapped: an exception downgrades a single item to `N/A`.
- **Process safety** — external commands (`su -c id`) run with `waitFor(timeout)` and are
  force-killed on expiry, so a hung `su` cannot freeze the app.
- **No permissions** — the app requests none; `<queries>` only declares root-manager packages
  for package visibility on Android 11+.

## ⚠️ Limitations

- Verdict is based on static heuristics — sophisticated cloaking (MagiskHide / Zygisk denylist)
  can hide traces, so `No root detected` is not a proof of a clean device.
- Not tested on a physical device/emulator in this environment; verified via build, `aapt`
  badging and `apksigner` signature checks.

---

<div align="center">
<sub>Built with Gradle 8.9 · AGP 8.7.3 · Kotlin 2.0.21 · Material Components 1.12</sub>
</div>
