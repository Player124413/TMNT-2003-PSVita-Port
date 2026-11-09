#!/usr/bin/env python3
"""Host tests for the URL-to-game-pack helpers. No network, Android SDK or game data required."""
import contextlib
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import stat
import struct
import sys
import tempfile
import unittest
from unittest import mock
import zipfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
sys.path.insert(0, str(ROOT / "tools/android"))
spec = importlib.util.spec_from_file_location("fetch_game_zip", ROOT / "tools/android/fetch_game_zip.py")
fetch = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fetch)
spec = importlib.util.spec_from_file_location("verify_game_pack", ROOT / "tools/android/verify_game_pack.py")
verify = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verify)
import make_android_files as android


class FakeResponse:
    def __init__(self, status, data=b"", headers=None):
        self.status = status
        self.headers = {k.lower(): v for k, v in (headers or {}).items()}
        self.data = io.BytesIO(data)
        self.closed = False

    def getheader(self, name, default=None):
        return self.headers.get(name.lower(), default)

    def read(self, amount):
        return self.data.read(amount)

    def close(self):
        self.closed = True


class FakeConnection:
    def __init__(self):
        self.sock = None
        self.closed = False

    def close(self):
        self.closed = True


class UrlTests(unittest.TestCase):
    def test_accepts_public_https(self):
        self.assertEqual(fetch.validate_url("https://example.com/game.zip?token=abc"), "https://example.com/game.zip?token=abc")

    def test_rejects_unsafe_urls(self):
        for url in [
            "",
            "http://example.com/game.zip",
            "https://user:pass@example.com/game.zip",
            "https://localhost/game.zip",
            "https://127.0.0.1/game.zip",
            "https://10.0.0.1/game.zip",
            "https://169.254.169.254/latest/meta-data",
            "https://[::1]/game.zip",
            "https://example.com/game.zip#fragment",
            "https://example.com/a b.zip",
            "https://example.com/a\\b.zip",
            "https://example.com/игра.zip",
            "https://example.com:99999/game.zip",
            "https://example.com:8443/game.zip",
            "https://[64:ff9b::7f00:1]/game.zip",
        ]:
            with self.subTest(url=url), self.assertRaises(fetch.GameArchiveError):
                fetch.validate_url(url)

    def test_ipv6_connection_host_is_unbracketed_for_tls(self):
        connection = fetch.PinnedHTTPSConnection("2606:4700:4700::1111", 443, [], 10, None)
        self.assertEqual(connection.host, "2606:4700:4700::1111")
        self.assertEqual(connection.port, 443)

    def test_dns_must_be_public(self):
        private = [(2, 1, 6, "", ("192.168.1.10", 443))]
        public = [(2, 1, 6, "", ("93.184.216.34", 443))]
        with mock.patch.object(fetch.socket, "getaddrinfo", return_value=private):
            with self.assertRaises(fetch.GameArchiveError):
                fetch.resolve_public("example.com", 443)
        with mock.patch.object(fetch.socket, "getaddrinfo", return_value=public):
            self.assertEqual(fetch.resolve_public("example.com", 443), public)
        nat64 = [(10, 1, 6, "", ("64:ff9b::7f00:1", 443, 0, 0))]
        with mock.patch.object(fetch.socket, "getaddrinfo", return_value=nat64):
            with self.assertRaises(fetch.GameArchiveError):
                fetch.resolve_public("example.com", 443)


class DownloadTests(unittest.TestCase):
    def test_download_follows_safe_redirect(self):
        first = FakeResponse(302, headers={"Location": "https://cdn.example.com/final.zip"})
        second = FakeResponse(200, b"zip-bytes", {"Content-Length": "9"})
        with tempfile.TemporaryDirectory() as tmp:
            destination = Path(tmp) / "game.zip"
            with mock.patch.object(fetch, "_request", side_effect=[(FakeConnection(), first), (FakeConnection(), second)]):
                size = fetch.download("https://example.com/game.zip", destination, 1024, 10)
            self.assertEqual(size, 9)
            self.assertEqual(destination.read_bytes(), b"zip-bytes")
            self.assertTrue(first.closed)
            self.assertTrue(second.closed)

    def test_download_rejects_unsafe_redirect(self):
        response = FakeResponse(302, headers={"Location": "http://example.com/game.zip"})
        with tempfile.TemporaryDirectory() as tmp:
            destination = Path(tmp) / "game.zip"
            with mock.patch.object(fetch, "_request", return_value=(FakeConnection(), response)):
                with self.assertRaises(fetch.GameArchiveError):
                    fetch.download("https://example.com/game.zip", destination, 1024, 10)
            self.assertFalse(destination.exists())

    def test_download_size_limit(self):
        response = FakeResponse(200, b"x" * 32, {"Content-Length": "32"})
        with tempfile.TemporaryDirectory() as tmp:
            destination = Path(tmp) / "game.zip"
            with mock.patch.object(fetch, "_request", return_value=(FakeConnection(), response)):
                with self.assertRaises(fetch.GameArchiveError):
                    fetch.download("https://example.com/game.zip", destination, 16, 10)
            self.assertFalse(destination.exists())
            self.assertFalse((Path(tmp) / "game.zip.part").exists())

    def test_download_rejects_redirect_loop(self):
        response = FakeResponse(302, headers={"Location": "https://example.com/again"})
        with tempfile.TemporaryDirectory() as tmp:
            with mock.patch.object(fetch, "_request", return_value=(FakeConnection(), response)):
                with self.assertRaises(fetch.GameArchiveError):
                    fetch.download("https://example.com/game.zip", Path(tmp) / "game.zip", 1024, 10)


