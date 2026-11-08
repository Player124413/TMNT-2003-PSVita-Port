package org.tmnt.port;

import android.content.Context;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Versioned, validated layout. Coordinates are fractions of the safe landscape area, not pixels. */
public final class ControlLayout {
    public static final int VERSION = 1;
    public static final String[] NAMES = {"weak", "strong", "jump", "shuriken", "dash", "switch", "juggle", "special", "start"};
    public static final class Control {
        public String id;
        public int action;
        public float x, y, size, alpha;
        public Control(String id, int action, float x, float y, float size, float alpha) {
            this.id = id; this.action = action; this.x = x; this.y = y; this.size = size; this.alpha = alpha;
        }
        public Control copy() { return new Control(id, action, x, y, size, alpha); }
        JSONObject json() throws Exception {
            JSONObject j = new JSONObject();
            j.put("id", id); j.put("action", action); j.put("x", x); j.put("y", y);
            j.put("size", size); j.put("alpha", alpha); return j;
        }
    }
    public final List<Control> controls = new ArrayList<>();
    public float opacity = 0.65f, deadzone = 0.16f;
    public boolean haptics = true;
    public static ControlLayout defaults() {
        ControlLayout l = new ControlLayout();
        l.controls.add(new Control("stick", -1, .145f, .73f, .28f, 1));
        float[][] positions = {{.86f,.76f},{.94f,.57f},{.75f,.88f},{.77f,.58f},
                               {.70f,.13f},{.92f,.13f},{.84f,.13f},{.95f,.34f},{.50f,.10f}};
        for (int i = 0; i < NAMES.length; i++) {
            float[] p = positions[i];
            l.controls.add(new Control(NAMES[i], i, p[0], p[1], i == 8 ? .095f : .145f, 1));
        }
        return l;
    }
    public ControlLayout copy() {
        ControlLayout l = new ControlLayout();
        for (Control c : controls) l.controls.add(c.copy());
        l.opacity = opacity; l.deadzone = deadzone; l.haptics = haptics; return l;
    }
    public String encode() throws Exception {
        JSONObject root = new JSONObject(); root.put("version", VERSION);
        root.put("opacity", opacity); root.put("deadzone", deadzone); root.put("haptics", haptics);
        JSONArray a = new JSONArray(); for (Control c : controls) a.put(c.json());
        root.put("controls", a); return root.toString(2);
    }
    public static float finite(float v, float fallback, float min, float max) {
        return Float.isFinite(v) ? Math.max(min, Math.min(max, v)) : fallback;
    }
    public static ControlLayout decode(String text) throws Exception {
        if (text.length() > 65536) throw new IllegalArgumentException("Layout too large");
        JSONObject j = new JSONObject(text);
        if (j.optInt("version", -1) != VERSION) throw new IllegalArgumentException("Unsupported layout version");
        ControlLayout l = new ControlLayout();
        l.opacity = finite((float)j.optDouble("opacity", .65), .65f, .15f, 1);
        l.deadzone = finite((float)j.optDouble("deadzone", .16), .16f, .05f, .35f);
        l.haptics = j.optBoolean("haptics", true);
        JSONArray a = j.optJSONArray("controls");
        if (a == null || a.length() != 10) throw new IllegalArgumentException("Expected stick and nine buttons");
        boolean[] used = new boolean[9]; boolean stick = false;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.getJSONObject(i); String id = o.getString("id");
            int action = o.getInt("action");
            if ("stick".equals(id)) {
                if (stick || action != -1) throw new IllegalArgumentException("Duplicate or remapped stick");
                stick = true;
            } else {
                if (action < 0 || action >= 9 || used[action] || !NAMES[action].equals(id))
                    throw new IllegalArgumentException("Duplicate or unknown action");
                used[action] = true;
            }
            l.controls.add(new Control(id, action,
                finite((float)o.getDouble("x"), .5f, .02f, .98f),
                finite((float)o.getDouble("y"), .5f, .02f, .98f),
                finite((float)o.getDouble("size"), .145f, .075f, .35f),
                finite((float)o.getDouble("alpha"), 1, .2f, 1)));
        }
        if (!stick) throw new IllegalArgumentException("Missing stick");
        return l;
    }
    public static File file(Context c) { return new File(c.getFilesDir(), "controls.json"); }
    public static ControlLayout load(Context c) {
        AtomicFile f = new AtomicFile(file(c));
        try { return decode(new String(f.readFully(), StandardCharsets.UTF_8)); }
        catch (Exception ignored) { return defaults(); }
    }
    public void save(Context c) throws Exception {
        AtomicFile f = new AtomicFile(file(c));
        FileOutputStream out = f.startWrite();
        try { out.write(encode().getBytes(StandardCharsets.UTF_8)); f.finishWrite(out); }
        catch (Exception e) { f.failWrite(out); throw e; }
    }
}
