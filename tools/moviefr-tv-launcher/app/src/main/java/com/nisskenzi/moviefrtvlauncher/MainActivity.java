package com.nisskenzi.moviefrtvlauncher;

import android.app.Activity;
import android.os.Bundle;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.Settings;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public final class MainActivity extends Activity {
    private static final String PKG="com.mfr.moviefr";
    private final int bg=Color.rgb(11,15,26);
    private LinearLayout layout;
    private TextView status;

    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}

    private GradientDrawable background(int color,int radius){
        GradientDrawable d=new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        return d;
    }

    private TextView text(String value,int sp,int color,boolean bold){
        TextView v=new TextView(this);
        v.setText(value);
        v.setTextSize(sp);
        v.setTextColor(color);
        if(bold)v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        return v;
    }

    private void addText(String value,int sp,int color,boolean bold,int after){
        TextView t=text(value,sp,color,bold);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);
        p.bottomMargin=dp(after);
        layout.addView(t,p);
    }

    private void addAction(String label,boolean accent,Runnable callback){
        Button b=new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(18);
        b.setTextColor(Color.WHITE);
        b.setBackground(background(accent?0xffdf2c53:0xff273044,12));
        b.setFocusable(true);
        b.setOnFocusChangeListener((v,focused)->{
            v.setBackground(background(focused?0xff425dd0:(accent?0xffdf2c53:0xff273044),12));
            v.setScaleX(focused?1.025f:1.0f);
            v.setScaleY(focused?1.025f:1.0f);
        });
        b.setOnClickListener(v->callback.run());
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(60));
        p.bottomMargin=dp(14);
        layout.addView(b,p);
        if(layout.getChildCount()==5)b.requestFocus();
    }

    private boolean installed(){
        try{getPackageManager().getPackageInfo(PKG,0);return true;}
        catch(PackageManager.NameNotFoundException e){return false;}
    }

    private void launch(String activityName){
        if(!installed()){
            Toast.makeText(this,"Installe d'abord l'APK MovieFR originale",Toast.LENGTH_LONG).show();
            return;
        }
        Intent intent=new Intent(Intent.ACTION_MAIN);
        intent.setComponent(new ComponentName(PKG,activityName));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try{startActivity(intent);}
        catch(Exception e){
            Toast.makeText(this,"Ouverture impossible : "+e.getClass().getSimpleName(),
                Toast.LENGTH_LONG).show();
        }
    }

    private void appSettings(){
        Intent intent=new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:"+PKG));
        try{startActivity(intent);}
        catch(Exception e){
            Toast.makeText(this,"Paramètres indisponibles",Toast.LENGTH_SHORT).show();
        }
    }

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        ScrollView scroll=new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(bg);
        layout=new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(35),dp(24),dp(35),dp(24));
        layout.setGravity(Gravity.CENTER_VERTICAL);
        scroll.addView(layout,new ScrollView.LayoutParams(-1,-1));
        setContentView(scroll);

        addText("MOVIEFR  ·  TV",29,Color.WHITE,true,12);
        addText("Lanceur de diagnostic Android TV — conserve l'APK et la signature d'origine.",
            14,0xffcbd5e1,false,24);
        status=text("Vérification...",16,0xffa6e3aa,true);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);
        p.bottomMargin=dp(20);
        layout.addView(status,p);
        addAction("1 · Ouvrir MovieFR normalement",true,
            ()->launch("com.mfr.moviefr.ui.login.splash.SplashActivity"));
        addAction("2 · Tester l'écran principal (sans splash)",false,
            ()->launch("com.mfr.moviefr.ui.MainActivity"));
        addAction("3 · Autorisations et stockage MovieFR",false,()->appSettings());
        addText("Si les deux boutons provoquent le même crash, le problème est dans MovieFR ou son environnement Android TV. Ce lanceur ne change pas son réseau ni sa signature.",
            14,0xffa1aaba,false,0);
        refresh();
    }

    private void refresh(){
        if(status!=null){
            status.setText(installed()?
                "MovieFR originale détectée sur la box ✓":
                "MovieFR originale introuvable — installe-la d'abord");
            status.setTextColor(installed()?0xff9ee2ab:0xffffcc99);
        }
    }

    @Override protected void onResume(){super.onResume();refresh();}
}