class ZipTests(unittest.TestCase):
    def make_zip(self, path, entries):
        with zipfile.ZipFile(path, "w") as archive:
            for name, data, *attrs in entries:
                info = zipfile.ZipInfo(name)
                if attrs:
                    info.external_attr = attrs[0]
                archive.writestr(info, data)

    def test_extracts_safe_nested_archive(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            archive = root / "game.zip"
            self.make_zip(archive, [("app/", b""), ("app/TMNT_EN.EXE", b"exe"), ("app/data.bin", b"data")])
            destination = root / "game"
            size = fetch.extract_zip(archive, destination)
            self.assertEqual(size, 7)
            self.assertEqual((destination / "app/TMNT_EN.EXE").read_bytes(), b"exe")
            self.assertEqual(stat.S_IMODE((destination / "app/TMNT_EN.EXE").stat().st_mode), 0o600)

    def test_rejects_unsafe_archives(self):
        symlink = (stat.S_IFLNK | 0o777) << 16
        cases = [
            [("app/../evil", b"x")],
            [("/evil", b"x")],
            [("C:/evil", b"x")],
            [("app\\evil", b"x")],
            [("link", b"target", symlink)],
            [("A", b"x"), ("a", b"y")],
        ]
        for entries in cases:
            with self.subTest(entries=entries), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                archive = root / "bad.zip"
                self.make_zip(archive, entries)
                with self.assertRaises(fetch.GameArchiveError):
                    fetch.extract_zip(archive, root / "game")
                self.assertFalse((root / "game").exists())

    def test_rejects_encrypted_and_oversized(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            archive = root / "bad.zip"
            with zipfile.ZipFile(archive, "w") as z:
                z.writestr("file", b"x")
            data = bytearray(archive.read_bytes())
            for signature, offset in ((b"PK\x03\x04", 6), (b"PK\x01\x02", 8)):
                index = data.find(signature)
                self.assertNotEqual(index, -1)
                data[index + offset] |= 0x1
            archive.write_bytes(data)
            with self.assertRaises(fetch.GameArchiveError):
                fetch.extract_zip(archive, root / "game")
            self.make_zip(archive, [("file", b"x" * 64)])
            with self.assertRaises(fetch.GameArchiveError):
                fetch.extract_zip(archive, root / "game", max_expanded=32)

    def test_locates_supported_game(self):
        exe_data = b"fake supported exe"
        fake_sha1 = hashlib.sha1(exe_data).hexdigest()
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            app = root / "app"
            app.mkdir()
            (app / "TMNT_EN.EXE").write_bytes(exe_data)
            for name in fetch.vita.DATA_FILES:
                (app / name).write_bytes(b"data")
            with mock.patch.object(fetch.vita, "EXE_SHA1", fake_sha1):
                self.assertEqual(fetch.locate_game(root), app)
                (app / "bgm.afs").unlink()
                with self.assertRaises(fetch.GameArchiveError):
                    fetch.locate_game(root)


class ExeOnlySourceTests(unittest.TestCase):
    def args(self, work, exe_only=True):
        return mock.Mock(url_env="GAME_ZIP_URL", work_dir=work, max_download_mib=8, max_expanded_mib=8,
                         max_entries=100, timeout_seconds=10, exe_only=exe_only)

    def fake_download(self, data):
        def write(url, destination, max_bytes, timeout_seconds):
            destination.write_bytes(data)
            return len(data)
        return write

    def test_raw_supported_exe_needs_no_media(self):
        exe = b"MZfake supported exe"
        with tempfile.TemporaryDirectory() as tmp:
            work = Path(tmp) / "work"
            with mock.patch.dict(os.environ, {"GAME_ZIP_URL": "https://example.com/TMNT_EN.EXE"}), \
                 mock.patch.object(fetch.vita, "EXE_SHA1", hashlib.sha1(exe).hexdigest()), \
                 mock.patch.object(fetch, "SUPPORTED_EXE_BYTES", len(exe)), \
                 mock.patch.object(fetch, "download", side_effect=self.fake_download(exe)):
                report = fetch.run(self.args(work))
            self.assertEqual(report["source_kind"], "exe")
            self.assertFalse(report["data_validated"])
            self.assertEqual(report["game_dir"], ".")
            self.assertEqual((work / "game" / "TMNT_EN.EXE").read_bytes(), exe)
            self.assertFalse((work / "source.zip").exists())

    def test_raw_exe_rejects_wrong_size_or_hash(self):
        exe = b"MZfake supported exe"
        with tempfile.TemporaryDirectory() as tmp:
            work = Path(tmp) / "work"
            with mock.patch.dict(os.environ, {"GAME_ZIP_URL": "https://example.com/TMNT_EN.EXE"}), \
                 mock.patch.object(fetch.vita, "EXE_SHA1", hashlib.sha1(exe).hexdigest()), \
                 mock.patch.object(fetch, "SUPPORTED_EXE_BYTES", len(exe) + 1), \
                 mock.patch.object(fetch, "download", side_effect=self.fake_download(exe)):
                with self.assertRaises(fetch.GameArchiveError):
                    fetch.run(self.args(work))

    def test_exe_only_zip_must_contain_only_exe(self):
        exe = b"MZfake supported exe"
        good = io.BytesIO()
        with zipfile.ZipFile(good, "w") as z:
            z.writestr("TMNT_EN.EXE", exe)
        bad = io.BytesIO()
        with zipfile.ZipFile(bad, "w") as z:
            z.writestr("TMNT_EN.EXE", exe)
            z.writestr("bgm.afs", b"not needed")
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            with mock.patch.dict(os.environ, {"GAME_ZIP_URL": "https://example.com/source.zip"}), \
                 mock.patch.object(fetch.vita, "EXE_SHA1", hashlib.sha1(exe).hexdigest()), \
                 mock.patch.object(fetch, "download", side_effect=self.fake_download(good.getvalue())):
                report = fetch.run(self.args(root / "good"))
            self.assertEqual(report["source_kind"], "zip")
            self.assertFalse(report["data_validated"])
            with mock.patch.dict(os.environ, {"GAME_ZIP_URL": "https://example.com/source.zip"}), \
                 mock.patch.object(fetch.vita, "EXE_SHA1", hashlib.sha1(exe).hexdigest()), \
                 mock.patch.object(fetch, "download", side_effect=self.fake_download(bad.getvalue())):
                with self.assertRaises(fetch.GameArchiveError):
                    fetch.run(self.args(root / "bad"))


class PackVerifyTests(unittest.TestCase):
    def stage(self, root, abi="armeabi-v7a"):
        stage = root / "stage"
        stage.mkdir()
        for name in ["image.bin", "tmnt_code.so", *android.vita.DATA_FILES.values()]:
            (stage / name).write_bytes(name.encode() * 8)
        header = bytearray(64)
        header[:6] = b"\x7fELF" + bytes([1 if abi == "armeabi-v7a" else 2, 1])
        struct.pack_into("<H", header, 16, 3)
        struct.pack_into("<H", header, 18, 40 if abi == "armeabi-v7a" else 183)
        (stage / "tmnt_code.so").write_bytes(header)
        return stage

    def run_verify(self, pack):
        with mock.patch.object(sys, "argv", ["verify_game_pack.py", str(pack)]), contextlib.redirect_stdout(io.StringIO()):
            verify.main()

    def test_valid_synthetic_pack_passes(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            pack = root / "TMNT.tmnt.zip"
            android.write_pack(self.stage(root), pack)
            self.run_verify(pack)

    def test_arm64_synthetic_pack_passes(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            pack = root / "TMNT.tmnt.zip"
            android.write_pack(self.stage(root, "arm64-v8a"), pack, "arm64-v8a")
            self.run_verify(pack)

    def test_missing_entry_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            pack = root / "TMNT.tmnt.zip"
            android.write_pack(self.stage(root), pack)
            bad = root / "bad.tmnt.zip"
            with zipfile.ZipFile(pack) as src, zipfile.ZipFile(bad, "w") as dst:
                for info in src.infolist():
                    if info.filename != "voice.afs":
                        dst.writestr(info, src.read(info.filename))
            with self.assertRaises(SystemExit):
                self.run_verify(bad)

    def test_hash_mismatch_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            pack = root / "TMNT.tmnt.zip"
            android.write_pack(self.stage(root), pack)
            bad = root / "bad.tmnt.zip"
            with zipfile.ZipFile(pack) as src:
                manifest = json.loads(src.read("pack.json"))
                manifest["files"]["image.bin"]["sha256"] = "0" * 64
                with zipfile.ZipFile(bad, "w") as dst:
                    dst.writestr("pack.json", json.dumps(manifest))
                    for name in src.namelist()[1:]:
                        dst.writestr(name, src.read(name))
            with self.assertRaises(SystemExit):
                self.run_verify(bad)


if __name__ == "__main__":
    unittest.main()
