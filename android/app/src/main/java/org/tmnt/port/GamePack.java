package org.tmnt.port;

import android.content.Context;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public final class GamePack {
    public static final int FORMAT = 1;
    public static final long MAX_TOTAL = 3L * 1024 * 1024 * 1024;
    public static final Set<String> FILES = new HashSet<>(Arrays.asList(
        "image.bin", "tmnt_code.so", "TMNT.DAT", "TMNT_V00.DAT", "TMNT_V01.DAT", "TMNT_V02.DAT", "bgm.afs", "se.afs", "voice.afs"));
    private GamePack() {}
    public static File root(Context c) { return c.getFilesDir(); }
    public static File marker(Context c) { return new File(root(c), "pack.json"); }
    public static boolean installed(Context c) {
        try {
            JSONObject j = new JSONObject(read(marker(c),65536));
            if (j.getInt("format") != FORMAT || !"armeabi-v7a".equals(j.getString("abi"))) return false;
            JSONObject files = j.getJSONObject("files");
            if (files.length() != FILES.size()) return false;
            for (String name : FILES) {
                JSONObject f = files.getJSONObject(name); File p = new File(root(c),name);
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
            while ((n=in.read(b)) >= 0) { if (out.size()+n > limit) throw new IllegalArgumentException("File too large"); out.write(b,0,n); }
            return new String(out.toByteArray(),StandardCharsets.UTF_8);
        }
    }
    public static String sha256(File f) throws Exception {
        MessageDigest h = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(f)) { byte[] b=new byte[1<<20]; int n; while((n=in.read(b))>=0) h.update(b,0,n); }
        StringBuilder s=new StringBuilder(64); for(byte v:h.digest()) s.append(String.format("%02x",v & 255)); return s.toString();
    }
    public static long totalSize(String text) throws Exception {
        JSONObject files=new JSONObject(text).getJSONObject("files"); long total=0;
        for(String name:FILES) total+=files.getJSONObject(name).getLong("size"); return total;
    }
    public static void validateManifest(String text) throws Exception {
        if (text.length() > 65536) throw new IllegalArgumentException("Manifest too large");
        JSONObject j = new JSONObject(text);
        if (j.getInt("format") != FORMAT || !"armeabi-v7a".equals(j.getString("abi")))
            throw new IllegalArgumentException("Wrong pack format or CPU ABI");
        JSONObject files = j.getJSONObject("files"); long total=0;
        if (files.length() != FILES.size()) throw new IllegalArgumentException("Incomplete or unexpected file list");
        for (String name : FILES) {
            JSONObject f=files.getJSONObject(name); long size=f.getLong("size");
            String hash=f.getString("sha256");
            if (size <= 0 || size > MAX_TOTAL || !hash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid file metadata");
            total += size; if (total > MAX_TOTAL) throw new IllegalArgumentException("Pack exceeds 3 GiB");
        }
    }
    public static void validateElf(File f) throws Exception {
        try (java.io.RandomAccessFile e = new java.io.RandomAccessFile(f,"r")) {
            byte[] h=new byte[52]; e.readFully(h);
            if (h[0]!=127 || h[1]!='E' || h[2]!='L' || h[3]!='F' || h[4]!=1 || h[5]!=1
                || h[16]!=3 || h[18]!=40 || h[20]!=1)
                throw new IllegalArgumentException("Game module must be ELF32 little-endian ARM");
        }
    }
    public static void remove(Context c) {
        for (String name : FILES) { File f=new File(root(c),name); if (!f.delete() && f.exists()) throw new IllegalStateException("Could not remove "+name); }
        marker(c).delete();
    }
}
