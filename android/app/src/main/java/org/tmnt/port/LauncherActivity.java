package org.tmnt.port;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import org.tmnt.port.ui.Ui;
import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class LauncherActivity extends Activity {
    private static final int PICK_PACK = 41, PICK_TREE = 42;
    private PortSettings settings;
    private TextView status, statusBody;
    private Button play, importButton;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean importing = new AtomicBoolean(), cancelled = new AtomicBoolean();
    private ProgressDialog progress;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); settings = new PortSettings(this);
        getWindow().setStatusBarColor(Ui.BG); getWindow().setNavigationBarColor(Ui.BG);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(Ui.BG);
        LinearLayout page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL);
        int pad=Ui.dp(this,24); page.setPadding(pad,pad,pad,pad); scroll.addView(page); setContentView(scroll); Ui.safePadding(scroll);
        TextView subtitle=Ui.text(this,getString(BuildConfig.EMBEDDED_GAME?R.string.subtitle_private:R.string.subtitle),11,Ui.GREEN);
        page.addView(subtitle); Ui.gap(page,this,22);
        page.addView(Ui.text(this,getString(R.string.hero),38,Ui.TEXT)); Ui.gap(page,this,12);
        page.addView(Ui.text(this,getString(R.string.hero_body),16,Ui.MUTED)); Ui.gap(page,this,28);
        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL); card.setPadding(pad,pad,pad,pad);
        card.setBackground(Ui.round(Ui.CARD,20,this)); page.addView(card,Ui.match());
        status=Ui.title(this,R.string.not_ready,20); statusBody=Ui.text(this,"",14,Ui.MUTED);
        card.addView(status); Ui.gap(card,this,8); card.addView(statusBody); Ui.gap(card,this,18);
        play=Ui.button(this,R.string.play,true); importButton=Ui.button(this,R.string.import_data,false);
        card.addView(play,Ui.match()); Ui.gap(card,this,10); card.addView(importButton,Ui.match());
        Ui.gap(page,this,18);
        Button edit=Ui.button(this,R.string.edit_controls,false); page.addView(edit,Ui.match()); Ui.gap(page,this,10);
        Button options=Ui.button(this,R.string.settings,false); page.addView(options,Ui.match()); Ui.gap(page,this,22);
        TextView abi=Ui.text(this,"",13,0xFFEBC17A); page.addView(abi);
        if (supported()) abi.setVisibility(View.GONE);
        else abi.setText(R.string.unsupported_abi);
        page.addView(Ui.text(this,getString(R.string.legal),12,Ui.MUTED)); Ui.gap(page,this,18);
        LinearLayout tools=new LinearLayout(this); tools.setOrientation(LinearLayout.HORIZONTAL); page.addView(tools);
        Button licenses=Ui.button(this,R.string.licenses,false); licenses.setTextSize(13);
        Button log=Ui.button(this,R.string.log,false); log.setTextSize(13);
        tools.addView(licenses,new LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1));
        tools.addView(new View(this),new LinearLayout.LayoutParams(Ui.dp(this,8),1));
        tools.addView(log,new LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1));
        Ui.gap(page,this,10); Button delete=Ui.button(this,R.string.delete,false); delete.setTextSize(13); page.addView(delete,Ui.match());
        play.setOnClickListener(v -> launch()); importButton.setOnClickListener(v -> pick());
        edit.setOnClickListener(v -> startActivity(new Intent(this,EditorActivity.class)));
        options.setOnClickListener(v -> settingsDialog());
        licenses.setOnClickListener(v -> showLicenses()); log.setOnClickListener(v -> shareLog());
        delete.setOnClickListener(v -> new AlertDialog.Builder(this).setMessage(R.string.delete_confirm)
            .setNegativeButton(R.string.cancel,null).setPositiveButton(R.string.delete,(d,w)->{
                try { GamePack.remove(this); refresh(); } catch(Exception e){ error(e); }
            }).show());
    }
    @Override protected void onResume() { super.onResume(); refresh(); }
    private boolean supported() { for(String a:Build.SUPPORTED_ABIS) if(GamePack.abi().equals(a)) return true; return false; }
    private void refresh() {
        boolean ready=GamePack.installed(this), ok=supported();
        status.setText(ready?R.string.ready:R.string.not_ready);
        if (BuildConfig.EMBEDDED_GAME) statusBody.setText(ready?R.string.embedded_ready_body:R.string.embedded_not_ready_body);
        else statusBody.setText(ready?R.string.ready_body:R.string.not_ready_body);
        play.setEnabled(ready&&ok&&!importing.get()); play.setAlpha(play.isEnabled()?1:.4f);
        importButton.setText(ready ? (BuildConfig.EMBEDDED_GAME?R.string.replace_pc_data:R.string.replace_data)
                                   : (BuildConfig.EMBEDDED_GAME?R.string.import_pc_data:R.string.import_data));
        importButton.setEnabled(ok&&!importing.get());
    }
    private void launch() {
        try {
            if (BuildConfig.EMBEDDED_GAME) EmbeddedGame.ensureImage(this);
            settings.writeNative(this); startActivity(new Intent(this,GameActivity.class));
        } catch(Exception e){ error(e); }
    }
    private void pick() {
        if (BuildConfig.EMBEDDED_GAME) {
            new AlertDialog.Builder(this).setTitle(R.string.import_data)
                .setItems(new String[]{getString(R.string.import_zip), getString(R.string.import_folder)}, (d,w)->{ if(w==0) pickZip(); else pickFolder(); })
                .setNegativeButton(R.string.cancel,null).show();
        } else pickZip();
    }
    private void pickZip() {
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*"); i.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"application/zip","application/x-zip-compressed","application/octet-stream"});
        startActivityForResult(i,PICK_PACK);
    }
    private void pickFolder() {
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(i,PICK_TREE);
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if((request!=PICK_PACK && request!=PICK_TREE) || result!=RESULT_OK || data==null || data.getData()==null) return;
        if(!importing.compareAndSet(false,true)) return;
        cancelled.set(false); Uri uri=data.getData(); boolean tree=request==PICK_TREE; refresh();
        progress=new ProgressDialog(this); progress.setMessage(getString(R.string.importing)); progress.setCancelable(false);
        progress.setButton(ProgressDialog.BUTTON_NEGATIVE,getString(R.string.cancel),(d,w)->cancelled.set(true)); progress.show();
        executor.execute(()->{
            try {
                if (tree) InstalledGameImporter.importTree(getApplicationContext(),uri,cancelled,s->runOnUiThread(()->{if(progress!=null)progress.setMessage(s);}));
                else if (BuildConfig.EMBEDDED_GAME) InstalledGameImporter.importZip(getApplicationContext(),uri,cancelled,s->runOnUiThread(()->{if(progress!=null)progress.setMessage(s);}));
                else PackImporter.importZip(getApplicationContext(),uri,cancelled,s->runOnUiThread(()->{if(progress!=null)progress.setMessage(s);}));
                runOnUiThread(()->{finishImport();Toast.makeText(this,R.string.import_ok,Toast.LENGTH_LONG).show();});
            } catch(Exception e) { runOnUiThread(()->{finishImport();if(!cancelled.get())error(e);}); }
        });
    }
    private void finishImport() { importing.set(false); if(progress!=null){progress.dismiss();progress=null;} refresh(); }
    private void error(Exception e) { new AlertDialog.Builder(this).setTitle(R.string.error).setMessage(String.valueOf(e.getMessage())).setPositiveButton(R.string.ok,null).show(); }
    private void settingsDialog() {
        LinearLayout box=new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); int p=Ui.dp(this,24); box.setPadding(p,p,p,p);
        ScrollView scroll=new ScrollView(this); scroll.addView(box);
        box.addView(Ui.text(this,getString(R.string.profile),16,Ui.TEXT));
        RadioGroup group=new RadioGroup(this); int[] labels={R.string.balanced,R.string.battery,R.string.performance};
        final int[] ids={R.id.profile_balanced,R.id.profile_cool,R.id.profile_performance};
        for(int i=0;i<3;i++){RadioButton b=new RadioButton(this);b.setId(ids[i]);b.setText(labels[i]);group.addView(b);} group.check(ids[settings.profile()]); box.addView(group);
        Ui.gap(box,this,14); TextView scale=Ui.text(this,getString(R.string.render_scale)+" · "+Math.round(settings.scale()*100)+"%",15,Ui.TEXT); box.addView(scale);
        SeekBar seek=new SeekBar(this); seek.setMax(75); seek.setProgress(Math.round((settings.scale()-.25f)*100)); box.addView(seek);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar s,int v,boolean u){scale.setText(getString(R.string.render_scale)+" · "+(v+25)+"%");}public void onStartTrackingTouch(SeekBar s){}public void onStopTrackingTouch(SeekBar s){}});
        CheckBox aspect=new CheckBox(this);aspect.setText(R.string.aspect);aspect.setChecked(settings.aspect43());box.addView(aspect);
        CheckBox sound=new CheckBox(this);sound.setText(R.string.sound);sound.setChecked(settings.sound());box.addView(sound);
        CheckBox perf=new CheckBox(this);perf.setText(R.string.perf);perf.setChecked(settings.perf());box.addView(perf);
        box.addView(Ui.text(this,getString(R.string.settings_note),12,Ui.MUTED));
        new AlertDialog.Builder(this).setTitle(R.string.settings).setView(scroll).setNegativeButton(R.string.cancel,null)
            .setPositiveButton(R.string.save,(d,w)->{int profile=0;for(int i=0;i<ids.length;i++)if(group.getCheckedRadioButtonId()==ids[i])profile=i;settings.put("profile",profile);settings.put("scale",(seek.getProgress()+25)/100f);
                settings.put("aspect43",aspect.isChecked());settings.put("sound",sound.isChecked());settings.put("perf",perf.isChecked());}).show();
    }
    private void showLicenses() {
        StringBuilder text=new StringBuilder();
        try { for(String name:getAssets().list("licenses")) { text.append(name).append("\n\n");
            try(java.io.InputStream in=getAssets().open("licenses/"+name)){byte[]b=new byte[8192];int n;while((n=in.read(b))>=0)text.append(new String(b,0,n,java.nio.charset.StandardCharsets.UTF_8));}text.append("\n\n"); } }
        catch(Exception e){text.append(e.getMessage());}
        new AlertDialog.Builder(this).setTitle(R.string.licenses).setMessage(text.toString()).setPositiveButton(R.string.ok,null).show();
    }
    private void shareLog() {
        executor.execute(()->{
            try {
                File dir=new File(getCacheDir(),"diagnostics");dir.mkdirs();final File f=new File(dir,"tmnt-log.txt");
                Process p=new ProcessBuilder("logcat","-d","-v","time","-s","TMNT:V","SDL:V","SDL/APP:V","AndroidRuntime:E").redirectOutput(f).start();
                if(p.waitFor()!=0)throw new java.io.IOException("logcat failed");
                runOnUiThread(()->{
                    Uri uri=DiagnosticProvider.uri(this,f);
                    Intent i=new Intent(Intent.ACTION_SEND);i.setType("text/plain");i.putExtra(Intent.EXTRA_STREAM,uri);i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(i,getString(R.string.log)));
                });
            } catch(Exception e){runOnUiThread(()->error(e));}
        });
    }
    @Override protected void onDestroy(){if(progress!=null)progress.dismiss();executor.shutdownNow();super.onDestroy();}
}
