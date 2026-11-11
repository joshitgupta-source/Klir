# Changelog

All notable changes to **Klir** will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.0] - 2026-11-11

### Added
* **Native Rust Storage Engine (`klir_core`):**
    * Multi-threaded directory traversal powered by `jwalk` and `rayon`.
    * Parallel `BLAKE3` cryptographic hashing for zero-collision duplicate file detection.
    * JNI bridge (`StorageCleanerEngine`) connecting native Rust execution to Kotlin coroutines.
* **Smart Cleaner & Safety Features:**
    * Soft-delete Recycle Bin (`.klir_trash`) allowing safe inspection and restoration of deleted files.
    * Visual Treemap disk usage analyzer for inspecting large directories and file blobs.
    * Multi-volume storage support covering Internal Storage, removable SD cards, and USB-OTG drives.
* **Android System Integration:**
    * Periodic background maintenance and `.klir_trash` auto-purging via `KlirMaintenanceWorker` (`WorkManager`).
    * Quick Settings Tile (`KlirQuickTileService`) for one-tap cleaning from the system notification shade.
    * Accessibility Service (`KlirAccessibilityService`) for automated system-wide app cache clearing.
* **Build & CI/CD Pipeline:**
    * Automated cross-platform Gradle `buildRust` task using `cargo-ndk`.
    * ABI split configurations producing optimized `arm64-v8a`, `x86_64`, and universal release APKs.
    * GitHub Actions workflow (`release.yml`) for automated Rust compilation, R8 minification, artifact packaging, and GitHub Release publishing.

### Fixed
* **R8 / ProGuard Hardening:** Added targeted keep rules for `androidx.work.impl.*Database_Impl`, `KlirMaintenanceWorker`, and JNI native method signatures to prevent reflection crashes on startup.