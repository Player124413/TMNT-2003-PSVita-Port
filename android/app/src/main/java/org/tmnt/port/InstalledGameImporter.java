package org.tmnt.port;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.StatFs;
import android.provider.DocumentsContract;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Embedded-APK import: copy only the seven PC data files. Hashed builds match the build; EXE-only builds hash on import. */
public final class InstalledGameImporter {
    private static final int MAX_ENTRIES = 50000;
    private static final int MAX_DEPTH = 16;
    private static final long MiB = 1024L * 1024L;
    private InstalledGameImporter() {}

    public static void importZip(Context c, Uri uri, AtomicBoolean cancelled, PackImporter.Progress progress) throws Exception {
        requireEmbedded();
        JSONObject data = EmbeddedGame.data(c);
        boolean deferred = EmbeddedGame.DATA_ON_IMPORT.equals(EmbeddedGame.dataValidation(c));
        checkSpace(c);
        File staging = staging(c);
        Set<String> done = new HashSet<>();
        JSONObject files = new JSONObject();
        long[] total = {0};
        try {
            try (InputStream raw = c.getContentResolver().openInputStream(uri);
                 ZipInputStream zip = new ZipInputStream(new BufferedInputStream(raw, 1 << 16))) {
                if (raw == null) throw new java.io.IOException("Cannot open selected ZIP");
                ZipEntry entry; int count = 0;
                while ((entry = zip.getNextEntry()) != null) {
                    if (cancelled.get()) throw new InterruptedException("Import cancelled");
                    if (++count > MAX_ENTRIES) throw new IllegalArgumentException("ZIP contains too many entries");
                    if (!entry.isDirectory()) {
                        String name = entry.getName().replace('\\', '/');
                        int slash = name.lastIndexOf('/');
                        String base = name.substring(slash + 1).toLowerCase(Locale.ROOT);
                        if (!base.isEmpty()) {
                            for (String dest : GamePack.DATA_FILES) {
                                JSONObject meta = data.getJSONObject(dest);
                                if (!names(meta).contains(base)) continue;
                                if (done.contains(dest)) {
                                    if (deferred) throw new IllegalArgumentException("Duplicate PC game file candidate: " + dest);
                                    continue;
                                }
                                if (deferred) {
                                    files.put(dest, copyDiscovered(c, zip, staging, dest, cancelled, total));
                                    done.add(dest); progress.message(dest + " · " + (files.getJSONObject(dest).getLong("size") / MiB) + " MB");
                                } else {
                                    long known = entry.getSize();
                                    if (known >= 0 && known != meta.getLong("size")) continue;
                                    if (copyExpected(zip, staging, dest, meta, cancelled)) {
                                        files.put(dest, expectedMeta(meta));
                                        done.add(dest); progress.message(dest + " · " + (meta.getLong("size") / MiB) + " MB");
                                    }
                                }
                            }
                        }
                    }
                    zip.closeEntry();
                    if (done.size() == GamePack.DATA_FILES.size()) break;
                }
            }
            requireComplete(done);
            commitData(c, staging, files);
        } finally { PackImporter.deleteTree(staging); }
    }

    public static void importTree(Context c, Uri tree, AtomicBoolean cancelled, PackImporter.Progress progress) throws Exception {
        requireEmbedded();
        JSONObject data = EmbeddedGame.data(c);
        boolean deferred = EmbeddedGame.DATA_ON_IMPORT.equals(EmbeddedGame.dataValidation(c));
        checkSpace(c);
        File staging = staging(c);
        Set<String> done = new HashSet<>();
        JSONObject files = new JSONObject();
        long[] total = {0};
        try {
            int[] count = {0};
            scan(c, tree, DocumentsContract.getTreeDocumentId(tree), 0, count, staging, data, done, files, total, deferred, cancelled, progress);
            requireComplete(done);
            commitData(c, staging, files);
        } finally { PackImporter.deleteTree(staging); }
    }

