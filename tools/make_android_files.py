#!/usr/bin/env python3
"""Build a private Android game pack from the user's supported PC installation.

    python3 tools/make_android_files.py GAME_FOLDER OUTPUT.tmnt.zip \
        --abi arm64-v8a --ndk /path/to/Android/Sdk/ndk/27.2.12479018

Build the matching APK first: the module links against
build/android-runtime/<abi>/libtmnt_runtime.so. No executable or game data is included in the clean
APK or repository. The ZIP is for the user's own device and must match the APK ABI.
"""
import argparse
import concurrent.futures
import hashlib
import json
import os
from pathlib import Path
import shutil
import struct
import subprocess
import sys
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
import make_vita_files as vita

ABI = "armeabi-v7a"
FORMAT = 1
ABIS = {
    "armeabi-v7a": {"target": "armv7a-linux-androideabi26", "elf_class": 1, "machine": 40,
                    "arch_flags": ["-mthumb", "-mfpu=neon", "-mfloat-abi=softfp"]},
    "arm64-v8a": {"target": "aarch64-linux-android26", "elf_class": 2, "machine": 183,
                  "arch_flags": []},
}
BASE_CFLAGS = ["-O2", "-std=gnu11", "-fPIC", "-fvisibility=hidden", "-fno-strict-aliasing", "-fno-unroll-loops", "-w",
               "-DGUEST_GENERATED_STACK_GUARD=0", "-DGUEST_GPR_LOCAL=1", "-DGUEST_X87_LOCAL=1",
               "-DGUEST_MEMBASE_LOCAL=1", "-DGUEST_FLAGS_LOCAL=1"]


def abi_config(abi):
    if abi not in ABIS:
        die("unsupported Android ABI: " + str(abi) + " (use armeabi-v7a or arm64-v8a)")
    return ABIS[abi]


def cflags(abi=ABI):
    return [*BASE_CFLAGS, *abi_config(abi)["arch_flags"]]


CFLAGS = cflags(ABI)


def die(message):
    raise SystemExit("Error: " + message)


