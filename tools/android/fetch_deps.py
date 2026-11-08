#!/usr/bin/env python3
"""Fetch pinned, unmodified SDL2/gl4es sources. No game files are downloaded."""
import hashlib
import json
import pathlib
import shutil
import tarfile
import tempfile
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[2]
DEST = ROOT / "build" / "android-deps"


def fetch(name, spec):
    dest = DEST / name
    stamp = dest / ".sha256"
    if stamp.is_file() and stamp.read_text().strip() == spec["sha256"]:
        return
    print("Fetching " + name + " (pinned source archive)…", flush=True)
    with tempfile.TemporaryDirectory(dir=DEST) as tmp:
        archive = pathlib.Path(tmp) / "source.tar.gz"
        request = urllib.request.Request(spec["url"], headers={"User-Agent": "TMNT-Android-Build/1"})
        with urllib.request.urlopen(request, timeout=120) as response, archive.open("wb") as out:
            shutil.copyfileobj(response, out)
        actual = hashlib.sha256(archive.read_bytes()).hexdigest()
        if actual != spec["sha256"]:
            raise RuntimeError("Checksum mismatch for " + name + ": " + actual)
        unpacked = pathlib.Path(tmp) / "source"
        with tarfile.open(archive, "r:gz") as tar:
            # These are pinned upstream archives, but never allow escapes or links outside the destination.
            for member in tar:
                parts = pathlib.PurePosixPath(member.name).parts
                if not member.isfile() and not member.isdir() and not member.issym():
                    raise RuntimeError("Unexpected archive member: " + member.name)
                if member.issym():
                    link_target = pathlib.PurePosixPath(member.name).parent / member.linkname
                    resolved = pathlib.PurePosixPath(*pathlib.PurePosixPath(*link_target.parts).parts[spec["strip_components"]:])
                    # PurePosixPath normalizes away '..'; check the resolved target is still inside the one archive root.
                    normalized = pathlib.PurePosixPath(str(resolved))
                    if member.linkname.startswith("/") or str(normalized).startswith("../") or normalized == pathlib.PurePosixPath(".."):
                        raise RuntimeError("Unsafe archive link: " + member.name)
                if ".." in parts or pathlib.PurePosixPath(member.name).is_absolute():
                    raise RuntimeError("Unsafe archive path: " + member.name)
            try:
                tar.extractall(unpacked, filter="data")
            except TypeError:  # Python 3.8: all member types and both path kinds were checked above.
                tar.extractall(unpacked)
        source = unpacked
        for _ in range(spec["strip_components"]):
            entries = list(source.iterdir())
            if len(entries) != 1 or not entries[0].is_dir():
                raise RuntimeError("Unexpected archive layout: " + name)
            source = entries[0]
        if dest.exists():
            shutil.rmtree(dest)
        shutil.move(str(source), str(dest))
        stamp.write_text(spec["sha256"] + "\n")


def main():
    DEST.mkdir(parents=True, exist_ok=True)
    specs = json.loads((ROOT / "tools/android/deps.json").read_text())
    for name, spec in specs.items():
        fetch(name, spec)
    licenses = DEST / "generated-assets" / "licenses"
    licenses.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(DEST / "SDL2/LICENSE.txt", licenses / "SDL2.txt")
    shutil.copyfile(DEST / "gl4es/LICENSE", licenses / "gl4es.txt")
    shutil.copyfile(ROOT / "LICENSE", licenses / "TMNT-port.txt")
    print("Native dependencies ready in " + str(DEST))


if __name__ == "__main__":
    main()
