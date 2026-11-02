package com.nisskenzi.tv;

import android.app.Activity;
import android.app.Application;
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
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

public final class TvInputProvider extends ContentProvider implements Application.ActivityLifecycleCallbacks {
    @Override public boolean onCreate() {
        Context c = getContext();
        if (c != null && c.getApplicationContext() instanceof Application) {
            ((Application)c.getApplicationContext()).registerActivityLifecycleCallbacks(this);
        }
        return true;
    }
    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
    @Override public String getType(Uri u) { return null; }
    @Override public Uri insert(Uri u, ContentValues v) { return null; }
    @Override public int delete(Uri u, String s, String[] a) { return 0; }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }

    @Override public void onActivityResumed(Activity a) {
        if ((a.getResources().getConfiguration().uiMode & Configuration.UI_MODE_TYPE_MASK) != Configuration.UI_MODE_TYPE_TELEVISION) return;
        a.getWindow().getDecorView().post(() -> attach(a));
    }
    private void attach(Activity a) {
        if (a.isFinishing()) return;
        View existing=a.findViewById(0x3f4b0901);
        if (existing instanceof CursorOverlay) {
            existing.requestFocus(); return;
        }
        View contentView=a.findViewById(android.R.id.content);
        if (!(contentView instanceof FrameLayout)) return;
        FrameLayout content=(FrameLayout)contentView;
        CursorOverlay overlay=new CursorOverlay(a,content);
        overlay.setId(0x3f4b0901);
        content.addView(overlay,new FrameLayout.LayoutParams(-1,-1));
        overlay.requestFocus();
    }

    private static final class CursorOverlay extends View {
        private final FrameLayout root;
        private final Paint paint=new Paint(3);
        private float x=-1,y=-1;
        private final int step;
        CursorOverlay(Context c, FrameLayout parent) {
            super(c);root=parent;
            step=Math.round(52*c.getResources().getDisplayMetrics().density);
            setFocusable(true);
            setFocusableInTouchMode(true);
            setClickable(false);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (x<0 || y<0) {x=getWidth()/2f;y=getHeight()/2f;}
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(4f);
            paint.setColor(0xff1b2333);
            canvas.drawCircle(x,y,17f,paint);
            paint.setStrokeWidth(3f);
            paint.setColor(0xfffb4864);
            canvas.drawCircle(x,y,14f,paint);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(x,y,3f,paint);
            paint.setColor(0x99000000);
            canvas.drawRoundRect(12,getHeight()-45,getWidth()-12,getHeight()-10,6,6,paint);
            paint.setColor(Color.WHITE);
            paint.setTextSize(18f);
            canvas.drawText("TV  |  Fleches : curseur     OK : clic     Retour : revenir",24,getHeight()-22,paint);
        }
        private void click() {
            if (root.getChildCount()<2) return;
            View app=root.getChildAt(0);
            if (app==this) return;
            int[] a=new int[2], b=new int[2];
            app.getLocationOnScreen(a);getLocationOnScreen(b);
            float tx=x+b[0]-a[0],ty=y+b[1]-a[1];
            long now=android.os.SystemClock.uptimeMillis();
            MotionEvent down=MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,tx,ty,0);
            MotionEvent up=MotionEvent.obtain(now,now+35,MotionEvent.ACTION_UP,tx,ty,0);
            app.dispatchTouchEvent(down);app.dispatchTouchEvent(up);
            down.recycle();up.recycle();
        }
        @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
            if (x<0 || y<0) {x=getWidth()/2f;y=getHeight()/2f;}
            switch(keyCode) {
                case KeyEvent.KEYCODE_DPAD_LEFT:x=Math.max(6,x-step);break;
                case KeyEvent.KEYCODE_DPAD_RIGHT:x=Math.min(getWidth()-6,x+step);break;
                case KeyEvent.KEYCODE_DPAD_UP:y=Math.max(6,y-step);break;
                case KeyEvent.KEYCODE_DPAD_DOWN:y=Math.min(getHeight()-6,y+step);break;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:click();break;
                default:return super.onKeyDown(keyCode,event);
            }
            invalidate();return true;
        }
        @Override public boolean onKeyUp(int keyCode, KeyEvent event) {
            switch(keyCode) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:return true;
                default:return super.onKeyUp(keyCode,event);
            }
        }
    }
    @Override public void onActivityCreated(Activity a, Bundle b){}
    @Override public void onActivityStarted(Activity a){}
    @Override public void onActivityPaused(Activity a){}
    @Override public void onActivityStopped(Activity a){}
    @Override public void onActivitySaveInstanceState(Activity a,Bundle b){}
    @Override public void onActivityDestroyed(Activity a){}
}
