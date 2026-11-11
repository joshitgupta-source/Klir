# Klir

[![Klir Android CI & Release](https://github.com/joshitgupta-source/Klir/actions/workflows/release.yml/badge.svg)](https://github.com/joshitgupta-source/Klir/actions)
[![Platform](https://img.shields.io/badge/Platform-Android%207.0%2B%20(API%2024)-3DDC84?logo=android)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/UI-Jetpack%20Compose-7F52FF?logo=kotlin)](https://kotlinlang.org)
[![Rust](https://img.shields.io/badge/Core-Rust%20JNI-DEA584?logo=rust)](https://www.rust-lang.org)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

**Klir** is a high-performance, open-source Android storage cleaner and disk analyzer built with a dual-engine architecture: a declarative **Kotlin Jetpack Compose (Material 3)** frontend paired with a multi-threaded **Rust JNI core** (`klir_core`) for parallel filesystem traversal and cryptographic duplicate detection.

---

## Key Features

* **Blazing-Fast Parallel Scanning (`jwalk` + `rayon`):** Traverses deep directory trees across internal storage, SD cards, and USB-OTG drives using lock-free native threads.
* **Cryptographic Duplicate Detection (`BLAKE3`):** Identifies exact duplicate files using size pre-filtering followed by parallel `BLAKE3` hashing.
* **Safe Recycle Bin (`.klir_trash`) & Auto-Purge:** Soft-deletes files into an isolated trash directory with one-tap restoration and automated background purging via Android `WorkManager`.
* **Visual Disk Treemap Analyzer:** Interactive proportional storage map to pinpoint oversized folders and media blobs at a glance.
* **Android System Integration:**
    * **Quick Settings Tile (`KlirQuickTileService`):** Trigger instant background junk sweeps directly from the Android notification shade.
    * **Automated App Cache Sweeper (`KlirAccessibilityService`):** Optional accessibility automation to clear system app caches hands-free.
* **100% Offline & Private:** Zero telemetry, zero ads, and no internet permission declared.

---

## Architecture Overview

```text
Klir/
├── app/                                  # Kotlin Android Application Module
│   ├── src/main/java/com/joshit/klir/
│   │   ├── MainActivity.kt               # Jetpack Compose UI & Navigation
│   │   ├── KlirViewModel.kt              # Reactive UI State & Volume Management
│   │   ├── StorageCleanerEngine.kt       # JNI Bridge to libklir_core.so
│   │   ├── JunkModels.kt                 # Data Models (JunkItem, TrashItem, DiskNode)
│   │   ├── FormatUtils.kt                # Byte & Timestamp Formatting Utilities
│   │   ├── KlirMaintenanceWorker.kt      # WorkManager Periodic Auto-Purge Worker
│   │   ├── KlirQuickTileService.kt       # Android Quick Settings Tile Service
│   │   └── KlirAccessibilityService.kt   # Automated System Cache Clearing Service
│   └── proguard-rules.pro                # R8 Keep Rules for JNI & WorkManager
└── klir_core/                            # Native Rust Crate (cdylib)
    ├── Cargo.toml                        # Dependencies: jni, jwalk, blake3, rayon
    └── src/lib.rs                        # Native Traversal, Hashing & File Operations
```

---

## Building from Source

### Prerequisites

1. **Android Studio** (with Android SDK Platform 37 and Android NDK installed)
2. **JDK 17+**
3. **Rust Toolchain** (`rustup`) with Android cross-compilation targets:
   ```bash
   rustup target add aarch64-linux-android x86_64-linux-android
   ```
4. **`cargo-ndk`** for bridging Cargo with the Android NDK:
   ```bash
   cargo install cargo-ndk
   ```

### Compile & Assemble

The Gradle build script (`app/build.gradle.kts`) automatically invokes `cargo-ndk` during the `preBuild` lifecycle stage to compile `libklir_core.so` for `arm64-v8a` and `x86_64` before packaging the APK.

```bash
# Clone the repository
git clone [https://github.com/joshitgupta-source/Klir.git](https://github.com/joshitgupta-source/Klir.git)
cd Klir

# Set your NDK path (Linux/macOS example; Windows falls back automatically if configured)
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/<your-ndk-version>

# Build R8-minified release APKs
./gradlew assembleRelease
```

Generated split and universal APKs are output to `app/build/outputs/apk/release/`:
* `app-arm64-v8a-release.apk` — For 64-bit physical Android devices
* `app-x86_64-release.apk` — For x86_64 Android emulators
* `app-universal-release.apk` — Combined multi-architecture build

---

## Permissions & Privacy

Klir requires specific elevated permissions to function as a system-wide cleaner. All operations happen strictly on-device. See [PRIVACY.md](PRIVACY.md) for a complete breakdown of permissions and data handling.

---

## License

Distributed under the MIT License. See [LICENSE](LICENSE) for full details.