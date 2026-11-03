package com.nisskenzi.tv;

import android.app.Activity;
import android.app.Application;
import android.app.AlertDialog;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.Toast;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public final class TvInputProvider extends ContentProvider implements Application.ActivityLifecycleCallbacks {
    private static final int ID=0x3f4b0901;
    private static final String[] QUALITY = {
        "Auto (adapte au debit)", "SD - 480p max", "HD - 720p max",
        "Full HD - 1080p max", "Qualite maximale disponible"
    };
    private static final int[] LIMITS={0,480,720,1080,Integer.MAX_VALUE};
    @Override public boolean onCreate() {
        Context c=getContext();
        if(c!=null && c.getApplicationContext() instanceof Application)
            ((Application)c.getApplicationContext()).registerActivityLifecycleCallbacks(this);
        return true;
    }
    @Override public Cursor query(Uri u,String[] p,String s,String[] a,String o){return null;}
    @Override public String getType(Uri u){return null;}
    @Override public Uri insert(Uri u,ContentValues v){return null;}
    @Override public int delete(Uri u,String s,String[] a){return 0;}
    @Override public int update(Uri u,ContentValues v,String s,String[] a){return 0;}

    private static boolean television(Activity a) {
        return (a.getResources().getConfiguration().uiMode & Configuration.UI_MODE_TYPE_MASK)
            == Configuration.UI_MODE_TYPE_TELEVISION;
    }
    @Override public void onActivityResumed(Activity a) {
        if(!television(a)) return;
        a.getWindow().getDecorView().post(()->attach(a));
    }
    private void attach(Activity a){
        if(a.isFinishing())return;
        View cv=a.findViewById(android.R.id.content);
        if(!(cv instanceof FrameLayout)) return;
        FrameLayout root=(FrameLayout)cv;
        View old=root.findViewById(ID);
        final CursorOverlay overlay;
        if(old instanceof CursorOverlay)overlay=(CursorOverlay)old;
        else {
            overlay=new CursorOverlay(a,root);
            overlay.setId(ID);
            root.addView(overlay,new FrameLayout.LayoutParams(-1,-1));
        }
        overlay.requestFocus();
        int mode=mode(a);
        root.postDelayed(()->applyQuality(root,mode),1600);
        root.postDelayed(()->applyQuality(root,mode),6500);
    }
    static int mode(Context context) {
        int m=context.getSharedPreferences("moviefr_tv_quality_v2",0).getInt("mode",0);
        return (m>=0 && m<LIMITS.length)?m:0;
    }
    static void choose(Activity a, FrameLayout root,CursorOverlay overlay) {
        if(a.isFinishing())return;
        AlertDialog dlg=new AlertDialog.Builder(a)
            .setTitle("MovieFR TV - Qualite video")
            .setSingleChoiceItems(QUALITY,mode(a),(dialog,which)->{
                a.getSharedPreferences("moviefr_tv_quality_v2",0).edit()
                    .putInt("mode",which).apply();
                int updated=applyQuality(root,which);
                dialog.dismiss();
                overlay.invalidate();
                Toast.makeText(a,updated>0?
                    "Reglage transmis au lecteur - sous reserve des pistes du flux":
                    "Reglage enregistre. Le lecteur n'est pas encore detecte.",
                    Toast.LENGTH_LONG).show();
            })
            .setNegativeButton("Fermer",(d,w)->d.dismiss())
            .create();
        dlg.setOnDismissListener(d->overlay.requestFocus());
        dlg.show();
    }

    private static void inspect(View view, List<Object> list, int depth){
        if(view==null || depth>28 || list.size()>20)return;
        Object p=getPlayer(view);
        if(p!=null && canControl(p) && !list.contains(p))list.add(p);
        if(view instanceof ViewGroup){
            ViewGroup group=(ViewGroup)view;
            for(int i=0;i<group.getChildCount();i++) inspect(group.getChildAt(i),list,depth+1);
        }
    }
    private static Object getPlayer(View view){
        // Do not inspect arbitrary application objects, only UI player views.
        String n=view.getClass().getName().toLowerCase(java.util.Locale.ROOT);
        if(!n.contains("player") && !n.contains("video"))return null;
        try {return view.getClass().getMethod("getPlayer").invoke(view);}
        catch(Throwable ignored) {return null;}
    }
    private static boolean canControl(Object p){
        try {p.getClass().getMethod("getTrackSelectionParameters");return true;}
        catch(Throwable ignored){return false;}
    }
    private static boolean call(Object target,String method,Class<?>[] types,Object... values){
        try {target.getClass().getMethod(method,types).invoke(target,values);return true;}
        catch(Throwable ignored){return false;}
    }
    private static Object buildParams(Object old,int maxHeight){
        try {
            Object b=old.getClass().getMethod("buildUpon").invoke(old);
            applyBounds(b,maxHeight);
            return b.getClass().getMethod("build").invoke(b);
        } catch(Throwable ignored) {}
        try {
            Object b=old.getClass().getMethod("toBuilder").invoke(old);
            applyBounds(b,maxHeight);
            return b.getClass().getMethod("build").invoke(b);
        } catch(Throwable ignored) {}
        return null;
    }
    private static void applyBounds(Object b,int maxHeight){
        // Track selection constraints are local to the actual bitrate/resolution
        // already offered by the source. They cannot create HD or unlock content.
        int max=maxHeight==Integer.MAX_VALUE?Integer.MAX_VALUE:maxHeight;
        int width=max==Integer.MAX_VALUE?Integer.MAX_VALUE:
            (max==480?854:(max==720?1280:1920));
        call(b,"setMaxVideoSize",new Class[]{int.class,int.class},width,max==0?Integer.MAX_VALUE:max);
        call(b,"setForceHighestSupportedBitrate",new Class[]{boolean.class},maxHeight==Integer.MAX_VALUE);
        if(maxHeight==0){
            call(b,"setMaxVideoSize",new Class[]{int.class,int.class},Integer.MAX_VALUE,Integer.MAX_VALUE);
            call(b,"setForceHighestSupportedBitrate",new Class[]{boolean.class},false);
        }
    }
    private static int applyQuality(View root,int chosen){
        List<Object> players=new ArrayList<>();
        inspect(root,players,0);
        int applied=0;
        for(Object player:players){
            try {
                Object previous=player.getClass().getMethod("getTrackSelectionParameters").invoke(player);
                if(previous==null)continue;
                Object params=buildParams(previous,LIMITS[chosen]);
                if(params==null)continue;
                for(Method method:player.getClass().getMethods()){
                    if(!"setTrackSelectionParameters".equals(method.getName()))continue;
                    Class<?>[] types=method.getParameterTypes();
                    if(types.length==1 && types[0].isInstance(params)){
                        method.invoke(player,params);
                        applied++;
                        break;
                    }
                }
            }catch(Throwable ignored) {}
        }
        return applied;
    }
    private static final class CursorOverlay extends View{
        private final FrameLayout root;
        private final Activity activity;
        private final Paint paint=new Paint(3);
        private float x=-1,y=-1;
        private final int step;
        CursorOverlay(Activity a,FrameLayout target) {
            super(a);activity=a;root=target;
            step=Math.round(52*a.getResources().getDisplayMetrics().density);
            setFocusable(true);setFocusableInTouchMode(true);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        @Override protected void onDraw(Canvas canvas){
            super.onDraw(canvas);
            if(x<0 || y<0){x=getWidth()/2f;y=getHeight()/2f;}
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(4);paint.setColor(0xff141d2b);
            canvas.drawCircle(x,y,17,paint);
            paint.setStrokeWidth(3);paint.setColor(0xfffb4864);
            canvas.drawCircle(x,y,14,paint);
            paint.setStyle(Paint.Style.FILL);canvas.drawCircle(x,y,3,paint);
            paint.setColor(0xdd0a1425);
            canvas.drawRoundRect(10,getHeight()-54,getWidth()-10,getHeight()-8,8,8,paint);
            paint.setColor(Color.WHITE);paint.setTextSize(17);
            canvas.drawText("TV | Fleches: curseur   OK: clic   MENU/INFO: qualite",
                25,getHeight()-29,paint);
            paint.setTextSize(13);paint.setColor(0xffa3baf0);
            canvas.drawText(QUALITY[mode(activity)],25,getHeight()-13,paint);
        }
        private void click(){
            if(root.getChildCount()<2)return;
            View app=root.getChildAt(0);
            if(app==this)return;
            int[] a=new int[2],b=new int[2];
            app.getLocationOnScreen(a);getLocationOnScreen(b);
            float tx=x+b[0]-a[0],ty=y+b[1]-a[1];
            long now=SystemClock.uptimeMillis();
            MotionEvent down=MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,tx,ty,0);
            MotionEvent up=MotionEvent.obtain(now,now+35,MotionEvent.ACTION_UP,tx,ty,0);
            try {app.dispatchTouchEvent(down);app.dispatchTouchEvent(up);}
            finally {down.recycle();up.recycle();}
        }
        @Override public boolean onKeyDown(int key,KeyEvent e){
            if(x<0 || y<0){x=getWidth()/2f;y=getHeight()/2f;}
            switch(key){
                case KeyEvent.KEYCODE_MENU:
                case KeyEvent.KEYCODE_INFO:
                case KeyEvent.KEYCODE_GUIDE:
                    if(e.getRepeatCount()==0)choose(activity,root,this);
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:x=Math.max(6,x-step);break;
                case KeyEvent.KEYCODE_DPAD_RIGHT:x=Math.min(getWidth()-6,x+step);break;
                case KeyEvent.KEYCODE_DPAD_UP:y=Math.max(6,y-step);break;
                case KeyEvent.KEYCODE_DPAD_DOWN:y=Math.min(getHeight()-6,y+step);break;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    if(e.getRepeatCount()==0)click();
                    break;
                default:return super.onKeyDown(key,e);
            }
            invalidate();return true;
        }
        @Override public boolean onKeyUp(int key,KeyEvent e){
            if(key==KeyEvent.KEYCODE_DPAD_LEFT || key==KeyEvent.KEYCODE_DPAD_RIGHT ||
               key==KeyEvent.KEYCODE_DPAD_UP || key==KeyEvent.KEYCODE_DPAD_DOWN ||
               key==KeyEvent.KEYCODE_DPAD_CENTER || key==KeyEvent.KEYCODE_ENTER ||
               key==KeyEvent.KEYCODE_MENU || key==KeyEvent.KEYCODE_GUIDE ||
               key==KeyEvent.KEYCODE_INFO) return true;
            return super.onKeyUp(key,e);
        }
    }
    @Override public void onActivityCreated(Activity a,Bundle b){}
    @Override public void onActivityStarted(Activity a){}
    @Override public void onActivityPaused(Activity a){}
    @Override public void onActivityStopped(Activity a){}
    @Override public void onActivitySaveInstanceState(Activity a,Bundle b){}
    @Override public void onActivityDestroyed(Activity a){}
}