# Android build helpers

- `fetch_deps.py` downloads pinned SDL2/gl4es sources into `build/android-deps/` and verifies SHA-256.
- `fetch_game_zip.py` downloads a user-supplied HTTPS game ZIP into an isolated work directory, pins validated DNS answers, rejects unsafe redirects/ZIP entries, and locates the supported PC installation. It never prints the URL.
- `verify_game_pack.py` checks a generated `TMNT.tmnt.zip` (manifest order, exact file list, sizes, SHA-256, ARM ELF; optionally `DT_NEEDED`/exported symbol with an NDK).
- `../make_android_files.py` translates the user's supported EXE and builds a private `.tmnt.zip` game pack.
- `test_android.py` tests pack manifest/order/hash metadata and ARM ELF validation without game data.
- `test_game_zip.py` tests URL/redirect/ZIP safety and pack verification with synthetic data only.
- `test_touch.c` tests the atomic touch/pause/perf contract on the host.

The Gradle project is `../../android`. Generated game code/data must never be committed or bundled into the APK.
