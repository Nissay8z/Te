package ru.iptvremote.android.iptv.vavoo;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * Android-native duplicate stream drawer (D-pad friendly). IPTV Pro's
 * third-party player has no Smarters settings layout, so we attach one
 * compact "Flux" control to the active player decor, not another TV category.
 */
public final class VavooUiHook {
    private static final int DRAWER_ID=0x1e453aa5;
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static volatile WeakReference<Activity> resumed=new WeakReference<>(null);
    private static volatile boolean installed=false;
    private VavooUiHook(){}
    private static int dp(Context c,int v) {
        return (int)(v*c.getResources().getDisplayMetrics().density+0.5f);
    }
    public static synchronized void install(Application app){
        if(installed)return;
        installed=true;
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks(){
            public void onActivityCreated(Activity a, Bundle b){}
            public void onActivityStarted(Activity a){}
            public void onActivityResumed(Activity a){
                resumed=new WeakReference<>(a);
                // Only show after the player actually requests VAVOO playback.
                if(VavooStreamServer.wasPlayingRecently(3500))onPlaying();
            }
            public void onActivityPaused(Activity a){remove(a);}
            public void onActivityStopped(Activity a){}
            public void onActivitySaveInstanceState(Activity a,Bundle b){}
            public void onActivityDestroyed(Activity a){remove(a);}
        });
    }
    public static void onPlaying(){
        MAIN.post(new Runnable(){@Override public void run(){
            Activity a=resumed.get();
            if(a==null||a.isFinishing()||a.isDestroyed())return;
            String id=VavooStreamServer.playingId();
            if(VavooVariants.sources(id).isEmpty())return;
            FrameLayout root=null;
            View decor=a.getWindow().getDecorView();
            if(decor instanceof FrameLayout)root=(FrameLayout)decor;
            if(root==null)return;
            View existing=root.findViewById(DRAWER_ID);
            if(existing instanceof TextView){
                ((TextView)existing).setText("⇄ Flux ("+VavooVariants.sources(id).size()+") · 4 miroirs");
                return;
            }
            TextView button=new TextView(a);
            button.setId(DRAWER_ID);
            button.setText("⇄ Flux ("+VavooVariants.sources(id).size()+") · 4 miroirs");
            button.setTextSize(14);
            button.setTypeface(null,Typeface.BOLD);
            button.setTextColor(Color.WHITE);
            button.setBackgroundColor(Color.argb(235,28,31,44));
            button.setPadding(dp(a,14),dp(a,10),dp(a,14),dp(a,10));
            button.setFocusable(true);
            button.setClickable(true);
            button.setElevation(dp(a,10));
            button.setContentDescription("Ouvrir les sources de la chaîne VAVOO");
            button.setOnClickListener(new View.OnClickListener(){
                @Override public void onClick(View v){show(a);}
            });
            FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.END|Gravity.BOTTOM);
            lp.rightMargin=dp(a,20);
            lp.bottomMargin=dp(a,80);
            root.addView(button,lp);
        }});
    }
    private static void remove(Activity a){
        try{
            View decor=a.getWindow().getDecorView();
            View v=decor.findViewById(DRAWER_ID);
            if(v!=null && v.getParent() instanceof ViewGroup)
                ((ViewGroup)v.getParent()).removeView(v);
        }catch(Exception ignored){}
    }
    private static void show(final Activity a){
        final String id=VavooStreamServer.playingId();
        final List<VavooVariants.Source> variants=VavooVariants.sources(id);
        if(variants.isEmpty()){
            Toast.makeText(a,"Cette chaîne n'a pas d'alternative VAVOO",Toast.LENGTH_LONG).show();
            return;
        }
        ArrayList<String> names=new ArrayList<>();
        String mirror=VavooVariants.mirror(id);
        names.add("🌐 Miroir : "+(mirror.isEmpty()?"AUTO (4 serveurs)":mirror)+"  ▾");
        names.add("⚡ Flux automatique : qualité puis secours");
        String selected=VavooVariants.getSelected(id);
        int checked=selected.isEmpty()?1:-1;
        for(int i=0;i<variants.size();i++){
            VavooVariants.Source option=variants.get(i);
            names.add((i+1)+". "+option.name+"  ["+option.score+"]");
            if(option.id.equals(selected))checked=i+2;
        }
        new AlertDialog.Builder(a)
            .setTitle("⇄ VAVOO · "+variants.size()+" flux / 4 miroirs")
            .setSingleChoiceItems(names.toArray(new String[0]),checked,
                (dialog,which)->{
                    dialog.dismiss();
                    if(which==0){showMirrors(a,id);return;}
                    String idToSet=which==1?"":variants.get(which-2).id;
                    if(!VavooVariants.select(id,idToSet)){
                        Toast.makeText(a,"Variante indisponible",Toast.LENGTH_SHORT).show();
                        return;
                    }
                    refreshPlayback(a,id);
                })
            .setNegativeButton("Fermer",null)
            .show();
    }
    private static void showMirrors(final Activity a,final String id){
        String selected=VavooVariants.mirror(id);
        ArrayList<String> labels=new ArrayList<>();
        labels.add("⚡ Automatique — rechercher sur les 4 miroirs");
        int checked=selected.isEmpty()?0:-1;
        for(int i=0;i<VavooVariants.MIRRORS.length;i++){
            String name=VavooVariants.MIRRORS[i];
            labels.add("🌐 "+name);
            if(name.equals(selected))checked=i+1;
        }
        new AlertDialog.Builder(a).setTitle("🌐 Choisir un miroir VAVOO")
            .setSingleChoiceItems(labels.toArray(new String[0]),checked,
                (dialog,which)->{
                    String mirror=which==0?"":VavooVariants.MIRRORS[which-1];
                    if(VavooVariants.selectMirror(id,mirror)){
                        dialog.dismiss();
                        refreshPlayback(a,id);
                    }
                })
            .setNegativeButton("Retour",(dialog,which)->show(a)).show();
    }
    private static void refreshPlayback(Activity a,String id){
        // Never destroy or recreate the current Activity merely to switch a
        // stream. For non-VideoView players, record choice for next Play.
        boolean replay=replayOnSameScreen(a,id);
        Toast.makeText(a,replay?"Source appliquée au lecteur":
            "Choix enregistré · relance la lecture sur cette chaîne",
            Toast.LENGTH_LONG).show();
    }
    private static boolean replayOnSameScreen(Activity a,String id){
        // Supported direct Android VideoView players can restart without
        // recreating the Activity. Other IPTV Pro engines are left untouched
        // rather than closing the player or dropping back to channel list.
        try{
            VideoView v=findVideo(a.getWindow().getDecorView());
            if(v!=null){
                v.setVideoURI(Uri.parse(
                    "http://127.0.0.1:"+VavooStreamServer.PORT+
                    "/play/"+Uri.encode(id)+"?switch="+System.currentTimeMillis()));
                v.start();
                return true;
            }
        }catch(Exception ignored){}
        return false;
    }
    private static VideoView findVideo(View v) {
        if(v instanceof VideoView)return (VideoView)v;
        if(v instanceof ViewGroup){
            ViewGroup g=(ViewGroup)v;
            for(int i=0;i<g.getChildCount();i++){
                VideoView result=findVideo(g.getChildAt(i));
                if(result!=null)return result;
            }
        }
        return null;
    }
}
