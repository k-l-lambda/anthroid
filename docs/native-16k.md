# Native 16 KB page support

The native modules built from source explicitly link with both
`-z max-page-size=16384` and `-z common-page-size=16384`. The existing NDK r22
configuration remains supported for those modules. Barcode Scanning 17.3.0 and
CameraX 1.4.2 supply aligned prebuilt libraries. CameraX requires compile SDK 34;
target SDK is intentionally unchanged.

The tracked ARM64 `libtun2http.so` was rebuilt with NDK r26d from
https://github.com/kaozaza2/TunProxy at
`d2bd5a191fdc8708bec66cf28bef9d72b7fd3a0a`. Its JNI package/signatures match
`tun.proxy.service.Tun2HttpVpnService`. To reproduce from a separate checkout:

```sh
python -I scripts/rebuild_tun2http.py /path/to/TunProxy /path/to/android-ndk-r26d
```

The helper invokes clang directly, adds the missing upstream `next_header`
prototype through a forced header, and replaces the existing tracked prebuilt.
No ELF header values are patched to disguise incompatible segment layouts.

After building, check **both** APK variants:

```sh
python -I scripts/check_elf_alignment.py app/build/outputs/apk/debug/anthroid-app_apt-android-7-debug_arm64-v8a.apk
python -I scripts/check_elf_alignment.py app/build/outputs/apk/debug/anthroid-app_apt-android-7-debug_universal.apk
```

JNI libraries retain compressed legacy packaging, so the loader extracts them;
uncompressed ZIP-entry alignment is not required for this packaging mode.
The validator checks all packaged 64-bit ELF PT_LOAD segments for alignment and
offset/address congruence. It does not certify the extracted Termux bootstrap
packages or replace runtime testing on a real 16 KB device/emulator.

2026-11-26 validation: both APKs passed with 12 ARM64 shared libraries and zero
alignment failures. The connected PLZ110 reports a 4096-byte page size: installation,
startup and real SSH tmux discovery passed there, but 16 KB runtime execution and
VPN/QR/speech functional regressions remain to be tested.
