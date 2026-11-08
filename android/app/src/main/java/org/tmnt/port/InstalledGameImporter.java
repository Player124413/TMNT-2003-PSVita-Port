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

/** Private-APK import: copy only the seven PC data files and check them against the embedded build. */
public final class InstalledGameImporter {
    private static final int MAX_ENTRIES = 50000;
    private static final int MAX_DEPTH = 16;
    private InstalledGameImporter() {}

    public static void importZip(Context c, Uri uri, AtomicBoolean cancelled, PackImporter.Progress progress) throws Exception {
        requireEmbedded();
        JSONObject data = EmbeddedGame.data(c);
        checkSpace(c);
        File staging = staging(c);
        Set<String> done = new HashSet<>();
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
                                if (done.contains(dest)) continue;
                                JSONObject meta = data.getJSONObject(dest);
                                if (!names(meta).contains(base)) continue;
                                long known = entry.getSize();
                                if (known >= 0 && known != meta.getLong("size")) continue;
                                if (copyExpected(zip, staging, dest, meta, cancelled)) {
                                    done.add(dest); progress.message(dest + " · " + (meta.getLong("size") / 1048576) + " MB");
                                }
                            }
                        }
                    }
                    zip.closeEntry();
                    if (done.size() == GamePack.DATA_FILES.size()) break;
                }
            }
            requireComplete(done);
            commitData(c, staging, data);
        } finally { PackImporter.deleteTree(staging); }
    }

    public static void importTree(Context c, Uri tree, AtomicBoolean cancelled, PackImporter.Progress progress) throws Exception {
        requireEmbedded();
        JSONObject data = EmbeddedGame.data(c);
        checkSpace(c);
        File staging = staging(c);
        Set<String> done = new HashSet<>();
        try {
            int[] count = {0};
            scan(c, tree, DocumentsContract.getTreeDocumentId(tree), 0, count, staging, data, done, cancelled, progress);
            requireComplete(done);
            commitData(c, staging, data);
        } finally { PackImporter.deleteTree(staging); }
    }

    private static boolean scan(Context c, Uri tree, String parent, int depth, int[] count, File staging,
                                JSONObject data, Set<String> done, AtomicBoolean cancelled, PackImporter.Progress progress) throws Exception {
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
                    if (scan(c, tree, document, depth + 1, count, staging, data, done, cancelled, progress)) return true;
                    continue;
                }
                if (name == null) continue;
                String base = name.toLowerCase(Locale.ROOT);
                long known = sizeColumn >= 0 && !cursor.isNull(sizeColumn) ? cursor.getLong(sizeColumn) : -1;
                for (String dest : GamePack.DATA_FILES) {
                    if (done.contains(dest)) continue;
                    JSONObject meta = data.getJSONObject(dest);
                    if (!names(meta).contains(base)) continue;
                    if (known >= 0 && known != meta.getLong("size")) continue;
                    Uri file = DocumentsContract.buildDocumentUriUsingTree(tree, document);
                    try (InputStream in = resolver.openInputStream(file)) {
                        if (in == null) throw new java.io.IOException("Cannot open " + name);
                        if (copyExpected(in, staging, dest, meta, cancelled)) {
                            done.add(dest); progress.message(dest + " · " + (meta.getLong("size") / 1048576) + " MB");
                            if (done.size() == GamePack.DATA_FILES.size()) return true;
                        }
                    }
                }
            }
        }
        return false;
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

    private static void commitData(Context c, File staging, JSONObject data) throws Exception {
        JSONObject files = new JSONObject();
        for (String name : GamePack.DATA_FILES) {
            JSONObject meta = data.getJSONObject(name), f = new JSONObject();
            f.put("size", meta.getLong("size"));
            f.put("sha256", meta.getString("sha256"));
            files.put(name, f);
        }
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
        long required = EmbeddedGame.dataTotal(c) + 512L * 1024 * 1024;
        if (new StatFs(GamePack.root(c).getAbsolutePath()).getAvailableBytes() < required)
            throw new java.io.IOException("Not enough free space for game data and a 512 MiB safety margin");
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
