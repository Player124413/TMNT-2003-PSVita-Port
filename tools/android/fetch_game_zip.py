#!/usr/bin/env python3
"""Download a user-supplied PC-game ZIP and extract it safely for Android recompilation.

The URL is read from an environment variable and is never printed. Only HTTPS URLs are
accepted. DNS answers are validated and pinned for the TLS connection, redirects are
bounded, and the archive is size-limited and validated before extraction.
"""
import argparse
import hashlib
import http.client
import ipaddress
import json
import os
from pathlib import Path, PurePosixPath
import re
import socket
import ssl
import stat
import sys
import time
import urllib.parse
import zipfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
import make_vita_files as vita

CHUNK = 1 << 20
DEFAULT_MAX_DOWNLOAD_MIB = 4096
DEFAULT_MAX_EXPANDED_MIB = 6144
DEFAULT_MAX_ENTRIES = 50000
DEFAULT_TIMEOUT_SECONDS = 1800
MAX_REDIRECTS = 5
REDIRECT_CODES = {301, 302, 303, 307, 308}


class GameArchiveError(Exception):
    """An error whose message is safe to show in public CI logs."""


def file_digest(path, algorithm="sha256"):
    h = hashlib.new(algorithm)
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(CHUNK), b""):
            h.update(block)
    return h.hexdigest()


def validate_url(url):
    if not url or len(url) > 8192:
        raise GameArchiveError("The game ZIP URL is empty or too long")
    if not url.isascii() or any(ch.isspace() or ord(ch) < 32 or ord(ch) == 127 for ch in url):
        raise GameArchiveError("The game ZIP URL must be ASCII without whitespace or control characters")
    if "\\" in url:
        raise GameArchiveError("The game ZIP URL must not contain backslashes")
    try:
        parts = urllib.parse.urlsplit(url)
        port = parts.port
    except ValueError:
        raise GameArchiveError("The game ZIP URL is malformed") from None
    if parts.scheme.lower() != "https":
        raise GameArchiveError("Only HTTPS game ZIP URLs are supported")
    if not parts.hostname or parts.username is not None or parts.password is not None:
        raise GameArchiveError("The game ZIP URL must have a host and must not contain credentials")
    if parts.fragment:
        raise GameArchiveError("The game ZIP URL must not contain a fragment")
    if port not in (None, 443):
        raise GameArchiveError("Only HTTPS URLs on the default port 443 are supported")
    host = parts.hostname.rstrip(".").lower()
    if not host or "%" in host or host == "localhost" or host.endswith((".localhost", ".local", ".internal")):
        raise GameArchiveError("Local game ZIP hosts are not allowed")
    try:
        address = ipaddress.ip_address(host)
    except ValueError:
        pass
    else:
        if not is_public_address(address):
            raise GameArchiveError("The game ZIP host must be a public Internet address")
    return url


NAT64_PREFIXES = (ipaddress.ip_network("64:ff9b::/96"), ipaddress.ip_network("64:ff9b:1::/48"))


def is_public_address(address):
    if isinstance(address, ipaddress.IPv6Address):
        # NAT64 can embed an IPv4 target that ipaddress does not classify for the IPv6 form.
        if any(address in prefix for prefix in NAT64_PREFIXES):
            return False
    return address.is_global


def resolve_public(host, port):
    """Resolve a host and reject private/reserved answers. The result is pinned for connect()."""
    try:
        infos = socket.getaddrinfo(host, port, type=socket.SOCK_STREAM)
    except (OSError, UnicodeError):
        raise GameArchiveError("The game ZIP host could not be resolved") from None
    if not infos:
        raise GameArchiveError("The game ZIP host could not be resolved")
    for info in infos:
        value = info[4][0].split("%", 1)[0]
        try:
            address = ipaddress.ip_address(value)
        except ValueError:
            raise GameArchiveError("The game ZIP host returned an invalid address") from None
        if not is_public_address(address):
            raise GameArchiveError("The game ZIP host must not resolve to a private or reserved address")
    return infos


