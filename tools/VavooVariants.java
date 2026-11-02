package ru.iptvremote.android.iptv.vavoo;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * IPTV Pro adaptation of Smarters' ONYX-inspired VavooChannelGroups drawer.
 * A single visible logical channel points to every genuine VAVOO source.
 * Never confuse BEIN SPORTS 1 with MAX or channels from different countries.
 * All 9,538 original stream identifiers remain inside bundled variants.tsv.
 */
public final class VavooVariants {
    public static final class Source {
        public final String id, name;
        public final int score;
        Source(String i, String n, int q) { id=i; name=n; score=q; }
    }
    private static final Map<String, List<Source>> SOURCES=new HashMap<>();
    private static SharedPreferences prefs;
    private static boolean initialized=false;
    // ONYX 1.8.11 VavooMirrorSettings: preferred mirror is app-wide.
    public static final String[] MIRRORS={"vavoo.net","vavoo.to","kool.ws","vavoo.top"};
    public static boolean knownMirror(String name){
        for(String mirror:MIRRORS)if(mirror.equals(name))return true;
        return false;
    }
    public static String mirror(String logicalId){
        if(prefs==null)return "";
        String saved=prefs.getString("vavoo_preferred_mirror","");
        return knownMirror(saved)?saved:"";
    }
    public static String[] orderedMirrors(String logicalId){
        String preferred=mirror(logicalId);
        if(preferred.isEmpty())return MIRRORS.clone();
        java.util.ArrayList<String> ordered=new java.util.ArrayList<>();
        ordered.add(preferred);
        for(String m:MIRRORS)if(!m.equals(preferred))ordered.add(m);
        return ordered.toArray(new String[0]);
    }
    public static synchronized boolean selectMirror(String logicalId,String mirror){
        if(prefs==null)return false;
        if(mirror!=null&&!mirror.isEmpty()&&!knownMirror(mirror))return false;
        if(mirror==null||mirror.isEmpty())
            prefs.edit().remove("vavoo_preferred_mirror").apply();
        else prefs.edit().putString("vavoo_preferred_mirror",mirror).apply();
        // ONYX uses the same preference across the Live TV catalogue.
        VavooStreamServer.invalidateAll();
        return true;
    }
    private VavooVariants() {}
    public static synchronized void init(Context context) {
        if(initialized)return;
        prefs=context.getApplicationContext().getSharedPreferences(
            "iptv_pro_vavoo_drawer_v5",Context.MODE_PRIVATE);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    context.getAssets().open("vavoo/variants.tsv"),"UTF-8"))) {
            String line;int count=0;
            while((line=reader.readLine())!=null){
                String[] p=line.split("\t",4);
                if(p.length!=4 || !p[0].matches("g[0-9a-f]{24}")
                    || !p[1].matches("[A-Za-z0-9_-]{4,120}"))continue;
                int score;
                try{score=Integer.parseInt(p[3]);}catch(NumberFormatException ignored){score=0;}
                List<Source> family=SOURCES.get(p[0]);
                if(family==null){family=new ArrayList<>();SOURCES.put(p[0],family);}
                family.add(new Source(p[1],p[2],score));count++;
            }
            if(count<8000||SOURCES.size()<5000)throw new IllegalStateException(
                "Incomplete drawer index: "+count+" originals / "+SOURCES.size()+" channels");
            initialized=true;
            Log.i("VavooVariants","Loaded "+count+" sources for "+SOURCES.size()+" channels");
        }catch(Exception e){
            SOURCES.clear();
            Log.e("VavooVariants","Unable to load embedded duplicate registry",e);
        }
    }
    public static synchronized List<Source> sources(String logicalId) {
        List<Source> list=SOURCES.get(logicalId);
        return list==null ? Collections.<Source>emptyList()
                          : new ArrayList<>(list);
    }
    public static synchronized List<Source> ranked(String logicalId) {
        List<Source> list=sources(logicalId);
        if(list.isEmpty())return list;
        // Prefer higher-quality variants on automatic playback, preserving
        // the exact original names in the stream-selection drawer.
        java.util.Collections.sort(list,(a,b)->Integer.compare(b.score,a.score));
        String chosen=getSelected(logicalId);
        if(!chosen.isEmpty()){
            for(int i=0;i<list.size();i++){
                if(chosen.equals(list.get(i).id)){
                    Source pref=list.remove(i);
                    list.add(0,pref);
                    break;
                }
            }
        }
        return list;
    }
    public static synchronized boolean select(String logicalId,String sourceId){
        if(prefs==null)return false;
        if(sourceId==null || sourceId.isEmpty()){
            prefs.edit().remove(logicalId).apply();
            VavooStreamServer.invalidate(logicalId);
            return true;
        }
        for(Source s:sources(logicalId))if(s.id.equals(sourceId)){
            prefs.edit().putString(logicalId,sourceId).apply();
            VavooStreamServer.invalidate(logicalId);
            return true;
        }
        return false;
    }
    public static synchronized String getSelected(String logicalId) {
        return prefs==null ? "" : prefs.getString(logicalId,"");
    }
}
