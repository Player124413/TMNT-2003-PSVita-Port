package org.tmnt.port;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;

/** Private APK payload: translated code is a native library; image.bin is an asset extracted once. */
public final class EmbeddedGame {
    public static final String MANIFEST_ASSET = "tmnt/embedded.json";
    public static final String IMAGE_ASSET = "tmnt/image.bin";
    public static final String DATA_SHA256 = "sha256";
    public static final String DATA_ON_IMPORT = "on-import";
    private static JSONObject cached;
    private EmbeddedGame() {}

    public static synchronized JSONObject manifest(Context c) throws Exception {
        if (cached != null) return cached;
        if (!BuildConfig.EMBEDDED_GAME) throw new IllegalStateException("This APK has no embedded game");
        JSONObject j = new JSONObject(readAsset(c, MANIFEST_ASSET, 65536));
        if (j.getInt("format") != 1 || !GamePack.abi().equals(j.getString("abi")))
            throw new IllegalArgumentException("Wrong embedded game format or ABI");
        String id = j.getString("id");
        if (!id.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid embedded game id");
        fileMeta(j.getJSONObject("image"));
        fileMeta(j.getJSONObject("code"));
        String policy = j.optString("data_validation", DATA_SHA256);
        if (!DATA_SHA256.equals(policy) && !DATA_ON_IMPORT.equals(policy))
            throw new IllegalArgumentException("Wrong embedded data validation policy");
        JSONObject data = j.getJSONObject("data");
        if (data.length() != GamePack.DATA_FILES.size()) throw new IllegalArgumentException("Incomplete embedded data manifest");
        long total = 0;
        for (String name : GamePack.DATA_FILES) {
            JSONObject d = data.getJSONObject(name);
            JSONArray names = d.getJSONArray("names");
            if (names.length() == 0) throw new IllegalArgumentException("Missing source names for " + name);
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < names.length(); i++) {
                String n = names.getString(i);
                if (n.isEmpty() || n.indexOf('/') >= 0 || n.indexOf('\\') >= 0 || !seen.add(n.toLowerCase(java.util.Locale.ROOT)))
                    throw new IllegalArgumentException("Invalid source name for " + name);
            }
            String source = d.getString("source");
            if (!seen.contains(source.toLowerCase(java.util.Locale.ROOT)))
                throw new IllegalArgumentException("Source name missing from aliases for " + name);
            if (DATA_SHA256.equals(policy)) {
                fileMeta(d);
                total += d.getLong("size");
                if (total > GamePack.MAX_TOTAL) throw new IllegalArgumentException("Embedded data exceeds 3 GiB");
            } else if (d.has("size") || d.has("sha256")) {
                throw new IllegalArgumentException("On-import data metadata must not contain build-time hashes: " + name);
            }
        }
        cached = j;
        return j;
    }
    private static void fileMeta(JSONObject j) throws Exception {
        long size = j.getLong("size");
        String hash = j.getString("sha256");
        if (size <= 0 || size > GamePack.MAX_TOTAL || !hash.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid embedded file metadata");
    }
    public static String id(Context c) throws Exception { return manifest(c).getString("id"); }
    public static JSONObject data(Context c) throws Exception { return manifest(c).getJSONObject("data"); }
    public static String dataValidation(Context c) throws Exception { return manifest(c).optString("data_validation", DATA_SHA256); }
    /** Exact total for build-time hashed data, or -1 when hashes are recorded during phone import. */
    public static long dataTotal(Context c) throws Exception {
        if (DATA_ON_IMPORT.equals(dataValidation(c))) return -1;
        JSONObject data = data(c); long total = 0;
        for (String name : GamePack.DATA_FILES) total += data.getJSONObject(name).getLong("size");
        return total;
    }

    /** The runtime keeps its simple file loader; the APK asset is materialized into app-private storage. */
    public static synchronized void ensureImage(Context c) throws Exception {
        JSONObject meta = manifest(c).getJSONObject("image");
        long expectedSize = meta.getLong("size");
        String expectedHash = meta.getString("sha256");
        File target = new File(GamePack.root(c), "image.bin");
        if (target.isFile() && target.length() == expectedSize && GamePack.sha256(target).equals(expectedHash)) return;
        File tmp = new File(GamePack.root(c), "image.bin.tmp");
        tmp.delete();
        MessageDigest h = MessageDigest.getInstance("SHA-256");
        long size = 0;
        try (InputStream in = c.getAssets().open(IMAGE_ASSET); FileOutputStream out = new FileOutputStream(tmp)) {
            byte[] b = new byte[1 << 20]; int n;
            while ((n = in.read(b)) >= 0) {
                size += n;
                if (size > expectedSize) throw new IllegalArgumentException("Embedded image is larger than its manifest");
                out.write(b, 0, n); h.update(b, 0, n);
            }
            out.getFD().sync();
        } catch (Exception e) { tmp.delete(); throw e; }
        if (size != expectedSize || !GamePack.hex(h.digest()).equals(expectedHash)) {
            tmp.delete(); throw new IllegalArgumentException("Embedded image checksum mismatch");
        }
        if (target.exists() && !target.delete()) throw new java.io.IOException("Cannot replace embedded image");
        if (!tmp.renameTo(target)) { tmp.delete(); throw new java.io.IOException("Cannot install embedded image"); }
    }
    private static String readAsset(Context c, String name, int limit) throws Exception {
        try (InputStream in = c.getAssets().open(name)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[8192]; int n;
            while ((n = in.read(b)) >= 0) {
                if (out.size() + n > limit) throw new IllegalArgumentException("Embedded manifest too large");
                out.write(b, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
