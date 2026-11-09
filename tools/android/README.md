# Android build helpers

- `fetch_deps.py` downloads pinned SDL2/gl4es sources into `build/android-deps/` and verifies SHA-256.
- `fetch_game_zip.py` downloads a user-supplied HTTPS game ZIP (or, with `--exe-only`, a raw supported `TMNT_EN.EXE` / ZIP containing only that EXE) into an isolated work directory, pins validated DNS answers, rejects unsafe redirects/ZIP entries, and locates the supported PC executable. It never prints the URL.
- `verify_game_pack.py` checks a generated `TMNT.tmnt.zip` (manifest order, exact file list, sizes, SHA-256, ARM/AArch64 ELF for the declared ABI; optionally `DT_NEEDED`/exported symbol with an NDK).
- `../make_android_files.py` translates the user's supported EXE and builds a private `.tmnt.zip` game pack for the clean APK. Pass `--abi arm64-v8a` or `--abi armeabi-v7a` to match the APK.
- `build_private_apk.py` builds an **embedded APK** with `libtmnt_code.so` and `image.bin` inside. Defaults to `arm64-v8a`; pass `--abi armeabi-v7a` for older 32-bit phones. With `--exe-only`, only `TMNT_EN.EXE` is needed at build time and the launcher records the seven media hashes during phone import; otherwise the build hashes the full installed game and import must match exactly.
- `embedded_game.py` creates/validates the embedded manifest and Gradle input directory.
- `verify_apk.py` verifies clean APKs and private embedded APKs; clean verification also creates a synthetic no-game pack.
- `test_android.py` tests pack manifest/order/hash metadata and ARM ELF validation without game data.
- `test_game_zip.py` tests URL/redirect/ZIP safety and pack verification with synthetic data only.
- `test_embedded.py` tests embedded manifest/staging rules with synthetic data only.
- `test_touch.c` tests the atomic touch/pause/perf contract on the host.
- `android/app/src/test/java` runs JVM regression tests against the pinned SDLActivity main-library rule,
  the GameActivity runtime-entry override and the diagnostic logcat filters; CI runs them for clean and embedded builds.

The Gradle project is `../../android`. The clean APK and the committed repository must never contain game code/data. The
embedded APK deliberately embeds game-derived code/image (but no full media data): in a private repository its CI artifact is
access-controlled; in a public repository it is publicly downloadable and the workflow requires explicit `confirm_public_artifact`
before building. Only publish it if you have the right to redistribute those game-derived files. Generated intermediates live
under ignored `build/` paths and must never be committed.