class PinnedHTTPSConnection(http.client.HTTPSConnection):
    """HTTPS connection that only connects to the validated DNS answers, with normal TLS checks."""

    def __init__(self, host, port, addresses, timeout, context):
        # urlsplit.hostname is already unbracketed for IPv6; http.client adds brackets to Host when needed.
        super().__init__(host, port=port, timeout=timeout, context=context)
        self._pinned_addresses = addresses

    def connect(self):
        last = None
        for family, kind, proto, _, sockaddr in self._pinned_addresses:
            sock = socket.socket(family, kind, proto)
            sock.settimeout(self.timeout)
            try:
                sock.connect(sockaddr)
            except OSError as exc:
                sock.close()
                last = exc
                continue
            try:
                self.sock = self._context.wrap_socket(sock, server_hostname=self.host)
            except BaseException:
                sock.close()
                raise
            return
        raise OSError("could not connect to the game ZIP host") from last


def _request(url, timeout, context):
    parts = urllib.parse.urlsplit(url)
    port = parts.port or 443
    addresses = resolve_public(parts.hostname, port)
    connection = PinnedHTTPSConnection(parts.hostname, port, addresses, timeout, context)
    target = urllib.parse.urlunsplit(("", "", parts.path or "/", parts.query, ""))
    connection.request(
        "GET",
        target,
        headers={
            "User-Agent": "TMNT-Android-Pack/1",
            "Accept": "application/zip, application/octet-stream;q=0.9, */*;q=0.1",
            "Connection": "close",
        },
    )
    return connection, connection.getresponse()


def download(url, destination, max_bytes, timeout_seconds):
    current = validate_url(url)
    context = ssl.create_default_context()
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    deadline = time.monotonic() + timeout_seconds
    redirects = 0
    total = 0
    temporary = destination.with_name(destination.name + ".part")
    try:
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise GameArchiveError("The game ZIP download timed out")
            connection = None
            response = None
            try:
                try:
                    connection, response = _request(current, min(60.0, remaining), context)
                except (OSError, http.client.HTTPException):
                    raise GameArchiveError("The game ZIP download failed (connection or TLS error)") from None
                status = response.status
                if status in REDIRECT_CODES:
                    location = response.getheader("Location")
                    if not location:
                        raise GameArchiveError("The game ZIP redirect had no Location header")
                    redirects += 1
                    if redirects > MAX_REDIRECTS:
                        raise GameArchiveError("The game ZIP URL redirected too many times")
                    try:
                        current = validate_url(urllib.parse.urljoin(current, location))
                    except ValueError:
                        raise GameArchiveError("The game ZIP redirect was malformed") from None
                    continue
                if status != 200:
                    raise GameArchiveError("The game ZIP download failed with HTTP %s" % status)
                length = response.getheader("Content-Length")
                declared = None
                if length:
                    try:
                        declared = int(length)
                    except ValueError:
                        raise GameArchiveError("The game ZIP server returned an invalid Content-Length") from None
                    if declared < 0:
                        raise GameArchiveError("The game ZIP server returned an invalid Content-Length")
                    if declared > max_bytes:
                        raise GameArchiveError("The game ZIP exceeds the download size limit")
                with temporary.open("wb") as out:
                    while True:
                        remaining = deadline - time.monotonic()
                        if remaining <= 0:
                            raise GameArchiveError("The game ZIP download timed out")
                        if connection.sock is not None:
                            connection.sock.settimeout(min(60.0, remaining))
                        try:
                            block = response.read(CHUNK)
                        except (OSError, http.client.HTTPException):
                            raise GameArchiveError("The game ZIP download interrupted") from None
                        if not block:
                            break
                        total += len(block)
                        if total > max_bytes:
                            raise GameArchiveError("The game ZIP exceeds the download size limit")
                        out.write(block)
                if declared is not None and total != declared:
                    raise GameArchiveError("The game ZIP download interrupted")
                break
            finally:
                if response is not None:
                    response.close()
                if connection is not None:
                    connection.close()
        if total == 0:
            raise GameArchiveError("The game ZIP download was empty")
        temporary.replace(destination)
        return total
    finally:
        temporary.unlink(missing_ok=True)


