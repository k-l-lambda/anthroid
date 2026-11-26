"""Rebuild the tracked ARM64 VPN library from a separately checked-out upstream.

Check out https://github.com/kaozaza2/TunProxy at the pinned revision below.
Usage: python -I scripts/rebuild_tun2http.py UPSTREAM_CHECKOUT NDK_DIRECTORY
Requires NDK r26d on Windows (or an equivalent clang toolchain).
Does not execute upstream build scripts. Output replaces the tracked prebuilt.
"""
import pathlib
import subprocess
import sys
import tempfile

REVISION = "d2bd5a191fdc8708bec66cf28bef9d72b7fd3a0a"


def main():
    upstream, ndk = map(pathlib.Path, sys.argv[1:])
    actual = subprocess.check_output(["git", "-C", str(upstream), "rev-parse", "HEAD"], text=True).strip()
    if actual != REVISION:
        raise ValueError(f"Expected upstream {REVISION}, got {actual}")
    if subprocess.check_output(["git", "-C", str(upstream), "status", "--porcelain", "--untracked-files=no"], text=True).strip():
        raise ValueError("Upstream checkout must be clean")
    host = "windows-x86_64" if sys.platform == "win32" else "linux-x86_64"
    compiler = ndk / "toolchains/llvm/prebuilt" / host / "bin" / ("aarch64-linux-android21-clang.cmd" if sys.platform == "win32" else "aarch64-linux-android21-clang")
    source = upstream / "app/src/main/cpp"
    output = pathlib.Path(__file__).resolve().parents[1] / "app/src/main/jniLibs/arm64-v8a/libtun2http.so"
    with tempfile.TemporaryDirectory(prefix="tun2http-build-") as temporary:
        temporary = pathlib.Path(temporary)
        # Upstream calls next_header before its declaration; provide its exact prototype.
        prototype = temporary / "prototype.h"
        prototype.write_text("#include <stddef.h>\nint next_header(const char **data, size_t *len);\n", encoding="utf-8")
        library = temporary / "libtun2http.so"
        subprocess.run([str(compiler), "-shared", "-fPIC", "-O2", "-include", str(prototype),
                        "-Wl,-soname,libtun2http.so", "-Wl,-z,max-page-size=16384",
                        "-Wl,-z,common-page-size=16384", *map(str, sorted(source.glob("*.c"))),
                        "-llog", "-o", str(library)], check=True)
        output.write_bytes(library.read_bytes())
    print(f"Rebuilt {output}")


if __name__ == "__main__":
    main()
