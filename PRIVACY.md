# Privacy Policy for Klir

**Last Updated:** November 11, 2026

Klir (`com.joshit.klir`) is built on a strict privacy-first principle: **your files and storage metadata never leave your device.**

## 1. Zero Network Access & Zero Telemetry

* **No Internet Permission:** Klir does **not** request or declare `android.permission.INTERNET` or `android.permission.ACCESS_NETWORK_STATE` in its `AndroidManifest.xml`. It is technically impossible for the application to transmit data to external servers.
* **No Third-Party SDKs:** Klir contains no analytics frameworks, crash-reporting trackers, advertising SDKs, or telemetry modules.

## 2. How Permissions Are Used

Because Klir is a low-level storage analyzer and cleaner, it requests elevated Android permissions. Below is the exact technical purpose for each permission:

### All Files Access (`android.permission.MANAGE_EXTERNAL_STORAGE`)
* **Why it is needed:** Android's Scoped Storage restricts apps from viewing or deleting residual files, orphaned directories, empty folders, and duplicate media across shared storage (`Internal Storage`, `SD Cards`, and `USB-OTG` drives).
* **How it is used:** The native Rust engine (`klir_core`) reads file metadata (size, modification date, path) to build the storage treemap, computes local `BLAKE3` cryptographic hashes in RAM to detect duplicate files, and moves selected junk files into the local `.klir_trash` directory or deletes them upon user confirmation.

### Accessibility Service (`android.permission.BIND_ACCESSIBILITY_SERVICE`)
* **Why it is needed:** Android restricts third-party apps from directly clearing the system cache of other installed applications without user interaction.
* **How it is used:** When explicitly triggered by the user, `KlirAccessibilityService` navigates the Android Settings "App Info" screen to automatically tap the "Storage & Cache" and "Clear Cache" buttons.
* **Strict Safeguards:** The service only inspects UI node labels related to system storage settings while an active cache-cleaning session is running. It **never** reads, logs, or stores personal text, messages, passwords, or screen content from other apps.

### Background Work & Notifications (`android.permission.POST_NOTIFICATIONS`)
* **Why it is needed:** Used by `KlirMaintenanceWorker` (via Android `WorkManager`) and `KlirQuickTileService`.
* **How it is used:** Runs scheduled local maintenance to purge expired items from the on-device `.klir_trash` folder and displays a local notification summarizing how much storage space was reclaimed.

## 3. Verifiability

Klir is 100% free and open-source software. Every line of Kotlin and Rust code—and the automated GitHub Actions release pipeline that compiles the official APKs—can be audited directly in this repository.