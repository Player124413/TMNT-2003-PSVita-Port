package org.tmnt.port;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;
import org.libsdl.app.SDLActivity;
import org.tmnt.port.ui.TouchView;
import org.tmnt.port.ui.Ui;

public class GameActivity extends SDLActivity {
    private TouchView touch;
    private FrameLayout overlay;
    private TextView perf;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private boolean pausedByMenu, leaving;
    private final Runnable stats=new Runnable(){@Override public void run(){
        if(NativeBridge.loaded&&perf!=null&&perf.getVisibility()==View.VISIBLE){
            perf.setText(String.format(java.util.Locale.US,"%.0f FPS · %.1f ms",NativeBridge.fps(),NativeBridge.frameMs()));
            handler.postDelayed(this,500);
        }
    }};
    @Override protected String[] getLibraries(){return new String[]{"SDL2","tmnt_runtime"};}
    @Override protected String getMainFunction(){return "SDL_main";}
    @Override public void onCreate(Bundle state){
        super.onCreate(state); Ui.immersive(this); Ui.back(this,this::showPause);
        if(mBrokenLibraries)return;
        NativeBridge.loaded=true;
        overlay=new FrameLayout(this); Ui.safePadding(overlay);
        touch=new TouchView(this,ControlLayout.load(this)); touch.setLive(true);
        overlay.addView(touch,new FrameLayout.LayoutParams(-1,-1));
        Button pause=Ui.button(this,R.string.pause,false); pause.setTextSize(12); pause.setMinHeight(Ui.dp(this,40));
        pause.setMinimumHeight(Ui.dp(this,40)); pause.setPadding(Ui.dp(this,12),0,Ui.dp(this,12),0); pause.setAlpha(.65f);
        FrameLayout.LayoutParams pp=new FrameLayout.LayoutParams(Ui.dp(this,88),Ui.dp(this,44),Gravity.TOP|Gravity.START);
        pp.topMargin=Ui.dp(this,6);pp.leftMargin=Ui.dp(this,6);overlay.addView(pause,pp);
        perf=Ui.text(this,"",12,Ui.GREEN); perf.setShadowLayer(3,0,1,0xFF000000);
        FrameLayout.LayoutParams fp=new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.START);fp.bottomMargin=Ui.dp(this,6);fp.leftMargin=Ui.dp(this,8);overlay.addView(perf,fp);
        mLayout.addView(overlay,new RelativeLayout.LayoutParams(-1,-1));
        pause.setOnClickListener(v->showPause());
        boolean show=new PortSettings(this).perf();perf.setVisibility(show?View.VISIBLE:View.GONE);NativeBridge.setPerf(show);
    }
    private void showPause(){
        if(leaving||pausedByMenu)return; pausedByMenu=true; touch.clear(); NativeBridge.setPaused(true);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(R.string.pause).setCancelable(false)
            .setPositiveButton(R.string.resume,(d,w)->{pausedByMenu=false;NativeBridge.setPaused(false);Ui.immersive(this);})
            .setNeutralButton(R.string.edit,(d,w)->startActivityForResult(new Intent(this,EditorActivity.class),71))
            .setNegativeButton(R.string.back_launcher,(d,w)->{leaving=true;NativeBridge.requestQuit();finish();}).create();
        dialog.show();
    }
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==71){touch.setLayout(ControlLayout.load(this));pausedByMenu=false;NativeBridge.setPaused(false);}}
    @Override public boolean onKeyDown(int code,KeyEvent event){if(code==KeyEvent.KEYCODE_BACK){showPause();return true;}return super.onKeyDown(code,event);}
    @Override protected void onPause(){if(touch!=null)touch.clear();if(NativeBridge.loaded)NativeBridge.setPaused(true);handler.removeCallbacks(stats);super.onPause();}
    @Override protected void onResume(){
        super.onResume();Ui.immersive(this);
        if(NativeBridge.loaded&&!pausedByMenu&&!leaving){NativeBridge.setPaused(false);boolean show=new PortSettings(this).perf();NativeBridge.setPerf(show);perf.setVisibility(show?View.VISIBLE:View.GONE);handler.removeCallbacks(stats);if(show)handler.post(stats);}
    }
    @Override protected void onDestroy(){handler.removeCallbacks(stats);if(NativeBridge.loaded)NativeBridge.clearInput();super.onDestroy();}
}
