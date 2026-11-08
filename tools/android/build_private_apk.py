#!/usr/bin/env python3
"""Build a private Android APK with tmnt_code.so and image.bin embedded.

    python3 tools/android/build_private_apk.py GAME_FOLDER TMNT-private.apk \
        --ndk /path/to/Android/Sdk/ndk/27.2.12479018

The APK is personalized from the user's supported TMNT_EN.EXE. After installing it,
the launcher imports only the seven data files from the same installed PC game
(either a ZIP or a folder copied to the phone). No game pack is needed on the phone.

Keep the APK private. This script never uploads anything and writes game-derived
intermediates only under build/, which is ignored by Git.
"""
import argparse
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
sys.path.insert(0, str(ROOT / "tools/android"))
import make_android_files as android
import make_vita_files as vita
import embedded_game as embedded


def die(message):
    raise SystemExit("Error: " + message)


def run(command, cwd=ROOT):
    print("+", " ".join(map(str, command)), flush=True)
    subprocess.run(list(map(str, command)), cwd=str(cwd), check=True)


def gradle(*extra):
    wrapper = ROOT / "android" / ("gradlew.bat" if os.name == "nt" else "gradlew")
    if not wrapper.is_file():
        die("Gradle wrapper is missing: " + str(wrapper))
    run([wrapper, "--no-daemon", "--stacktrace", *extra], cwd=ROOT / "android")


def apk_path(private):
    base = ROOT / "build/android-private-app" if private else ROOT / "android/app/build"
    return base / "outputs/apk/debug/app-debug.apk"


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def runtime_from_apk(apk, destination):
    with zipfile.ZipFile(apk) as z:
        name = "lib/armeabi-v7a/libtmnt_runtime.so"
        if name not in z.namelist():
            die("APK does not contain " + name)
        with z.open(name) as src, open(destination, "wb") as dst:
            shutil.copyfileobj(src, dst, 1 << 20)
    return destination


def validate_game(folder):
    exe = vita.find_file(str(folder), "TMNT_EN.EXE")
    if not exe or vita.sha1(exe) != vita.EXE_SHA1:
        die("supported TMNT_EN.EXE not found (SHA-1 " + vita.EXE_SHA1 + ")")
    exe_folder = Path(exe).parent
    missing = [name for name in vita.DATA_FILES if not vita.find_file(str(exe_folder), name)]
    if missing:
        die("missing next to the EXE: " + ", ".join(missing))
    return Path(exe), exe_folder


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("game", type=Path, help="installed PC game folder containing TMNT_EN.EXE")
    parser.add_argument("output", type=Path, help="destination private APK")
    parser.add_argument("--ndk", help="NDK root (r27d recommended)")
    parser.add_argument("--work-dir", type=Path, default=ROOT / "build/android-private-game",
                        help="Gradle input directory for embedded code/image (default: build/android-private-game)")
    parser.add_argument("--skip-verify", action="store_true", help="skip tools/android/verify_apk.py (not recommended)")
    args = parser.parse_args(argv)

    game = args.game.resolve()
    output = args.output.resolve()
    work_dir = args.work_dir.resolve()
    if not game.is_dir():
        die("game folder does not exist: " + str(game))
    if output.suffix.lower() != ".apk":
        die("output must end in .apk")
    if work_dir == ROOT or ROOT not in work_dir.parents:
        die("work-dir must be inside the repository (normally under build/)")
    exe, exe_folder = validate_game(game)
    clang = android.find_ndk(args.ndk)

    print("Building the clean runtime first; the private module links against it.", flush=True)
    gradle(":app:assembleDebug")
    runtime = ROOT / "build/android-runtime/armeabi-v7a/libtmnt_runtime.so"
    if not runtime.is_file():
        die("runtime was not produced: " + str(runtime))

    shutil.rmtree(ROOT / "recomp/out", ignore_errors=True)
    vita.translate(str(exe))
    image = ROOT / "recomp/out/image.bin"
    if not image.is_file():
        die("translator did not produce image.bin")

    shutil.rmtree(work_dir, ignore_errors=True)
    work_dir.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="tmnt-private-module-", dir=work_dir) as tmp:
        temp = Path(tmp)
        module = temp / "libtmnt_code.so"
        android.build_module(clang, runtime, module, temp, soname="libtmnt_code.so")
        manifest = embedded.make_manifest(module, image, runtime, vita.EXE_SHA1, exe_folder)
        embedded.write_directory(work_dir, module, image, manifest)

    print("Packaging the private APK with embedded code and image.", flush=True)
    gradle("-PtmntEmbeddedDir=" + str(work_dir), ":app:assembleDebug")
    final_apk = apk_path(True)
    if not final_apk.is_file():
        die("private APK was not produced: " + str(final_apk))

    # A changed build directory can rebuild the runtime. Relink once against the exact packaged runtime.
    packaged_runtime = runtime_from_apk(final_apk, work_dir / "libtmnt_runtime.packaged.so")
    if sha256(packaged_runtime) != manifest["runtime_sha256"]:
        print("Runtime changed during private packaging; relinking the embedded module against the packaged runtime.", flush=True)
        with tempfile.TemporaryDirectory(prefix="tmnt-private-relink-", dir=work_dir) as tmp:
            temp = Path(tmp)
            module = temp / "libtmnt_code.so"
            android.build_module(clang, packaged_runtime, module, temp, soname="libtmnt_code.so")
            manifest = embedded.make_manifest(module, image, packaged_runtime, vita.EXE_SHA1, exe_folder)
            embedded.write_directory(work_dir, module, image, manifest)
        gradle("-PtmntEmbeddedDir=" + str(work_dir), ":app:assembleDebug")
        final_apk = apk_path(True)
        packaged_runtime = runtime_from_apk(final_apk, work_dir / "libtmnt_runtime.packaged.so")
        if sha256(packaged_runtime) != manifest["runtime_sha256"]:
            die("packaged runtime does not match the embedded module after relink")

    if not args.skip_verify:
        command = [sys.executable, ROOT / "tools/android/verify_apk.py", final_apk]
        if args.ndk:
            command += ["--ndk", args.ndk]
        run(command)

    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_suffix(output.suffix + ".tmp")
    shutil.copyfile(final_apk, temporary)
    temporary.replace(output)
    checksum = sha256(output)
    output.with_suffix(output.suffix + ".sha256").write_text(checksum + "  " + output.name + "\n", encoding="utf-8")
    print("Wrote private APK:", output)
    print("SHA-256:", checksum)
    print("Install it, then import a ZIP or folder of the same installed PC game. Keep the APK private.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