    private static boolean scan(Context c, Uri tree, String parent, int depth, int[] count, File staging,
                                JSONObject data, Set<String> done, JSONObject files, long[] total, boolean deferred,
                                AtomicBoolean cancelled, PackImporter.Progress progress) throws Exception {
        if (cancelled.get()) throw new InterruptedException("Import cancelled");
        if (depth > MAX_DEPTH) throw new IllegalArgumentException("Game folder is nested too deeply");
        ContentResolver resolver = c.getContentResolver();
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent);
        String[] projection = {DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE};
        try (Cursor cursor = resolver.query(children, projection, null, null, null)) {
            if (cursor == null) throw new java.io.IOException("Cannot list the selected game folder");
            int idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
            int nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            int mimeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE);
            int sizeColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE);
            while (cursor.moveToNext()) {
                if (cancelled.get()) throw new InterruptedException("Import cancelled");
                if (++count[0] > MAX_ENTRIES) throw new IllegalArgumentException("Game folder contains too many entries");
                String document = cursor.getString(idColumn);
                String name = cursor.getString(nameColumn);
                String mime = cursor.getString(mimeColumn);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    if (scan(c, tree, document, depth + 1, count, staging, data, done, files, total, deferred, cancelled, progress)) return true;
                    continue;
                }
                if (name == null) continue;
                String base = name.toLowerCase(Locale.ROOT);
                long known = sizeColumn >= 0 && !cursor.isNull(sizeColumn) ? cursor.getLong(sizeColumn) : -1;
                for (String dest : GamePack.DATA_FILES) {
                    JSONObject meta = data.getJSONObject(dest);
                    if (!names(meta).contains(base)) continue;
                    if (done.contains(dest)) {
                        if (deferred) throw new IllegalArgumentException("Duplicate PC game file candidate: " + dest);
                        continue;
                    }
                    Uri file = DocumentsContract.buildDocumentUriUsingTree(tree, document);
                    try (InputStream in = resolver.openInputStream(file)) {
                        if (in == null) throw new java.io.IOException("Cannot open " + name);
                        if (deferred) {
                            files.put(dest, copyDiscovered(c, in, staging, dest, cancelled, total));
                            done.add(dest); progress.message(dest + " · " + (files.getJSONObject(dest).getLong("size") / MiB) + " MB");
                            if (done.size() == GamePack.DATA_FILES.size()) return true;
                        } else {
                            if (known >= 0 && known != meta.getLong("size")) continue;
                            if (copyExpected(in, staging, dest, meta, cancelled)) {
                                files.put(dest, expectedMeta(meta));
                                done.add(dest); progress.message(dest + " · " + (meta.getLong("size") / MiB) + " MB");
                                if (done.size() == GamePack.DATA_FILES.size()) return true;
                            }
                        }
                    }
                }
            }
        }
        return false;
    }

    private static JSONObject expectedMeta(JSONObject meta) throws Exception {
        JSONObject f = new JSONObject();
        f.put("size", meta.getLong("size"));
        f.put("sha256", meta.getString("sha256"));
        return f;
    }

    /** EXE-only build: validate names/limits, compute SHA-256 on the phone, and record it for future integrity checks. */
    private static JSONObject copyDiscovered(Context c, InputStream in, File staging, String dest,
                                             AtomicBoolean cancelled, long[] total) throws Exception {
        long remaining = GamePack.MAX_TOTAL - total[0];
        if (remaining <= 0) throw new IllegalArgumentException("Game data exceeds 3 GiB");
        requireFree(c, 512 * MiB);
        File tmp = new File(staging, dest + ".candidate"), target = new File(staging, dest);
        tmp.delete();
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        long size = 0, nextSpaceCheck = 64 * MiB;
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            byte[] buffer = new byte[1 << 16]; int n;
            while ((n = in.read(buffer)) >= 0) {
                if (cancelled.get()) throw new InterruptedException("Import cancelled");
                if (n == 0) continue;
                if (size + n > remaining) throw new IllegalArgumentException("Game data exceeds 3 GiB");
                out.write(buffer, 0, n); hash.update(buffer, 0, n); size += n;
                if (size >= nextSpaceCheck) { requireFree(c, 256 * MiB); nextSpaceCheck += 64 * MiB; }
            }
            out.getFD().sync();
        } catch (Exception e) { tmp.delete(); throw e; }
        if (size == 0) { tmp.delete(); throw new IllegalArgumentException("Empty PC game file: " + dest); }
        requireFree(c, 256 * MiB);
        if (target.exists()) { tmp.delete(); throw new IllegalStateException("Duplicate staged PC game file: " + dest); }
        if (!tmp.renameTo(target)) { tmp.delete(); throw new java.io.IOException("Cannot stage " + dest); }
        total[0] += size;
        JSONObject f = new JSONObject();
        f.put("size", size);
        f.put("sha256", GamePack.hex(hash.digest()));
        return f;
    }

    /** Copies a candidate only if its exact size and SHA-256 match the game used to build this APK. */
    private static boolean copyExpected(InputStream in, File staging, String dest, JSONObject meta, AtomicBoolean cancelled) throws Exception {
        long expected = meta.getLong("size");
        String expectedHash = meta.getString("sha256");
        File tmp = new File(staging, dest + ".candidate"), target = new File(staging, dest);
        tmp.delete();
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        long size = 0; boolean tooLarge = false;
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            byte[] buffer = new byte[1 << 16]; int n;
            while ((n = in.read(buffer)) >= 0) {
                if (cancelled.get()) throw new InterruptedException("Import cancelled");
                if (n == 0) continue;
                if (size + n > expected) { tooLarge = true; break; }
                out.write(buffer, 0, n); hash.update(buffer, 0, n); size += n;
            }
            out.getFD().sync();
        } catch (Exception e) { tmp.delete(); throw e; }
        if (tooLarge || size != expected || !GamePack.hex(hash.digest()).equals(expectedHash)) { tmp.delete(); return false; }
        if (target.exists()) { tmp.delete(); return true; }
        if (!tmp.renameTo(target)) { tmp.delete(); throw new java.io.IOException("Cannot stage " + dest); }
        return true;
    }

    private static void commitData(Context c, File staging, JSONObject files) throws Exception {
        JSONObject manifest = new JSONObject();
        manifest.put("format", GamePack.EMBEDDED_FORMAT);
        manifest.put("abi", GamePack.abi());
        manifest.put("embedded_id", EmbeddedGame.id(c));
        manifest.put("files", files);
        PackImporter.commit(c, staging, manifest, GamePack.DATA_FILES);
    }
    private static Set<String> names(JSONObject meta) throws Exception {
        JSONArray array = meta.getJSONArray("names"); Set<String> set = new HashSet<>();
        for (int i = 0; i < array.length(); i++) set.add(array.getString(i).toLowerCase(Locale.ROOT));
        return set;
    }
    private static void requireEmbedded() {
        if (!BuildConfig.EMBEDDED_GAME) throw new IllegalArgumentException("This APK needs a full .tmnt.zip pack");
    }
    private static void checkSpace(Context c) throws Exception {
        long known = EmbeddedGame.dataTotal(c);
        long required = known >= 0 ? known + 512 * MiB : 512 * MiB;
        if (new StatFs(GamePack.root(c).getAbsolutePath()).getAvailableBytes() < required)
            throw new java.io.IOException("Not enough free space for game data and a safety margin");
    }
    private static void requireFree(Context c, long margin) throws Exception {
        if (new StatFs(GamePack.root(c).getAbsolutePath()).getAvailableBytes() < margin)
            throw new java.io.IOException("Not enough free space while importing game data");
    }
    private static File staging(Context c) throws Exception {
        File dir = new File(GamePack.root(c), ".import-" + UUID.randomUUID());
        if (!dir.mkdir()) throw new java.io.IOException("Cannot create import staging directory");
        return dir;
    }
    private static void requireComplete(Set<String> done) {
        if (done.size() == GamePack.DATA_FILES.size()) return;
        StringBuilder missing = new StringBuilder();
        for (String name : GamePack.DATA_FILES) if (!done.contains(name)) { if (missing.length() > 0) missing.append(", "); missing.append(name); }
        throw new IllegalArgumentException("Missing or mismatched PC game files: " + missing);
    }
}