def digest(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def find_ndk(explicit):
    candidates = [explicit, os.environ.get("ANDROID_NDK_HOME"), os.environ.get("ANDROID_NDK_ROOT")]
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if sdk:
        candidates += [str(p) for p in sorted((Path(sdk) / "ndk").glob("*"), reverse=True)]
    candidates += [str(Path.home() / "Android/Sdk/ndk/27.2.12479018"),
                   str(Path.home() / "Library/Android/sdk/ndk/27.2.12479018")]
    for candidate in candidates:
        if not candidate:
            continue
        root = Path(candidate)
        prebuilt = root / "toolchains/llvm/prebuilt"
        if not prebuilt.is_dir():
            continue
        for host in ("linux-x86_64", "darwin-x86_64", "windows-x86_64"):
            clang = prebuilt / host / "bin" / ("clang.exe" if host.startswith("windows") else "clang")
            if clang.is_file():
                return clang
    die("Android NDK not found. Install NDK 27.2.12479018 and pass --ndk its directory.")


def validate_module(path, abi=ABI):
    cfg = abi_config(abi)
    with open(path, "rb") as f:
        data = f.read(64)
    if (len(data) < 52 or data[:4] != b"\x7fELF" or data[4] != cfg["elf_class"] or data[5] != 1
            or struct.unpack_from("<H", data, 16)[0] != 3 or struct.unpack_from("<H", data, 18)[0] != cfg["machine"]):
        die("the compiler did not produce an ELF shared module for " + abi)


def build_module(clang, runtime, destination, work, soname=None, abi=ABI):
    cfg = abi_config(abi)
    sources = sorted((ROOT / "recomp/out/c").glob("*.c")) + [ROOT / "rt/code_so_entry.c"]
    if len(sources) < 3:
        die("translated C is missing; run the full generator, not --code-only")
    objects = work / "obj"
    objects.mkdir()
    flags = [str(clang), "--target=" + cfg["target"], *cflags(abi), "-I", str(ROOT / "rt")]

    def compile_one(item):
        index, source = item
        obj = objects / ("%04d.o" % index)
        result = subprocess.run(flags + ["-c", str(source), "-o", str(obj)], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        if result.returncode:
            return "compiling %s failed:\n%s" % (source.name, result.stdout)
        return obj

    print("Compiling %d translated units for Android %s..." % (len(sources), abi), flush=True)
    objs = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=min(8, os.cpu_count() or 4)) as pool:
        for result in pool.map(compile_one, enumerate(sources)):
            if isinstance(result, str):
                die(result)
            objs.append(result)
    command = [str(clang), "--target=" + cfg["target"], "-shared", "-Wl,-z,max-page-size=16384",
               "-Wl,-z,now", "-Wl,--no-undefined"]
    if soname:
        command += ["-Wl,-soname," + soname]
    command += ["-o", str(destination), *map(str, objs), str(runtime), "-lm"]
    result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    if result.returncode:
        die("linking tmnt_code.so failed:\n" + result.stdout)
    validate_module(destination, abi)


def write_pack(folder, output, abi=ABI):
    abi_config(abi)
    names = ["image.bin", "tmnt_code.so", *vita.DATA_FILES.values()]
    manifest = {"format": FORMAT, "abi": abi, "files": {}}
    for name in names:
        path = folder / name
        if not path.is_file() or path.stat().st_size == 0:
            die("missing " + name)
        manifest["files"][name] = {"size": path.stat().st_size, "sha256": digest(path)}
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_suffix(output.suffix + ".tmp")
    with zipfile.ZipFile(temporary, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as z:
        # The importer deliberately requires this small manifest before any large entry.
        z.writestr("pack.json", json.dumps(manifest, indent=2, sort_keys=True))
        for name in names:
            z.write(folder / name, name)
    temporary.replace(output)
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("game", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--ndk", help="NDK root (r27d recommended)")
    parser.add_argument("--abi", choices=sorted(ABIS), default=ABI, help="APK/runtime ABI the pack is built for")
    parser.add_argument("--runtime", type=Path, help="matching libtmnt_runtime.so (default: build/android-runtime/<abi>/)")
    parser.add_argument("--code-only", action="store_true", help="recompile recomp/out/c; reuse image/data already next to OUTPUT")
    args = parser.parse_args()
    runtime = args.runtime or ROOT / "build/android-runtime" / args.abi / "libtmnt_runtime.so"
    if not runtime.is_file():
        die("build the Android APK first, or pass --runtime the matching libtmnt_runtime.so")
    clang = find_ndk(args.ndk)
    with tempfile.TemporaryDirectory(prefix="tmnt-android-") as tmp:
        work = Path(tmp)
        stage = work / "pack"
        stage.mkdir()
        if args.code_only:
            # This developer path expects a directory as OUTPUT, not a ZIP.
            args.output.mkdir(parents=True, exist_ok=True)
            build_module(clang, runtime.resolve(), args.output / "tmnt_code.so", work, abi=args.abi)
            print("Wrote", args.output / "tmnt_code.so")
            return
        exe = vita.find_file(str(args.game.resolve()), "TMNT_EN.EXE")
        if not exe or vita.sha1(exe) != vita.EXE_SHA1:
            die("supported TMNT_EN.EXE not found (SHA-1 " + vita.EXE_SHA1 + ")")
        folder = Path(exe).parent
        missing = [name for name in vita.DATA_FILES if not vita.find_file(str(folder), name)]
        if missing:
            die("missing next to the EXE: " + ", ".join(missing))
        vita.translate(exe)
        build_module(clang, runtime.resolve(), stage / "tmnt_code.so", work, abi=args.abi)
        shutil.copyfile(ROOT / "recomp/out/image.bin", stage / "image.bin")
        for source, dest in vita.DATA_FILES.items():
            shutil.copyfile(vita.find_file(str(folder), source), stage / dest)
        manifest = write_pack(stage, args.output.resolve(), args.abi)
    print("Wrote %s (%d MiB, %s). Import it with the matching TMNT launcher; keep it private." %
          (args.output, args.output.stat().st_size // 1048576, manifest["abi"]))


if __name__ == "__main__":
    main()
