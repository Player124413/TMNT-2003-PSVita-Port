package org.tmnt.port;

import android.content.Context;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Installed-file contract. Clean APKs import a full pack; private APKs import only PC data. */
public final class GamePack {
    public static final int FORMAT = 1;
    public static final int EMBEDDED_FORMAT = 2;
    public static final long MAX_TOTAL = 3L * 1024 * 1024 * 1024;
    public static final Set<String> DATA_FILES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        "TMNT.DAT", "TMNT_V00.DAT", "TMNT_V01.DAT", "TMNT_V02.DAT", "bgm.afs", "se.afs", "voice.afs")));
    public static final Set<String> FILES;
    static {
        Set<String> all = new HashSet<>(DATA_FILES);
        all.add("image.bin");
        all.add("tmnt_code.so");
        FILES = Collections.unmodifiableSet(all);
    }
    private GamePack() {}
    /** ABI of this APK. A game module/pack must match it exactly; ARM32 code cannot load in an ARM64 process. */
    public static String abi() { return BuildConfig.PORT_ABI; }
    public static Set<String> requiredFiles() { return BuildConfig.EMBEDDED_GAME ? DATA_FILES : FILES; }
    public static File root(Context c) { return c.getFilesDir(); }
    public static File marker(Context c) { return new File(root(c), "pack.json"); }
    public static boolean installed(Context c) {
        try {
            JSONObject j = new JSONObject(read(marker(c), 65536));
            boolean embedded = BuildConfig.EMBEDDED_GAME;
            if (j.getInt("format") != (embedded ? EMBEDDED_FORMAT : FORMAT) || !abi().equals(j.getString("abi"))) return false;
            if (embedded && !EmbeddedGame.id(c).equals(j.optString("embedded_id"))) return false;
            JSONObject files = j.getJSONObject("files");
            Set<String> required = requiredFiles();
            if (files.length() != required.size()) return false;
            for (String name : required) {
                JSONObject f = files.getJSONObject(name); File p = new File(root(c), name);
                if (!p.isFile() || p.length() != f.getLong("size")) return false;
            }
            return true;
        } catch (Exception e) { return false; }
    }
    public static String read(File f, int limit) throws Exception {
        if (f.length() > limit) throw new IllegalArgumentException("File too large");
        try (InputStream in = new FileInputStream(f)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[8192]; int n;
            while ((n = in.read(b)) >= 0) { if (out.size() + n > limit) throw new IllegalArgumentException("File too large"); out.write(b, 0, n); }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
    public static String sha256(File f) throws Exception {
        MessageDigest h = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(f)) { byte[] b = new byte[1 << 20]; int n; while ((n = in.read(b)) >= 0) h.update(b, 0, n); }
        return hex(h.digest());
    }
    public static String hex(byte[] digest) {
        StringBuilder s = new StringBuilder(digest.length * 2);
        for (byte v : digest) s.append(String.format("%02x", v & 255));
        return s.toString();
    }
    public static long totalSize(String text) throws Exception {
        JSONObject files = new JSONObject(text).getJSONObject("files"); long total = 0;
        for (String name : FILES) total += files.getJSONObject(name).getLong("size"); return total;
    }
    /** Validates a clean, importable .tmnt.zip manifest. Private APKs do not accept executable code from storage. */
    public static void validateManifest(String text) throws Exception {
        if (text.length() > 65536) throw new IllegalArgumentException("Manifest too large");
        JSONObject j = new JSONObject(text);
        if (j.getInt("format") != FORMAT || !abi().equals(j.getString("abi")))
            throw new IllegalArgumentException("Wrong pack format or CPU ABI");
        JSONObject files = j.getJSONObject("files"); long total = 0;
        if (files.length() != FILES.size()) throw new IllegalArgumentException("Incomplete or unexpected file list");
        for (String name : FILES) {
            JSONObject f = files.getJSONObject(name); long size = f.getLong("size");
            String hash = f.getString("sha256");
            if (size <= 0 || size > MAX_TOTAL || !hash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid file metadata");
            total += size; if (total > MAX_TOTAL) throw new IllegalArgumentException("Pack exceeds 3 GiB");
        }
    }
    public static void validateElf(File f) throws Exception {
        boolean arm64 = "arm64-v8a".equals(abi());
        int elfClass = arm64 ? 2 : 1, machine = arm64 ? 183 : 40;
        try (java.io.RandomAccessFile e = new java.io.RandomAccessFile(f, "r")) {
            byte[] h = new byte[64]; e.readFully(h);
            if (h[0] != 127 || h[1] != 'E' || h[2] != 'L' || h[3] != 'F' || (h[4] & 255) != elfClass || h[5] != 1
                || u16(h, 16) != 3 || u16(h, 18) != machine || u16(h, 20) != 1)
                throw new IllegalArgumentException("Game module must be an ELF shared object for " + abi());
        }
    }
    private static int u16(byte[] b, int off) { return (b[off] & 255) | ((b[off + 1] & 255) << 8); }
    public static void remove(Context c) {
        for (String name : requiredFiles()) { File f = new File(root(c), name); if (!f.delete() && f.exists()) throw new IllegalStateException("Could not remove " + name); }
        marker(c).delete();
    }
}