def _safe_member_name(name):
    if not name or len(name) > 4096:
        return None
    if "\\" in name or name.startswith("/") or re.match(r"^[A-Za-z]:", name):
        return None
    if any(ord(ch) < 32 or ord(ch) == 127 for ch in name):
        return None
    parts = name.split("/")
    directory = name.endswith("/")
    components = parts[:-1] if directory else parts
    if not components or any(part in ("", ".", "..") or ":" in part for part in components):
        return None
    return "/".join(components) + ("/" if directory else "")


def validate_zip(archive, max_entries, max_expanded):
    try:
        infos = archive.infolist()
    except (zipfile.BadZipFile, OSError):
        raise GameArchiveError("The downloaded file is not a readable ZIP archive") from None
    if not infos:
        raise GameArchiveError("The game ZIP is empty")
    if len(infos) > max_entries:
        raise GameArchiveError("The game ZIP contains too many entries")
    seen = set()
    total = 0
    for info in infos:
        name = _safe_member_name(info.filename)
        if name is None or name != info.filename:
            raise GameArchiveError("The game ZIP contains an unsafe path")
        key = name.rstrip("/").casefold()
        if key in seen:
            raise GameArchiveError("The game ZIP contains duplicate or case-colliding paths")
        seen.add(key)
        if info.flag_bits & 0x1:
            raise GameArchiveError("Encrypted game ZIP entries are not supported")
        mode = info.external_attr >> 16
        kind = stat.S_IFMT(mode)
        if kind not in (0, stat.S_IFREG, stat.S_IFDIR):
            raise GameArchiveError("The game ZIP contains a link or special file")
        if info.is_dir() and (kind == stat.S_IFREG or info.file_size != 0):
            raise GameArchiveError("The game ZIP has an invalid directory entry")
        if not info.is_dir() and kind == stat.S_IFDIR:
            raise GameArchiveError("The game ZIP has an invalid file entry")
        if info.file_size < 0 or info.file_size > max_expanded:
            raise GameArchiveError("A game ZIP entry exceeds the expanded size limit")
        total += info.file_size
        if total > max_expanded:
            raise GameArchiveError("The game ZIP exceeds the expanded size limit")
    return infos, total


def extract_zip(archive_path, destination, max_entries=DEFAULT_MAX_ENTRIES, max_expanded=DEFAULT_MAX_EXPANDED_MIB * (1 << 20)):
    if destination.exists():
        raise GameArchiveError("The game extraction directory already exists")
    try:
        archive = zipfile.ZipFile(archive_path)
    except (zipfile.BadZipFile, OSError):
        raise GameArchiveError("The downloaded file is not a readable ZIP archive") from None
    with archive:
        infos, expected = validate_zip(archive, max_entries, max_expanded)
        destination.mkdir(parents=True, mode=0o700)
        destination.chmod(0o700)
        root = destination.resolve()
        written = 0
        for info in infos:
            target = root.joinpath(*PurePosixPath(info.filename).parts)
            resolved = target.resolve()
            if resolved != root and root not in resolved.parents:
                raise GameArchiveError("The game ZIP contains an unsafe path")
            if info.is_dir():
                target.mkdir(parents=True, exist_ok=True, mode=0o700)
                continue
            target.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
            try:
                with archive.open(info) as src, target.open("xb") as out:
                    target.chmod(0o600)
                    while True:
                        block = src.read(CHUNK)
                        if not block:
                            break
                        written += len(block)
                        if written > max_expanded:
                            raise GameArchiveError("The game ZIP exceeds the expanded size limit")
                        out.write(block)
            except (zipfile.BadZipFile, NotImplementedError, OSError):
                raise GameArchiveError("A game ZIP entry failed CRC, compression, or read validation") from None
        if written != expected:
            raise GameArchiveError("The game ZIP expanded size did not match its directory")
    return written


