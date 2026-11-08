package org.tmnt.port;

import android.content.Context;
import android.net.Uri;
import android.os.StatFs;
import android.util.AtomicFile;
import org.json.JSONObject;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Bounded, streaming SAF import. Never executes or dlopens anything during validation. */
public final class PackImporter {
    public interface Progress { void message(String text); }
    public static void importZip(Context context, Uri uri, java.util.concurrent.atomic.AtomicBoolean cancelled, Progress progress) throws Exception {
        if (BuildConfig.EMBEDDED_GAME) throw new IllegalArgumentException("Private APK imports installed PC game data, not a code pack");
        File root = GamePack.root(context), staging = new File(root, ".import-" + UUID.randomUUID());
        try {
            if (!staging.mkdir()) throw new java.io.IOException("Cannot create import staging directory");
            JSONObject manifest = null; long total = 0; int count = 0; Set<String> seen = new HashSet<>();
            try (InputStream raw = context.getContentResolver().openInputStream(uri);
                 ZipInputStream zip = new ZipInputStream(new BufferedInputStream(raw, 1 << 16))) {
                if (raw == null) throw new java.io.IOException("Cannot open selected file");
                ZipEntry entry; byte[] buffer = new byte[1 << 16];
                while ((entry = zip.getNextEntry()) != null) {
                    if (cancelled.get()) throw new InterruptedException("Import cancelled");
                    if (++count > GamePack.FILES.size() + 1 || entry.isDirectory()) throw new IllegalArgumentException("Unexpected directory or extra files in pack");
                    String name = entry.getName();
                    if (!seen.add(name)) throw new IllegalArgumentException("Duplicate ZIP entry");
                    if ("pack.json".equals(name)) {
                        if (manifest != null) throw new IllegalArgumentException("Duplicate manifest");
                        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(); int n;
                        while ((n = zip.read(buffer)) >= 0) { if (out.size() + n > 65536) throw new IllegalArgumentException("Manifest too large"); out.write(buffer, 0, n); }
                        String text = new String(out.toByteArray(), StandardCharsets.UTF_8);
                        GamePack.validateManifest(text); manifest = new JSONObject(text);
                        long required = GamePack.totalSize(text) + 512L * 1024 * 1024;
                        if (new StatFs(root.getAbsolutePath()).getAvailableBytes() < required)
                            throw new java.io.IOException("Not enough free space for the pack and a 512 MiB safety margin");
                    } else {
                        if (manifest == null) throw new IllegalArgumentException("pack.json must be the first ZIP entry");
                        if (!GamePack.FILES.contains(name)) throw new IllegalArgumentException("Unexpected file in pack");
                        JSONObject meta = manifest.getJSONObject("files").getJSONObject(name);
                        long expected = meta.getLong("size"), size = 0;
                        if (total + expected > GamePack.MAX_TOTAL) throw new IllegalArgumentException("Pack exceeds 3 GiB");
                        File out = new File(staging, name); MessageDigest hash = MessageDigest.getInstance("SHA-256");
                        try (FileOutputStream fos = new FileOutputStream(out)) {
                            int n; while ((n = zip.read(buffer)) >= 0) {
                                if (cancelled.get()) throw new InterruptedException("Import cancelled");
                                size += n; if (size > expected) throw new IllegalArgumentException("File larger than manifest");
                                fos.write(buffer, 0, n); hash.update(buffer, 0, n);
                            }
                            fos.getFD().sync();
                        }
                        String actual = GamePack.hex(hash.digest());
                        if (size != expected || !actual.equals(meta.getString("sha256")))
                            throw new IllegalArgumentException("Checksum/size mismatch: " + name);
                        total += size; progress.message(name + " · " + (size / 1048576) + " MB");
                    }
                    zip.closeEntry();
                }
            }
            if (manifest == null || seen.size() != GamePack.FILES.size() + 1) throw new IllegalArgumentException("Incomplete game pack");
            File code = new File(staging, "tmnt_code.so"); GamePack.validateElf(code);
            if (!code.setReadOnly()) throw new java.io.IOException("Cannot make game module read-only");
            commit(context, staging, manifest, GamePack.FILES);
        } finally { deleteTree(staging); }
    }

    /** Atomically replaces only the requested installed files; saves/settings are never touched. */
    static void commit(Context context, File staging, JSONObject manifest, Set<String> names) throws Exception {
        File root = GamePack.root(context);
        List<File> moved = new ArrayList<>(), backups = new ArrayList<>(); boolean committed = false;
        try {
            for (String name : names) {
                File target = new File(root, name), backup = new File(staging, "old-" + name);
                if (target.exists()) { if (!target.renameTo(backup)) throw new java.io.IOException("Cannot back up " + name); backups.add(backup); }
                File src = new File(staging, name); if (!src.renameTo(target)) throw new java.io.IOException("Cannot install " + name);
                moved.add(target);
            }
            AtomicFile marker = new AtomicFile(GamePack.marker(context)); FileOutputStream out = marker.startWrite();
            try { out.write(manifest.toString().getBytes(StandardCharsets.UTF_8)); marker.finishWrite(out); }
            catch (Exception e) { marker.failWrite(out); throw e; }
            committed = true;
        } catch (Exception e) {
            if (!committed) {
                for (File f : moved) f.delete();
                for (File b : backups) { String name = b.getName().substring(4); b.renameTo(new File(root, name)); }
            }
            throw e;
        }
    }
    static void deleteTree(File f) { if (f.isDirectory()) { File[] children = f.listFiles(); if (children != null) for (File c : children) deleteTree(c); } f.delete(); }
}
