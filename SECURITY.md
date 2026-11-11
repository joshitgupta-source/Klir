# Security Policy

## Supported Versions

Only the latest stable release of Klir receives active security updates and patches.

| Version | Supported          |
| ------- | ------------------ |
| 1.0.x   | :white_check_mark: |
| < 1.0   | :x:                |

## Security Scope

Because Klir performs low-level file deletions via a native Rust JNI library (`klir_core`) and utilizes Android's `MANAGE_EXTERNAL_STORAGE` and `BIND_ACCESSIBILITY_SERVICE` permissions, we treat the following areas as security-critical:

1. **Memory Safety & FFI Boundaries:** Any buffer overflow, undefined behavior, or JNI pointer mishandling inside `klir_core/src/lib.rs`.
2. **Path Traversal & Symlink Safety:** Any flaw in `.klir_trash` movement or permanent deletion logic that could inadvertently follow symbolic links outside the target volume or delete protected user data.
3. **Accessibility Service Scope:** Any unintended UI interaction or state leak within `KlirAccessibilityService.kt`.

## Reporting a Vulnerability

**Please do not report security vulnerabilities through public GitHub issues.**

Instead, please report them privately using GitHub's built-in advisory system:
1. Navigate to the [**Security Advisories**](https://github.com/joshitgupta-source/Klir/security/advisories/new) page of this repository.
2. Click **Report a vulnerability** and provide:
    * A clear description of the issue and affected component (`app` Kotlin code or `klir_core` Rust code).
    * Steps to reproduce or a proof-of-concept input/directory structure.
    * Device architecture (`arm64-v8a` or `x86_64`) and Android API level.

You will receive an acknowledgment within 48 hours, and we will coordinate a fix and release prior to public disclosure.