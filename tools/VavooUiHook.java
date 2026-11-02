package ru.iptvremote.android.iptv.vavoo;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
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
    private static final int FLUX_BUTTON_ID=0x1e453aa6;
    private static final int MIRROR_BUTTON_ID=0x1e453aa7;
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
            updateControls(a,(LinearLayout)old,id);
            return;
        }

        LinearLayout strip=new LinearLayout(a);
        strip.setId(STRIP_ID);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        strip.setGravity(Gravity.CENTER);
        strip.setPadding(dp(a,8),dp(a,3),dp(a,8),dp(a,5));
        strip.setClipChildren(false);
        strip.setClipToPadding(false);
        bar.setClipChildren(false);
        bar.setClipToPadding(false);
        TextView sources=createButton(a,"Flux · "+VavooVariants.sources(id).size());
        TextView mirrors=createButton(a,mirrorTitle(id));
        sources.setId(FLUX_BUTTON_ID);
        mirrors.setId(MIRROR_BUTTON_ID);
        sources.setNextFocusRightId(MIRROR_BUTTON_ID);
        mirrors.setNextFocusLeftId(FLUX_BUTTON_ID);
        // Native Android TV DPAD focus events automatically update the
        // ring, colour, size, text and accessibility description.
        sources.setOnFocusChangeListener((view,focused)->
            paintFocus((TextView)view,sourceTitle(VavooStreamServer.playingId())));
        mirrors.setOnFocusChangeListener((view,focused)->
            paintFocus((TextView)view,mirrorTitle(VavooStreamServer.playingId())));
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
        updateControls(a,strip,id);
        // On Android TV, give first-time users an unmistakable initial
        // highlighted target when the playback controls are shown.
        sources.post(()->{
            if(a.isFinishing()||a.isDestroyed())return;
            if(strip.isShown() && a.hasWindowFocus() && bar.findFocus()==null)
                sources.requestFocus();
        });
        Log.i(TAG,"TV remote focus-highlight controls inserted into IPTV Pro player");
    }

    private static TextView createButton(Activity a,String label){
        TextView button=new TextView(a);
        button.setText(label);
        button.setTextSize(15.5f);
        button.setSingleLine(true);
        button.setEllipsize(TextUtils.TruncateAt.END);
        button.setTypeface(null,Typeface.BOLD);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(a,7),0,dp(a,7),0);
        button.setFocusable(true);
        button.setFocusableInTouchMode(false);
        button.setClickable(true);
        paintFocus(button,label);
        return button;
    }

    private static String sourceTitle(String id){
        java.util.List<VavooVariants.Source> variants=VavooVariants.sources(id);
        String selected=VavooVariants.getSelected(id);
        if(!selected.isEmpty()){
            for(VavooVariants.Source source:variants){
                if(source.id.equals(selected)){
                    // Keep long channel names in the drawer. The control shows
                    // a short status suitable for a 720p TV screen.
                    return "FLUX · Manuel";
                }
            }
        }
        return "FLUX · "+variants.size()+" sources";
    }
    private static void updateControls(Activity a,LinearLayout strip,String id){
        if(strip.getChildCount()<2)return;
        View first=strip.findViewById(FLUX_BUTTON_ID);
        View second=strip.findViewById(MIRROR_BUTTON_ID);
        if(first instanceof TextView)
            paintFocus((TextView)first,sourceTitle(id));
        if(second instanceof TextView)
            paintFocus((TextView)second,mirrorTitle(id));
    }

    /**
     * The remote's focused control is unmistakable:
     * - Bright yellow background and white focus ring
     * - Dark high-contrast bold text with ▶ and OK prompt
     * - +6% scale and raised elevation
     * Normal controls remain dark slate with a subtle grey outline.
     * No need to tap the screen or guess where the cursor is.
     */
    private static void paintFocus(TextView button,String label){
        Context context=button.getContext();
        boolean focused=button.isFocused();
        GradientDrawable background=new GradientDrawable();
        background.setShape(GradientDrawable.RECTANGLE);
        background.setCornerRadius(dp(context,9));
        background.setColor(focused?Color.rgb(255,213,61):Color.rgb(26,34,49));
        background.setStroke(dp(context,focused?3:1),
            focused?Color.WHITE:Color.rgb(98,111,133));
        button.setBackground(background);
        button.setTextColor(focused?Color.rgb(15,22,35):Color.WHITE);
        button.setText((focused?"▶ ":"")+label+(focused?" · OK":""));
        button.setTextSize(focused?16f:15.5f);
        button.setScaleX(focused?1.06f:1f);
        button.setScaleY(focused?1.06f:1f);
        button.setElevation(dp(context,focused?9:2));
        button.setContentDescription((focused?"Sélection actuelle, appuyez sur OK : ":"Bouton ")+label);
        button.invalidate();
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
