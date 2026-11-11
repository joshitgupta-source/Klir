# Contributing to Klir

Thank you for your interest in contributing to **Klir**! Whether you are fixing a bug, optimizing Rust traversal performance, or refining the Jetpack Compose UI, these guidelines will help you get set up quickly.

---

## 1. Development Environment Setup

To build and test both layers of Klir locally, ensure you have:

* **Android Studio** (latest stable or Canary build with Android SDK 37 and NDK installed)
* **JDK 17** or newer
* **Rust Stable Toolchain** via [rustup.rs](https://rustup.rs/):
  ```bash
  rustup target add aarch64-linux-android x86_64-linux-android
  cargo install cargo-ndk
  ```
* **NDK Environment Variable:**
  Set `ANDROID_NDK_HOME` to your local NDK directory so the Gradle `buildRust` task can locate the cross-compilation linkers on your OS.

---

## 2. Project Guidelines

### Rust Core (`klir_core/`)
* Keep all heavy I/O, directory traversal (`jwalk`), and file hashing (`blake3`) inside `klir_core/src/lib.rs`.
* Format your Rust code with `cargo fmt` and check for warnings using `cargo clippy` before committing.
* Do **not** commit the `klir_core/target/` build cache or generated `app/src/main/jniLibs/` binaries; Gradle generates these automatically during `preBuild`.

### Kotlin & R8 ProGuard (`app/`)
* UI components should follow **Jetpack Compose Material 3** conventions and keep state hoisted in `KlirViewModel.kt`.
* **CRITICAL — JNI & Reflection Keep Rules:** Release builds run with `isMinifyEnabled = true` and `isShrinkResources = true`. If you add or rename any `external fun` JNI methods, data models passed across JNI, or `WorkManager` classes, you **must** update `app/proguard-rules.pro` and test with `./gradlew assembleRelease` to ensure R8 does not strip the constructors or method signatures.

---

## 3. Submitting a Pull Request

1. Fork the repository and create a feature branch from `master` (e.g., `feat/custom-exclusion-filters` or `fix/symlink-handling`).
2. Verify that the release build compiles and runs cleanly on a physical device or emulator:
   ```bash
   ./gradlew assembleRelease
   ```
3. Ensure your commit messages follow a clear convention (e.g., `feat(rust): ...`, `fix(r8): ...`, `docs: ...`).
4. Open a Pull Request and confirm that the **Klir Android CI & Release** GitHub Actions workflow passes all checks.