"""Check 64-bit native libraries in an APK for 16 KiB PT_LOAD alignment.

Usage: python -I scripts/check_elf_alignment.py path/to/app.apk
Compressed JNI packaging is allowed; this checks ELF segments, not ZIP offsets.
"""
import struct
import sys
import zipfile


def check(path):
    failures = []
    count = 0
    with zipfile.ZipFile(path) as apk:
        for name in apk.namelist():
            if not name.startswith(("lib/arm64-v8a/", "lib/x86_64/")) or not name.endswith(".so"):
                continue
            data = apk.read(name)
            if data[:6] != b"\x7fELF\x02\x01":
                raise ValueError(f"Unexpected ELF format: {name}")
            offset = struct.unpack_from("<Q", data, 32)[0]
            size, entries = struct.unpack_from("<HH", data, 54)
            loads = 0
            for index in range(entries):
                at = offset + index * size
                if struct.unpack_from("<I", data, at)[0] != 1:
                    continue
                loads += 1
                file_offset, address = struct.unpack_from("<QQ", data, at + 8)
                alignment = struct.unpack_from("<Q", data, at + 48)[0]
                if alignment < 16384 or alignment & (alignment - 1) or (address - file_offset) % 16384:
                    failures.append(f"{name}: PT_LOAD {index} alignment={alignment:#x}")
            if not loads:
                failures.append(f"{name}: no PT_LOAD segments")
            count += 1
    if not count:
        raise ValueError("No 64-bit libraries found")
    for failure in failures:
        print(f"FAIL {failure}")
    print(f"{path}: {count} libraries checked, {len(failures)} failures")
    return not failures


if __name__ == "__main__":
    sys.exit(0 if check(sys.argv[1]) else 1)