def _named_files(root, name):
    matches = []
    wanted = name.casefold()
    for directory, _, files in os.walk(root):
        for filename in files:
            if filename.casefold() == wanted:
                matches.append(Path(directory) / filename)
    return sorted(matches, key=lambda p: (len(p.relative_to(root).parts), str(p).casefold()))


def locate_game(root):
    candidates = _named_files(root, "TMNT_EN.EXE")
    supported = [path for path in candidates if file_digest(path, "sha1") == vita.EXE_SHA1]
    if not supported:
        raise GameArchiveError("Supported TMNT_EN.EXE not found (SHA-1 " + vita.EXE_SHA1 + ")")
    if len(supported) != 1:
        raise GameArchiveError("The game ZIP contains multiple supported TMNT_EN.EXE files; keep only one")
    exe = supported[0]
    missing = [name for name in vita.DATA_FILES if not _named_files(exe.parent, name)]
    if missing:
        raise GameArchiveError("Missing game files below the EXE directory: " + ", ".join(missing))
    empty = [name for name in vita.DATA_FILES if _named_files(exe.parent, name)[0].stat().st_size == 0]
    if empty:
        raise GameArchiveError("Empty game files below the EXE directory: " + ", ".join(empty))
    return exe.parent


def run(args):
    url = os.environ.get(args.url_env, "").strip()
    validate_url(url)
    work = args.work_dir.resolve()
    if work.exists():
        raise GameArchiveError("The game work directory already exists; remove it before retrying")
    work.mkdir(parents=True, mode=0o700)
    work.chmod(0o700)
    archive = work / "source.zip"
    game = work / "game"
    print("Downloading the user-supplied game ZIP (URL withheld)...", flush=True)
    size = download(url, archive, args.max_download_mib * (1 << 20), args.timeout_seconds)
    archive_hash = file_digest(archive)
    print("Downloaded %d MiB; SHA-256 %s" % (size // (1 << 20), archive_hash), flush=True)
    expanded = extract_zip(archive, game, args.max_entries, args.max_expanded_mib * (1 << 20))
    folder = locate_game(game)
    relative = folder.relative_to(game).as_posix()
    report = {
        "archive_sha256": archive_hash,
        "archive_bytes": size,
        "expanded_bytes": expanded,
        "game_dir": relative,
        "exe_sha1": vita.EXE_SHA1,
    }
    (work / "report.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print("Extracted and validated the installed game; directory: " + relative, flush=True)
    return report


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url-env", default="GAME_ZIP_URL", help="environment variable containing the HTTPS ZIP URL")
    parser.add_argument("--work-dir", type=Path, required=True)
    parser.add_argument("--max-download-mib", type=int, default=DEFAULT_MAX_DOWNLOAD_MIB)
    parser.add_argument("--max-expanded-mib", type=int, default=DEFAULT_MAX_EXPANDED_MIB)
    parser.add_argument("--max-entries", type=int, default=DEFAULT_MAX_ENTRIES)
    parser.add_argument("--timeout-seconds", type=int, default=DEFAULT_TIMEOUT_SECONDS)
    args = parser.parse_args(argv)
    if min(args.max_download_mib, args.max_expanded_mib, args.max_entries, args.timeout_seconds) <= 0:
        print("Error: archive limits must be positive", file=sys.stderr)
        return 2
    try:
        run(args)
        return 0
    except GameArchiveError as exc:
        print("Error: " + str(exc), file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print("Error: game archive processing interrupted", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
