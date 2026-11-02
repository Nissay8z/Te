package ru.iptvremote.android.iptv.vavoo;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.util.Log;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * IPTV Pro's real MediaControllerView layout embeds two VAVOO actions:
 * "Flux" (HD/FHD/s/b/c) and "Miroir" (the four ONYX host preferences).
 * Changes restart the SAME channel using PlaybackService / wm0.g without
 * recreating the activity or returning to the channel list.
 */
public final class VavooUiHook {
    private static final String TAG="VavooPlayerControls";
    private static final String VIDEO_ACTIVITY=
        "ru.iptvremote.android.iptv.common.player.VideoActivity";
    private static final int STRIP_ID=0x1e453aa5;
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static volatile WeakReference<Activity> resumed=new WeakReference<>(null);
    private static volatile boolean installed=false;

    private VavooUiHook(){}
    private static int dp(Context c,int value){
        return (int)(value*c.getResources().getDisplayMetrics().density+0.5f);
    }
    private static boolean isPlayer(Activity activity){
        return activity!=null&&VIDEO_ACTIVITY.equals(activity.getClass().getName());
    }

    public static synchronized void install(Application app){
        if(installed)return;
        installed=true;
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks(){
            public void onActivityCreated(Activity a,Bundle saved){}
            public void onActivityStarted(Activity a){}
            public void onActivityResumed(Activity a){
                if(!isPlayer(a))return;
                resumed=new WeakReference<>(a);
                if(VavooStreamServer.wasPlayingRecently(12000))onPlaying();
            }
            public void onActivityPaused(Activity a){}
            public void onActivityStopped(Activity a){}
            public void onActivitySaveInstanceState(Activity a,Bundle out){}
            public void onActivityDestroyed(Activity a){
                if(resumed.get()==a)resumed=new WeakReference<>(null);
            }
        });
    }

    public static void onPlaying(){
        MAIN.post(()->{
            Activity a=resumed.get();
            if(!isPlayer(a)||a.isFinishing()||a.isDestroyed())return;
            attachIntoNativeControls(a,0);
        });
    }

    private static void attachIntoNativeControls(Activity a,int attempt){
        if(a.isFinishing()||a.isDestroyed())return;
        final String id=VavooStreamServer.playingId();
        if(id==null||VavooVariants.sources(id).isEmpty())return;

        int controlId=a.getResources().getIdentifier("control_bar","id",a.getPackageName());
        View control=controlId==0?null:a.findViewById(controlId);
        if(!(control instanceof LinearLayout)){
            if(attempt<10)MAIN.postDelayed(()->attachIntoNativeControls(a,attempt+1),350);
            else Log.w(TAG,"The native IPTV Pro player controller was not found.");
            return;
        }
        LinearLayout bar=(LinearLayout)control;
        View old=bar.findViewById(STRIP_ID);
        if(old instanceof LinearLayout){
            LinearLayout strip=(LinearLayout)old;
            if(strip.getChildCount()>=2){
                TextView first=(TextView)strip.getChildAt(0);
                first.setText("Flux · "+VavooVariants.sources(id).size());
                TextView mirror=(TextView)strip.getChildAt(1);
                mirror.setText(mirrorTitle(id));
            }
            return;
        }

        LinearLayout strip=new LinearLayout(a);
        strip.setId(STRIP_ID);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        strip.setGravity(Gravity.CENTER);
        strip.setPadding(dp(a,8),dp(a,3),dp(a,8),dp(a,5));
        TextView sources=createButton(a,"Flux · "+VavooVariants.sources(id).size());
        TextView mirrors=createButton(a,mirrorTitle(id));
        sources.setOnClickListener(view->showSources(a));
        mirrors.setOnClickListener(view->showMirrors(a));
        LinearLayout.LayoutParams cell=new LinearLayout.LayoutParams(0,dp(a,42),1);
        cell.setMargins(dp(a,4),0,dp(a,4),0);
        strip.addView(sources,cell);
        LinearLayout.LayoutParams second=new LinearLayout.LayoutParams(0,dp(a,42),1);
        second.setMargins(dp(a,4),0,dp(a,4),0);
        strip.addView(mirrors,second);
        // Added inside IPTV Pro's original control_bar; buttons follow its
        // native show/hide animation and D-pad focus order.
        bar.addView(strip,0,new LinearLayout.LayoutParams(-1,dp(a,50)));
        Log.i(TAG,"VAVOO buttons inserted into native IPTV Pro control bar");
    }

    private static TextView createButton(Activity a,String label){
        TextView b=new TextView(a);
        b.setText(label);
        b.setTextSize(15);
        b.setTypeface(null,Typeface.BOLD);
        b.setTextColor(Color.WHITE);
        b.setGravity(Gravity.CENTER);
        b.setBackgroundColor(Color.argb(230,30,35,48));
        b.setFocusable(true);
        b.setFocusableInTouchMode(false);
        b.setClickable(true);
        b.setContentDescription(label+" — changer sans quitter la lecture");
        b.setElevation(dp(a,2));
        return b;
    }
    private static String mirrorTitle(String id){
        String mirror=VavooVariants.mirror(id);
        return "Miroir · "+(mirror.isEmpty()?"Auto":mirror);
    }

    private static void showSources(final Activity a){
        final String id=VavooStreamServer.playingId();
        final List<VavooVariants.Source> variants=VavooVariants.sources(id);
        if(variants.isEmpty())return;
        ArrayList<String> names=new ArrayList<>();
        names.add("Automatique — meilleure qualité et sources de secours");
        String current=VavooVariants.getSelected(id);
        int checked=current.isEmpty()?0:-1;
        for(int i=0;i<variants.size();i++){
            VavooVariants.Source source=variants.get(i);
            names.add(source.name);
            if(source.id.equals(current))checked=i+1;
        }
        new AlertDialog.Builder(a)
            .setTitle("VAVOO · Flux de cette chaîne")
            .setSingleChoiceItems(names.toArray(new String[0]),checked,(dialog,which)->{
                dialog.dismiss();
                String next=which==0?"":variants.get(which-1).id;
                if(VavooVariants.select(id,next))applyAndReplay(a,id);
            })
            .setNegativeButton("Fermer",null).show();
    }

    private static void showMirrors(final Activity a){
        final String id=VavooStreamServer.playingId();
        String current=VavooVariants.mirror(id);
        ArrayList<String> names=new ArrayList<>();
        names.add("Automatique — miroirs et secours");
        int checked=current.isEmpty()?0:-1;
        for(int i=0;i<VavooVariants.MIRRORS.length;i++){
            names.add(VavooVariants.MIRRORS[i]);
            if(VavooVariants.MIRRORS[i].equals(current))checked=i+1;
        }
        new AlertDialog.Builder(a).setTitle("VAVOO · Miroir du lecteur")
            .setSingleChoiceItems(names.toArray(new String[0]),checked,(dialog,which)->{
                dialog.dismiss();
                String next=which==0?"":VavooVariants.MIRRORS[which-1];
                if(VavooVariants.selectMirror(id,next))applyAndReplay(a,id);
            })
            .setNegativeButton("Fermer",null).show();
    }

    private static void applyAndReplay(Activity a,String previousId){
        // Never restart a different channel if the user changed stations while
        // the selection popup was open.
        if(!previousId.equals(VavooStreamServer.playingId()))return;
        boolean restarted=restartNativePlayback(a);
        attachIntoNativeControls(a,0);
        Toast.makeText(a,restarted
            ?"Nouvelle source appliquée · lecture relancée"
            :"Source enregistrée, redémarrage indisponible",
            Toast.LENGTH_SHORT).show();
    }

    private static Field field(Class<?> type,String name)throws Exception{
        for(Class<?> current=type;current!=null;current=current.getSuperclass()){
            try{
                Field f=current.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            }catch(NoSuchFieldException ignored){}
        }
        throw new NoSuchFieldException(name);
    }

    /**
     * VideoActivity.H -> PlaybackService.e() -> wm0.g(PlayerStartParams).
     * This is the native on-screen IPTV Pro VLC/engine "replay current item"
     * path; no new Intent, Activity.finish or Activity.recreate occurs.
     * wm0.g schedules playback on the service's existing background queue.
     */
    private static boolean restartNativePlayback(Activity a){
        try{
            if(!isPlayer(a))return false;
            Object service=field(a.getClass(),"H").get(a);
            if(service==null)return false;
            Object engine=service.getClass().getMethod("e").invoke(service);
            if(engine==null)return false;
            Class<?> params=Class.forName(
                "ru.iptvremote.android.iptv.common.player.PlayerStartParams");
            Object playbackParameters=params.getDeclaredConstructor().newInstance();
            Method replay=engine.getClass().getMethod("g",params);
            replay.invoke(engine,playbackParameters);
            Log.i(TAG,"Requested player-native replay on the current screen");
            return true;
        }catch(Exception error){
            Log.e(TAG,"IPTV Pro engine replay failed",error);
            return false;
        }
    }
}
