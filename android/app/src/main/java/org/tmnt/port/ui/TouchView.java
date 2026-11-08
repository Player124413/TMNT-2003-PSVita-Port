package org.tmnt.port.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import java.util.HashMap;
import java.util.Map;
import org.tmnt.port.ControlLayout;
import org.tmnt.port.ControlLayout.Control;
import org.tmnt.port.NativeBridge;
import org.tmnt.port.R;

/** Event-driven overlay: no GL work or allocation per game frame. Pointer ownership survives overlapping buttons. */
public final class TouchView extends View {
    public interface SelectionListener { void selected(Control control); }
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Map<Integer, Control> owners = new HashMap<>();
    private final int[] labels = {R.string.weak,R.string.strong,R.string.jump,R.string.shuriken,R.string.dash,
        R.string.switch_turtle,R.string.juggle,R.string.special,R.string.start};
    private final int[] colors = {0xFFE97965,0xFFF0BC58,0xFF70B9E8,0xFFB59AE5,0xFF8FE36A,
        0xFF9DB5A6,0xFFE59D6E,0xFFEACD62,0xFFCBD8CF};
    private ControlLayout layout;
    private Control selected;
    private boolean editor, live;
    private int stickPointer = -1, dragPointer = -1;
    private float stickX, stickY, dragX, dragY;
    private SelectionListener listener;
    private Runnable onChange, onGesture;
    public TouchView(Context c, ControlLayout layout) {
        super(c); this.layout = layout;
        setFocusable(false); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        colors[7] = 0xFFEACD62;
    }
    public void setLayout(ControlLayout l) { clear(); layout = l; selected = null; invalidate(); }
    public void setEditor(boolean value, SelectionListener listener) { editor = value; this.listener = listener; clear(); invalidate(); }
    public void setLive(boolean value) { live = value; if (!value) clear(); }
    public void setChangeCallback(Runnable r) { onChange = r; }
    public void setGestureCallback(Runnable r) { onGesture = r; }
    public Control selected() { return selected; }
    public void select(Control c) { selected = c; invalidate(); if (listener != null) listener.selected(c); }
    public void changed() { invalidate(); if (onChange != null) onChange.run(); }
    private float unit() { return Math.min(getWidth(), getHeight()); }
    private float radius(Control c) { return unit() * c.size * .5f; }
    private float cx(Control c) { return c.x * getWidth(); }
    private float cy(Control c) { return c.y * getHeight(); }
    public void clear() {
        owners.clear(); stickPointer = dragPointer = -1; stickX = stickY = 0;
        if (live && NativeBridge.loaded) { NativeBridge.clearInput(); }
        invalidate();
    }
    private void publish() {
        if (!live || editor || !NativeBridge.loaded) return;
        int mask = 0;
        for (Control c : owners.values()) if (c.action >= 0) mask |= 1 << c.action;
        NativeBridge.touchButtons(mask); NativeBridge.touchStick(stickX, stickY);
    }
    private Control hit(float x, float y) {
        Control best = null; float distance = Float.MAX_VALUE;
        for (Control c : layout.controls) {
            float dx = x - cx(c), dy = y - cy(c), r = radius(c) * 1.12f;
            float d = dx*dx + dy*dy;
            if (d <= r*r && d < distance) { best = c; distance = d; }
        }
        return best;
    }
    public void clamp(Control c) {
        float rx = radius(c) / Math.max(1,getWidth()), ry = radius(c) / Math.max(1,getHeight());
        c.x = ControlLayout.finite(c.x, .5f, rx + .005f, .995f-rx);
        c.y = ControlLayout.finite(c.y, .5f, ry + .005f, .995f-ry);
    }
    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        clear(); for (Control c : layout.controls) clamp(c);
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked(), index = event.getActionIndex(), id = event.getPointerId(index);
        float x = event.getX(index), y = event.getY(index);
        if (editor) {
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
                if (dragPointer != -1) return true;
                Control c = hit(x,y); if (c == null) return false;
                if (onGesture != null) onGesture.run();
                select(c); dragPointer = id; dragX = x-cx(c); dragY = y-cy(c);
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            } else if (action == MotionEvent.ACTION_MOVE && id == dragPointer) {
                if (selected != null) {
                    selected.x = (x-dragX)/Math.max(1,getWidth()); selected.y = (y-dragY)/Math.max(1,getHeight());
                    clamp(selected); changed();
                }
            } else if (action == MotionEvent.ACTION_CANCEL ||
                    ((action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) && id == dragPointer)) {
                dragPointer = -1;
            }
            return true;
        }
        if (!live) return false;
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            Control c = hit(x,y); if (c == null) return false;
            if (c.action < 0) {
                if (stickPointer != -1) return true;
                stickPointer = id; updateStick(c,x,y);
            } else if (!owners.containsValue(c)) owners.put(id,c);
            if (layout.haptics) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            publish(); invalidate();
        } else if (action == MotionEvent.ACTION_MOVE) {
            if (stickPointer != -1) {
                int si = event.findPointerIndex(stickPointer);
                if (si >= 0) {
                    Control c = control("stick"); if (c != null) updateStick(c,event.getX(si),event.getY(si));
                }
            }
            // A finger may slide between action buttons; it always releases the button it originally pressed.
            for (int i = 0; i < event.getPointerCount(); i++) {
                int pid = event.getPointerId(i);
                if (pid == stickPointer) continue;
                Control old = owners.get(pid), next = hit(event.getX(i),event.getY(i));
                if (next != null && next.action >= 0 && next != old && !owners.containsValue(next)) {
                    owners.remove(pid); owners.put(pid,next);
                }
            }
            publish(); invalidate();
        } else {
            boolean cancel = action == MotionEvent.ACTION_CANCEL;
            if (cancel || action == MotionEvent.ACTION_UP) { owners.clear(); stickPointer=-1; stickX=stickY=0; }
            else if (action == MotionEvent.ACTION_POINTER_UP) {
                owners.remove(id); if (id == stickPointer) { stickPointer=-1; stickX=stickY=0; }
            }
            publish(); invalidate();
        }
        return true;
    }
    private Control control(String id) { for (Control c : layout.controls) if (c.id.equals(id)) return c; return null; }
    private void updateStick(Control c, float x, float y) {
        float dx = (x-cx(c))/radius(c), dy = (y-cy(c))/radius(c), len = (float)Math.hypot(dx,dy);
        if (len > 1) { dx/=len; dy/=len; }
        float m = Math.min(1,len);
        if (m < layout.deadzone) dx=dy=0;
        else { float scaled=(m-layout.deadzone)/(1-layout.deadzone)/Math.max(.001f,m); dx*=scaled; dy*=scaled; }
        stickX=dx; stickY=dy;
    }
    private void circle(float x, float y, float r, int color, float alpha) {
        paint.setStyle(Paint.Style.FILL); paint.setColor(color); paint.setAlpha((int)(Color.alpha(color)*alpha));
        canvas.drawCircle(x,y,r,paint);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(Math.max(2,unit()*.003f));
        paint.setColor(Color.WHITE); paint.setAlpha((int)(130*alpha));
        canvas.drawCircle(x,y,r,paint); paint.setStyle(Paint.Style.FILL);
    }
    private Canvas canvas;
    @Override protected void onDraw(Canvas c) {
        canvas = c;
        float u = unit();
        for (Control b : layout.controls) {
            float x=cx(b), y=cy(b), r=radius(b), a=layout.opacity*b.alpha;
            boolean down = b.action < 0 ? stickPointer != -1 : owners.containsValue(b);
            if (b.action < 0) {
                circle(x,y,r,0xFF182C22,a*.85f);
                paint.setColor(0xFF8FE36A); paint.setAlpha((int)(170*a)); paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(u*.003f); canvas.drawCircle(x,y,r*.4f,paint); paint.setStyle(Paint.Style.FILL);
                circle(x+stickX*r,y+stickY*r,r*.38f,down?0xFF8FE36A:0xFF527B60,a);
            } else {
                circle(x,y,r*(down?.94f:1),colors[b.action],a*(down?1:.72f));
                paint.setColor(0xFF102016); paint.setAlpha((int)(255*a)); paint.setTextAlign(Paint.Align.CENTER);
                paint.setFakeBoldText(true); paint.setTextSize(Math.min(r*.36f,u*.033f));
                String label = getResources().getString(labels[b.action]);
                float baseline=y-(paint.ascent()+paint.descent())/2;
                canvas.drawText(label,x,baseline,paint);
            }
            if (editor && selected == b) {
                paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(u*.006f); paint.setColor(0xFFFFFFFF);
                paint.setAlpha(255); canvas.drawCircle(x,y,r+u*.012f,paint); paint.setStyle(Paint.Style.FILL);
            }
        }
        canvas = null;
    }
}
