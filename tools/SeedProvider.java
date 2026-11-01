package ru.iptvremote.android.iptv.vavoo;
import android.content.*;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.SystemClock;
import android.util.Log;
import java.net.*;
import java.io.*;
import java.util.*;
import java.util.regex.*;
public final class SeedProvider extends ContentProvider {
  static final String URL="https://raw.githubusercontent.com/Nissay8z/Te/main/iptv-pro-vavoo-local.m3u";
  static final String V3_URL="https://raw.githubusercontent.com/Nissay8z/Te/main/iptv-global.m3u";
  static final String OLD_URL="https://raw.githubusercontent.com/Nissay8z/Te/main/iptv.m3u";
  static final String EPG="https://raw.githubusercontent.com/Nissay8z/Te/main/epg.xml";
  static final Pattern P=Pattern.compile("([a-zA-Z][a-zA-Z0-9-]*)=\"([^\"]*)\"");
  static String attr(String s,String n) {
    Matcher m=P.matcher(s);while(m.find())if(n.equalsIgnoreCase(m.group(1)))return m.group(2);return "";
  }
  static String load(Context ctx) throws Exception {
    // Bundle an actual copy of the playlist inside the APK. This avoids a
    // silent empty installation if GitHub/VAVOO is unreachable on first launch.
    try {
      InputStream in=ctx.getAssets().open("vavoo/iptv.m3u");
      BufferedReader r=new BufferedReader(new InputStreamReader(in,"UTF-8"));
      StringBuilder b=new StringBuilder();
      char[] buf=new char[8192];int n;
      while((n=r.read(buf))!=-1&&b.length()<12000000)b.append(buf,0,n);
      r.close();
      if(b.toString().startsWith("#EXTM3U")&&b.toString().contains("#EXTINF:"))return b.toString();
    }catch(Exception e){Log.w("VavooSeed","Bundled M3U unavailable, trying network",e);}
    return loadRemote();
  }
  static String loadRemote() throws Exception {
    HttpURLConnection c=(HttpURLConnection)new URL(URL).openConnection();
    c.setConnectTimeout(15000);c.setReadTimeout(20000);
    c.setRequestProperty("User-Agent","Mozilla/5.0 Android IPTV");
    if(c.getResponseCode()!=200)throw new IOException("M3U HTTP "+c.getResponseCode());
    BufferedReader r=new BufferedReader(new InputStreamReader(c.getInputStream(),"UTF-8"));
    StringBuilder b=new StringBuilder();char[] arr=new char[16384];int n;
    while((n=r.read(arr))!=-1&&b.length()<12000000)b.append(arr,0,n);
    r.close();c.disconnect();return b.toString();
  }
  static boolean column(SQLiteDatabase db,String table,String name) {
    Cursor c=db.rawQuery("PRAGMA table_info("+table+")",null);
    try {while(c.moveToNext())if(name.equals(c.getString(1)))return true;return false;}
    finally {c.close();}
  }
  static long getId(SQLiteDatabase db) {
    Cursor c=db.rawQuery("SELECT id FROM Playlist WHERE url=?",new String[]{URL});
    try{return c.moveToFirst()?c.getLong(0):-1;}finally{c.close();}
  }
  static long oldId(SQLiteDatabase db) {
    Cursor c=db.rawQuery("SELECT id FROM Playlist WHERE url=?",new String[]{OLD_URL});
    try{return c.moveToFirst()?c.getLong(0):-1;}finally{c.close();}
  }
  static boolean migrateWorldV3(Context ctx) throws Exception {
    SQLiteDatabase db=SQLiteDatabase.openDatabase(
      ctx.getDatabasePath("iptv.db").getPath(),null,SQLiteDatabase.OPEN_READWRITE);
    try {
      if(getId(db)>=0)return false;
      Cursor c=db.rawQuery("SELECT id FROM Playlist WHERE url=?",new String[]{V3_URL});
      long id;
      try {if(!c.moveToFirst())return false;id=c.getLong(0);}finally{c.close();}
      db.beginTransaction();
      try {
        ContentValues p=new ContentValues();
        p.put("url",URL);p.put("name","VAVOO | TV MONDE");
        db.update("Playlist",p,"id=?",new String[]{""+id});
        // Preserve channels, categories, favourites and user settings.
        db.execSQL(
          "UPDATE Channel SET url='http://127.0.0.1:18497/play/' || "+
          "substr(url,instr(url,'/play/')+length('/play/')) "+
          "WHERE playlistId=? AND url LIKE '%/play/%'",
          new Object[]{id});
        db.setTransactionSuccessful();
        Log.i("VavooSeed","Upgraded existing global V3 to native live resolver");
        return true;
      } finally{db.endTransaction();}
    }finally{db.close();}
  }
  static boolean exists(Context ctx) {
    try {
      SQLiteDatabase db=SQLiteDatabase.openDatabase(
        ctx.getDatabasePath("iptv.db").getPath(),null,SQLiteDatabase.OPEN_READONLY);
      try{
        long id=getId(db);
        if(id<0)return false;
        Cursor c=db.rawQuery("SELECT COUNT(*) FROM Channel WHERE playlistId=?",new String[]{""+id});
        try{return c.moveToFirst()&&c.getInt(0)>50;}finally{c.close();}
      }finally{db.close();}
    }catch(Exception ignored){return false;}
  }
  static void refresh(Context ctx) {
    // The original app observes Room transactions; direct external SQLite
    // writes may not notify its already-open UI. Reopen the launcher once.
    try {
      android.content.Intent launch=ctx.getPackageManager()
          .getLaunchIntentForPackage(ctx.getPackageName());
      if(launch!=null){
        launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK|
                        android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK);
        ctx.startActivity(launch);
      }
    }catch(Exception e){Log.w("VavooSeed","Relaunch failed; manually reopen the app",e);}
  }
  static boolean install(Context ctx,String m3u) throws Exception {
    SQLiteDatabase db=SQLiteDatabase.openDatabase(ctx.getDatabasePath("iptv.db").getPath(),null,SQLiteDatabase.OPEN_READWRITE);
    try {
      long id=getId(db);
      if(id>=0){
        Cursor cc=db.rawQuery("SELECT COUNT(*) FROM Channel WHERE playlistId=?",new String[]{""+id});
        try {if(cc.moveToFirst()&&cc.getLong(0)>50)return true;}finally{cc.close();}
      }
      db.beginTransaction();
      try{
        if(id<0){
          // Upgrade our own V2 test playlist instead of leaving the old
          // Turkey-heavy snapshot next to the new international catalogue.
          // Other user-installed playlists are not touched.
          long legacy=oldId(db);
          if(legacy>=0){
            String[] ids={""+legacy};
            db.delete("ChannelCategory","channelId IN (SELECT id FROM Channel WHERE playlistId=?)",ids);
            db.delete("Channel","playlistId=?",ids);
            db.delete("Category","playlistId=?",ids);
            ContentValues newProfile=new ContentValues();
            newProfile.put("url",URL);
            newProfile.put("name","VAVOO | TV MONDE");
            newProfile.put("updateTime",0L);
            db.update("Playlist",newProfile,"id=?",ids);
            id=legacy;
          }
        }
        if(id<0){
          ContentValues v=new ContentValues();
          v.put("url",URL);v.put("name","VAVOO | TV MONDE");
          v.put("accessTime",0L);v.put("updateTime",0L);
          v.put("status",2);v.put("enableLive",1);v.put("enableSeries",0);
          v.put("enableVod",0);v.put("format",1);
          if(column(db,"Playlist","pages"))v.put("pages",0);
          id=db.insertOrThrow("Playlist",null,v);
        }
        Map<String,Long> groups=new HashMap<>();
        String pending=null;
        int num=0;
        for(String s:m3u.split("\\r?\\n")){
          s=s.trim();
          if(s.startsWith("#EXTINF:")){pending=s;continue;}
          if(s.isEmpty()||s.startsWith("#")||pending==null)continue;
          if(!s.startsWith("https://")&&!s.startsWith("http://")){pending=null;continue;}
          s=VavooStreamServer.localUrl(s);
          String name=pending.contains(",")?pending.substring(pending.indexOf(',')+1).trim():"";
          if(name.isEmpty())name=attr(pending,"tvg-name");
          if(name.isEmpty()){pending=null;continue;}
          String group=attr(pending,"group-title");
          if(group.isEmpty())group="Autres";
          Long cat=groups.get(group);
          if(cat==null){
            Cursor c=db.rawQuery("SELECT id FROM Category WHERE playlistId=? AND title=?",new String[]{""+id,group});
            try {if(c.moveToFirst())cat=c.getLong(0);}finally{c.close();}
            if(cat==null){
              ContentValues cv=new ContentValues();cv.put("playlistId",id);
              cv.put("title",group);cv.put("parentalControl",0);
              cat=db.insertOrThrow("Category",null,cv);
            }
            groups.put(group,cat);
          }
          ContentValues ch=new ContentValues();
          ch.put("playlistId",id);ch.put("number",++num);
          ch.put("url",s);ch.put("name",name);
          ch.put("normalizedName",name.toLowerCase(Locale.ROOT).trim());
          ch.put("timeShift",0);
          ch.put("tvgId",attr(pending,"tvg-id"));
          ch.put("tvgName",attr(pending,"tvg-name"));
          String logo=attr(pending,"tvg-logo");
          if(!logo.isEmpty())ch.put("logo",logo);
          long chId=db.insertOrThrow("Channel",null,ch);
          ContentValues link=new ContentValues();
          link.put("channelId",chId);link.put("categoryId",cat);
          db.insertOrThrow("ChannelCategory",null,link);
          pending=null;
        }
        if(num<2000)throw new IOException("World M3U incomplete: only "+num+" channels");
        Cursor ec=db.rawQuery("SELECT id FROM TvgSource WHERE url=?",new String[]{EPG});
        boolean exists;
        try {exists=ec.moveToFirst();}finally{ec.close();}
        if(!exists){
          ContentValues e=new ContentValues();
          e.put("url",EPG);e.put("type",1);e.put("playlistId",id);
          e.put("enabled",1);e.put("lastUpdated",0L);
          db.insertOrThrow("TvgSource",null,e);
        }
        db.setTransactionSuccessful();
        Log.i("VavooSeed","Imported "+num+" stations, "+groups.size()+" groups and XMLTV source");
        return true;
      }finally{db.endTransaction();}
    }finally{db.close();}
  }
  public boolean onCreate(){
    VavooStreamServer.start();
    final Context ctx=getContext().getApplicationContext();
    Thread worker=new Thread(new Runnable(){public void run(){
      for(int attempt=0;attempt<30;attempt++){
        SystemClock.sleep(attempt==0?2000:3000);
        try{
          if(!ctx.getDatabasePath("iptv.db").exists())continue;
          if(migrateWorldV3(ctx)){
            SystemClock.sleep(700);refresh(ctx);return;
          }
          if(exists(ctx))return;
          String m3u=load(ctx);
          if(m3u.startsWith("#EXTM3U")&&install(ctx,m3u)){
            // Give SQLite WAL connections a moment to settle before relaunch.
            SystemClock.sleep(700);
            refresh(ctx);
            return;
          }
        }catch(Exception e){Log.w("VavooSeed","Auto-import attempt "+attempt,e);}
      }
    }},"IPTV-Pro-VAVOO-import");
    worker.setDaemon(true);worker.start();return true;
  }
  public Cursor query(Uri u,String[] a,String s,String[] b,String o){return null;}
  public String getType(Uri u){return null;}
  public Uri insert(Uri u,ContentValues v){return null;}
  public int delete(Uri u,String s,String[] a){return 0;}
  public int update(Uri u,ContentValues v,String s,String[] a){return 0;}
}
