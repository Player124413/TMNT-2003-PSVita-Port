#!/usr/bin/env python3
"""Verify a generated TMNT.tmnt.zip before it is offered as a CI artifact."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import struct
import subprocess
import sys
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
import make_android_files as android

MAX_TOTAL = 3 * 1024 * 1024 * 1024
FILES = {"image.bin", "tmnt_code.so", *android.vita.DATA_FILES.values()}
CHUNK = 1 << 20


def fail(message):
    text = "Game pack verification failed: " + message
    if os.environ.get("GITHUB_ACTIONS") == "true":
        print("::error::" + text, flush=True)
    raise SystemExit(text)


def check(condition, message):
    if not condition:
        fail(message)


def stream_hash(archive, name):
    h = hashlib.sha256()
    size = 0
    with archive.open(name) as f:
        for block in iter(lambda: f.read(CHUNK), b""):
            size += len(block)
            h.update(block)
    return size, h.hexdigest()


def file_hash(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(CHUNK), b""):
            h.update(block)
    return h.hexdigest()


def tool_output(command):
    return subprocess.run(command, check=True, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT).stdout


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("pack", type=Path)
    parser.add_argument("--ndk", help="NDK root; enables native module checks")
    parser.add_argument("--readelf")
    parser.add_argument("--nm")
    args = parser.parse_args()
    path = args.pack.resolve()
    check(path.is_file(), "pack does not exist")
    try:
        archive = zipfile.ZipFile(path)
    except (zipfile.BadZipFile, OSError):
        fail("pack is not a readable ZIP")
    with archive:
        infos = archive.infolist()
        names = [info.filename for info in infos]
        check(names and names[0] == "pack.json", "pack.json must be the first ZIP entry")
        check(len(names) == len(set(names)) == 10, "pack must contain exactly ten unique entries")
        check(not any(info.is_dir() for info in infos), "pack must not contain directories")
        check(set(names) == FILES | {"pack.json"}, "unexpected or missing pack entries")
        manifest_info = archive.getinfo("pack.json")
        check(manifest_info.file_size <= 65536, "manifest too large")
        try:
            manifest = json.loads(archive.read("pack.json").decode("utf-8"))
        except (ValueError, UnicodeError):
            fail("manifest is not valid UTF-8 JSON")
        check(isinstance(manifest, dict), "manifest is not a JSON object")
        check(type(manifest.get("format")) is int and manifest.get("format") == 1, "wrong pack format")
        check(manifest.get("abi") == "armeabi-v7a", "wrong pack ABI")
        files = manifest.get("files")
        check(isinstance(files, dict) and set(files) == FILES, "manifest file list mismatch")
        total = 0
        for name in sorted(FILES):
            meta = files[name]
            check(isinstance(meta, dict), "invalid metadata for " + name)
            size = meta.get("size")
            digest = meta.get("sha256")
            check(type(size) is int and 0 < size <= MAX_TOTAL, "invalid size for " + name)
            check(isinstance(digest, str) and re.fullmatch(r"[0-9a-f]{64}", digest), "invalid SHA-256 for " + name)
            total += size
            check(total <= MAX_TOTAL, "pack exceeds 3 GiB")
            actual_size, actual_hash = stream_hash(archive, name)
            check(actual_size == size, "size mismatch for " + name)
            check(actual_hash == digest, "SHA-256 mismatch for " + name)
        with tempfile.TemporaryDirectory(prefix="tmnt-pack-verify-") as tmp:
            module = Path(tmp) / "tmnt_code.so"
            with archive.open("tmnt_code.so") as src, module.open("wb") as dst:
                header = src.read(52)
                check(len(header) >= 52 and header[:6] == b"\x7fELF\x01\x01", "tmnt_code.so is not ELF32 little-endian")
                check(struct.unpack_from("<H", header, 16)[0] == 3, "tmnt_code.so is not ET_DYN")
                check(struct.unpack_from("<H", header, 18)[0] == 40, "tmnt_code.so is not ARM")
                dst.write(header)
                for block in iter(lambda: src.read(CHUNK), b""):
                    dst.write(block)
            if args.ndk or args.readelf or args.nm:
                clang = android.find_ndk(args.ndk) if args.ndk else None
                readelf = args.readelf
                nm = args.nm
                if clang:
                    directory = Path(clang).parent
                    readelf = readelf or str(directory / "llvm-readelf")
                    nm = nm or str(directory / "llvm-nm")
                check(readelf and Path(readelf).is_file(), "llvm-readelf not found")
                check(nm and Path(nm).is_file(), "llvm-nm not found")
                dynamic = tool_output([readelf, "-d", str(module)])
                check("libtmnt_runtime.so" in dynamic, "module does not depend on libtmnt_runtime.so")
                symbols = tool_output([nm, "-D", str(module)])
                check(re.search(r"\btmnt_code\b", symbols), "module does not export tmnt_code")
    print("OK: game pack manifest, hashes, sizes and ARM module verified.")
    print("Pack SHA-256:", file_hash(path))
    print("Pack size:", path.stat().st_size)


if __name__ == "__main__":
    main()
