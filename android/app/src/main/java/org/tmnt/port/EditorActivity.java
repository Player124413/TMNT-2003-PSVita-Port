package org.tmnt.port;

import org.tmnt.port.ui.TouchView;
import org.tmnt.port.ui.Ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.tmnt.port.ControlLayout;
import org.tmnt.port.ControlLayout.Control;
import org.tmnt.port.PortSettings;
import org.tmnt.port.R;

public final class EditorActivity extends Activity {
    private ControlLayout layout, original;
    private TouchView touch;
    private final Deque<String> undo = new ArrayDeque<>();
    private TextView selected;
    private SeekBar size, alpha, opacity, deadzone;
    private Spinner action;
    private CheckBox haptics;
    private boolean binding, dirty;
    private final int[] actions={R.string.weak,R.string.strong,R.string.jump,R.string.shuriken,R.string.dash,R.string.switch_turtle,R.string.juggle,R.string.special,R.string.start};
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); Ui.immersive(this); Ui.back(this,this::confirmDiscard);
        original=ControlLayout.load(this); layout=original.copy();
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.HORIZONTAL); root.setBackgroundColor(Ui.BG); Ui.safePadding(root); setContentView(root);
        FrameLayout preview=new FrameLayout(this); preview.setBackgroundColor(0xFF0A100C);
        touch=new TouchView(this,layout); preview.addView(touch,new FrameLayout.LayoutParams(-1,-1));
        root.addView(preview,new LinearLayout.LayoutParams(0,-1,1));
        LinearLayout panel=new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL); int p=Ui.dp(this,14); panel.setPadding(p,p,p,p);
        ScrollView scroll=new ScrollView(this); scroll.addView(panel); root.addView(scroll,new LinearLayout.LayoutParams(Ui.dp(this,285),-1));
        panel.addView(Ui.title(this,R.string.edit_controls,20)); Ui.gap(panel,this,6);
        panel.addView(Ui.text(this,getString(R.string.editor_hint),12,Ui.MUTED)); Ui.gap(panel,this,12);
        selected=Ui.text(this,getString(R.string.selected),16,Ui.GREEN); panel.addView(selected);
        action=new Spinner(this); List<String> names=new ArrayList<>(); for(int id:actions)names.add(getString(id));
        action.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,names)); panel.addView(action);
        size=slider(panel,R.string.size,100); alpha=slider(panel,R.string.opacity,100);
        opacity=slider(panel,R.string.opacity,100); deadzone=slider(panel,R.string.deadzone,100);
        haptics=new CheckBox(this); haptics.setText(R.string.haptics); panel.addView(haptics);
        touch.setGestureCallback(this::remember);
        touch.setEditor(true,c->bind());
        action.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            public void onItemSelected(AdapterView<?> parent,View v,int pos,long id){ if(binding||touch.selected()==null||touch.selected().action<0)return; remember();
                Control c=touch.selected(); if(c.action!=pos){ for(Control other:layout.controls)if(other!=c&&other.action==pos){other.action=c.action;other.id=ControlLayout.NAMES[other.action];}
                    c.action=pos;c.id=ControlLayout.NAMES[pos];dirty=true;touch.changed();bind(); } }
            public void onNothingSelected(AdapterView<?> parent){}
        });
        bind();
        LinearLayout row=new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); panel.addView(row);
        Button save=Ui.button(this,R.string.save,true); Button discard=Ui.button(this,R.string.discard,false);
        row.addView(save,new LinearLayout.LayoutParams(0,-2,1)); row.addView(discard,new LinearLayout.LayoutParams(0,-2,1));
        LinearLayout row2=new LinearLayout(this); row2.setOrientation(LinearLayout.HORIZONTAL); panel.addView(row2);
        Button reset=Ui.button(this,R.string.reset,false); Button back=Ui.button(this,R.string.undo,false);
        row2.addView(reset,new LinearLayout.LayoutParams(0,-2,1)); row2.addView(back,new LinearLayout.LayoutParams(0,-2,1));
        save.setOnClickListener(v->{try{layout.save(this);setResult(RESULT_OK);finish();}catch(Exception e){new AlertDialog.Builder(this).setMessage(e.getMessage()).setPositiveButton(R.string.ok,null).show();}});
        discard.setOnClickListener(v->confirmDiscard());
        reset.setOnClickListener(v->new AlertDialog.Builder(this).setMessage(R.string.reset).setNegativeButton(R.string.cancel,null).setPositiveButton(R.string.reset,(d,w)->{
            remember();layout=ControlLayout.defaults();touch.setLayout(layout);bind();dirty=true;}).show());
        back.setOnClickListener(v->{if(!undo.isEmpty()){try{layout=ControlLayout.decode(undo.pop());touch.setLayout(layout);bind();dirty=true;}catch(Exception ignored){}}});
        touch.setChangeCallback(()->dirty=true);
    }
    private SeekBar slider(LinearLayout panel,int label,int max){TextView t=Ui.text(this,getString(label),13,Ui.MUTED);panel.addView(t);SeekBar s=new SeekBar(this);s.setMax(max);panel.addView(s);return s;}
    private void remember(){try{undo.push(layout.encode());while(undo.size()>30)undo.removeLast();}catch(Exception ignored){}}
    private void bind(){
        binding=true; Control c=touch.selected();
        selected.setText(c==null?getString(R.string.selected):(c.action<0?getString(R.string.stick):getString(actions[c.action])));
        action.setEnabled(c!=null&&c.action>=0); if(c!=null&&c.action>=0)action.setSelection(c.action);
        size.setEnabled(c!=null);size.setProgress(c==null?0:Math.round((c.size-.075f)/.275f*100));
        alpha.setEnabled(c!=null);alpha.setProgress(c==null?0:Math.round((c.alpha-.2f)/.8f*100));
        opacity.setProgress(Math.round((layout.opacity-.15f)/.85f*100));deadzone.setProgress(Math.round((layout.deadzone-.05f)/.30f*100));haptics.setChecked(layout.haptics);
        size.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(SeekBar s){remember();}public void onProgressChanged(SeekBar s,int v,boolean user){if(user&&touch.selected()!=null){touch.selected().size=.075f+v*.00275f;touch.clamp(touch.selected());touch.changed();}}public void onStopTrackingTouch(SeekBar s){}});
        alpha.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(SeekBar s){remember();}public void onProgressChanged(SeekBar s,int v,boolean user){if(user&&touch.selected()!=null){touch.selected().alpha=.2f+v*.008f;touch.changed();}}public void onStopTrackingTouch(SeekBar s){}});
        opacity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(SeekBar s){remember();}public void onProgressChanged(SeekBar s,int v,boolean user){if(user){layout.opacity=.15f+v*.0085f;touch.changed();}}public void onStopTrackingTouch(SeekBar s){}});
        deadzone.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(SeekBar s){remember();}public void onProgressChanged(SeekBar s,int v,boolean user){if(user){layout.deadzone=.05f+v*.003f;touch.changed();}}public void onStopTrackingTouch(SeekBar s){}});
        haptics.setOnCheckedChangeListener((b,checked)->{if(!binding){remember();layout.haptics=checked;dirty=true;}});
        binding=false;
    }
    private void confirmDiscard(){if(!dirty){setResult(RESULT_CANCELED);finish();return;}new AlertDialog.Builder(this).setMessage(R.string.discard).setNegativeButton(R.string.cancel,null).setPositiveButton(R.string.discard,(d,w)->{setResult(RESULT_CANCELED);finish();}).show();}
    @Override public void onBackPressed(){confirmDiscard();}
}
