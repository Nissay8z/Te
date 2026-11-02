package ru.iptvremote.android.iptv.vavoo;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.SystemClock;
import android.util.Log;
import java.io.*;
import java.util.*;
import java.util.regex.*;

/**
 * IPTV Pro v6 public-channel bootstrap. Uses the app's REAL Room table schema
 * as recovered from its classes3.dex CREATE TABLE statements. The previous
 * v5 bootstrap wrote accessTime=0, pages=0 and sometimes left a previous
 * playlist selected, making channels look absent. This version imports
 * separate verified M3U snapshots offline and marks a populated playlist
 * current. No premium channels, keys, credentials or DRM circumvention.
 */
public final class SeedProvider extends ContentProvider {
  private static final String TAG="TVSeedV6";
  private static final String PREFIX="https://raw.githubusercontent.com/Nissay8z/Te/main/";
  private static final String V5_URL=PREFIX+"iptv-pro-vavoo-organized.m3u";
  private static final String V4_URL=PREFIX+"iptv-pro-vavoo-local.m3u";
  private static final String V3_URL=PREFIX+"iptv-global.m3u";
  private static final String EPG=PREFIX+"epg.xml";
  private static final Pattern P=Pattern.compile("([A-Za-z][A-Za-z0-9-]*)=\"([^\"]*)\"");
  // Only the two standalone M3U profiles explicitly requested.
  // ONYX's Vegeta/OLA/TV Hub remain native ONYX providers, NOT fake M3U links.
  private static final String[][] FEEDS={
    {"iptv_org","IPTV-org · Pays du monde","https://iptv-org.github.io/iptv/index.country.m3u","900"},
    {"vavoo","VAVOO · TV organisée",V5_URL,"3500"}
  };
  private static final String[] RETIRED_FEEDS={
    "https://raw.githubusercontent.com/Free-TV/IPTV/master/playlist.m3u8",
    "https://raw.githubusercontent.com/BuddyChewChew/app-m3u-generator/main/playlists/plutotv_fr.m3u",
    "https://raw.githubusercontent.com/BuddyChewChew/app-m3u-generator/main/playlists/plex_fr.m3u",
    "https://raw.githubusercontent.com/BuddyChewChew/app-m3u-generator/main/playlists/samsungtvplus_fr.m3u"
  };
  private static String attr(String s,String n){
    Matcher m=P.matcher(s);while(m.find())if(n.equalsIgnoreCase(m.group(1)))return m.group(2);return "";
  }
  private static String readAsset(Context ctx,String path)throws Exception{
    InputStream f=ctx.getAssets().open(path);
    try(BufferedReader br=new BufferedReader(new InputStreamReader(f,"UTF-8"))){
      StringBuilder b=new StringBuilder();char[] buf=new char[65536];int n;
      while((n=br.read(buf))>0){
        if(b.length()+n>23000000)throw new IOException("M3U too big");
        b.append(buf,0,n);
      }
      if(!b.toString().trim().startsWith("#EXTM3U"))throw new IOException("Bad bundled M3U "+path);
      return b.toString();
    }
  }
  private static boolean column(SQLiteDatabase db,String table,String name){
    try(Cursor c=db.rawQuery("PRAGMA table_info("+table+")",null)){
      while(c.moveToNext())if(name.equals(c.getString(1)))return true;
      return false;
    }
  }
  private static long getId(SQLiteDatabase db,String url){
    try(Cursor c=db.rawQuery("SELECT id FROM Playlist WHERE url=?",new String[]{url})){
      return c.moveToFirst()?c.getLong(0):-1;
    }
  }
  private static int count(SQLiteDatabase db,long id){
    try(Cursor c=db.rawQuery("SELECT COUNT(*) FROM Channel WHERE playlistId=?",new String[]{""+id})){
      return c.moveToFirst()?c.getInt(0):0;
    }
  }
  private static long now(){return System.currentTimeMillis();}
  private static void touch(SQLiteDatabase db,long id,long timestamp){
    ContentValues up=new ContentValues();
    up.put("accessTime",timestamp);
    up.put("status",2);
    if(column(db,"Playlist","pages"))up.put("pages",7);
    db.update("Playlist",up,"id=?",new String[]{""+id});
  }
  private static void addGuide(SQLiteDatabase db,long id,String url){
    if(!url.startsWith("https://")||url.length()>300) return;
    try(Cursor c=db.rawQuery("SELECT id FROM TvgSource WHERE playlistId=? AND url=?",new String[]{""+id,url})){
      if(c.moveToFirst())return;
    }
    ContentValues v=new ContentValues();
    v.put("url",url);v.put("type",1);v.put("playlistId",id);v.put("enabled",1);
    v.put("lastUpdated",0L);
    db.insertWithOnConflict("TvgSource",null,v,SQLiteDatabase.CONFLICT_IGNORE);
  }
  private static long createPlaylist(SQLiteDatabase db,String url,String name,long timestamp){
    ContentValues p=new ContentValues();
    p.put("url",url);p.put("name",name);p.put("accessTime",timestamp);
    p.put("updateTime",now());p.put("status",2);
    p.put("enableLive",1);p.put("enableSeries",0);p.put("enableVod",0);
    p.put("format",1);
    if(column(db,"Playlist","pages"))p.put("pages",7);
    return db.insertOrThrow("Playlist",null,p);
  }
  private static int insertRows(SQLiteDatabase db,long playlistId,String m3u)throws Exception{
    HashMap<String,Long> catIds=new HashMap<>();
    String pending=null;int idx=0;
    BufferedReader br=new BufferedReader(new StringReader(m3u));
    for(String row;(row=br.readLine())!=null;){
      row=row.trim();
      if(row.startsWith("#EXTINF:")){pending=row;continue;}
      if(pending==null||row.isEmpty()||row.startsWith("#"))continue;
      if(!(row.startsWith("https://")||row.startsWith("http://"))){pending=null;continue;}
      String url=row;
      if(url.contains("/vavoo-iptv/play/")||url.contains("/kool-iptv/play/"))
        url=VavooStreamServer.localUrl(url);
      String name=pending.contains(",")?pending.substring(pending.indexOf(',')+1).trim():"";
      if(name.isEmpty())name=attr(pending,"tvg-name");
      if(name.isEmpty()){pending=null;continue;}
      String group=attr(pending,"group-title");
      if(group.isEmpty())group="Général";
      Long categoryId=catIds.get(group);
      if(categoryId==null){
        try(Cursor cc=db.rawQuery("SELECT id FROM Category WHERE playlistId=? AND title=?",
            new String[]{""+playlistId,group})){
          if(cc.moveToFirst())categoryId=cc.getLong(0);
        }
        if(categoryId==null){
          ContentValues c=new ContentValues();
          c.put("playlistId",playlistId);c.put("title",group);c.put("parentalControl",0);
          categoryId=db.insertOrThrow("Category",null,c);
        }
        catIds.put(group,categoryId);
      }
      ContentValues ch=new ContentValues();
      ch.put("playlistId",playlistId);ch.put("number",++idx);
      ch.put("url",url);ch.put("name",name);
      ch.put("normalizedName",name.toLowerCase(Locale.ROOT));
      ch.put("timeShift",0);
      String logo=attr(pending,"tvg-logo");
      if(!logo.isEmpty())ch.put("logo",logo);
      String tvgId=attr(pending,"tvg-id");
      if(!tvgId.isEmpty())ch.put("tvgId",tvgId);
      String tvgName=attr(pending,"tvg-name");
      if(!tvgName.isEmpty())ch.put("tvgName",tvgName);
      // Channel.type is nullable in the original Room schema.
      // The native M3U importer leaves it unset for ordinary live streams.
      long channelId=db.insertOrThrow("Channel",null,ch);
      ContentValues cc=new ContentValues();
      cc.put("channelId",channelId);cc.put("categoryId",categoryId);
      db.insertOrThrow("ChannelCategory",null,cc);
      pending=null;
    }
    return idx;
  }
  private static boolean installOne(Context ctx,String[] feed,long timestamp)throws Exception{
    String slug=feed[0],name=feed[1],url=feed[2];
    int minimum=Integer.parseInt(feed[3]);
    SQLiteDatabase db=SQLiteDatabase.openDatabase(ctx.getDatabasePath("iptv.db").getPath(),null,SQLiteDatabase.OPEN_READWRITE);
    try{
      long id=getId(db,url);
      if(id>=0 && count(db,id)>=minimum){
        // Fix V5's accessTime=0 / pages=0 bug without deleting favourites.
        touch(db,id,timestamp);
        return false;
      }
      String path=slug.equals("vavoo")?"vavoo/iptv.m3u":"feeds/"+slug+".m3u";
      String m3u=readAsset(ctx,path);
      int observed=m3u.split("#EXTINF:",-1).length-1;
      if(observed<minimum)throw new IOException("Incomplete bundled feed: "+slug+" "+observed);
      db.beginTransaction();
      try{
        // If an old bad version exists, append only its missing entries.
        // Preserve all existing favourites/entries instead of destructive reset.
        if(id<0)id=createPlaylist(db,url,name,timestamp);
        int existing=count(db,id);
        int added=0;
        if(existing<minimum){
          if(existing>0) {
            // Partial data is not safe to append without duplicate checks.
            // Restore it on next native playlist refresh; add the other
            // public sources now rather than deleting a user's data.
            Log.w(TAG,"Existing partial source left unchanged: "+slug+" "+existing);
          }else{
            added=insertRows(db,id,m3u);
          }
        }
        int finalCount=existing+added;
        if(finalCount<minimum){
          if(existing>0){db.setTransactionSuccessful();return false;}
          throw new IOException("Imported "+finalCount+" channels, minimum "+minimum);
        }
        String epg=attr(m3u.split("\\r?\\n",2)[0],"url-tvg");
        addGuide(db,id,epg);
        if(slug.equals("vavoo"))addGuide(db,id,EPG);
        touch(db,id,timestamp);
        db.setTransactionSuccessful();
        Log.i(TAG,"Seeded "+name+": "+finalCount+" channels; profile="+id);
        return true;
      }finally{db.endTransaction();}
    }finally{db.close();}
  }
  private static void moveOldV5ToCurrent(Context ctx)throws Exception{
    // Old V5 already contains the grouped channels, do not reimport it.
    SQLiteDatabase db=SQLiteDatabase.openDatabase(ctx.getDatabasePath("iptv.db").getPath(),null,SQLiteDatabase.OPEN_READWRITE);
    try {
      long id=getId(db,V5_URL);
      if(id>=0&&count(db,id)>3000)touch(db,id,now()-15000);
    }finally{db.close();}
  }
  private static void removeRetiredTestProfiles(Context ctx){
    // Remove ONLY the four previously auto-added test profiles.
    // No user-added URLs or unrelated IPTV playlist are changed.
    SQLiteDatabase db=null;
    try{
      db=SQLiteDatabase.openDatabase(ctx.getDatabasePath("iptv.db").getPath(),
          null,SQLiteDatabase.OPEN_READWRITE);
      for(String url:RETIRED_FEEDS){
        long id=getId(db,url);
        if(id<0)continue;
        String[] bind={""+id};
        db.beginTransaction();
        try{
          db.delete("ChannelCategory","channelId IN (SELECT id FROM Channel WHERE playlistId=?)",bind);
          db.delete("Channel","playlistId=?",bind);
          db.delete("Category","playlistId=?",bind);
          db.delete("TvgSource","playlistId=?",bind);
          db.delete("Playlist","id=?",bind);
          db.setTransactionSuccessful();
        }finally{db.endTransaction();}
      }
    }catch(Exception e){Log.w(TAG,"Retired profiles cleanup skipped",e);}
    finally{if(db!=null)db.close();}
  }
  private static void restart(Context ctx){
    try{
      Intent app=ctx.getPackageManager().getLaunchIntentForPackage(ctx.getPackageName());
      if(app!=null){
        app.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP);
        ctx.startActivity(app);
      }
    }catch(Exception e){Log.w(TAG,"Could not reopen player; manually reopen IPTV Pro",e);}
  }
  @Override public boolean onCreate(){
    Context ctx=getContext().getApplicationContext();
    VavooVariants.init(ctx);
    VavooUiHook.install((android.app.Application)ctx);
    VavooStreamServer.start();
    Thread worker=new Thread(()->{
      for(int i=0;i<90&&!ctx.getDatabasePath("iptv.db").exists();i++)
        SystemClock.sleep(2000);
      if(!ctx.getDatabasePath("iptv.db").exists()){
        Log.e(TAG,"IPTV app database missing after startup");return;
      }
      try{moveOldV5ToCurrent(ctx);}catch(Exception e){Log.w(TAG,"V5 migration check failed",e);}
      // V11->V12: merge only aliases in the existing VAVOO profile,
      // preserving Channel preference/EPG/favorite references.
      int merged=0;
      try{merged=VavooDeduplicator.migrate(ctx);}
      catch(Exception e){Log.e(TAG,"VAVOO duplicate migration failed; SQLite rollback",e);}
      removeRetiredTestProfiles(ctx);
      long base=now();
      boolean addedAny=merged>0;
      int countOK=0;
      // IPTV-org first: opens a directly playable public TV catalogue.
      // VAVOO remains the second native source, with alternative stream drawer.
      for(int i=0;i<FEEDS.length;i++){
        String[] feed=FEEDS[i];
        long access=base-((long)i*12000L);
        for(int retry=0;retry<2;retry++){
          try{
            boolean added=installOne(ctx,feed,access);
            if(added)addedAny=true;
            countOK++;
            // Do not relaunch in the middle of SQLite imports.
            break;
          }catch(Exception e){
            Log.e(TAG,"Source import failed: "+feed[0]+" retry="+retry,e);
            SystemClock.sleep(2000);
          }
        }
      }
      Log.i(TAG,"Startup completed: "+countOK+"/"+FEEDS.length+" profiles");
      if(addedAny){SystemClock.sleep(750);restart(ctx);}
    },"IPTV-Pro-VAVOO-IPTVorg-v7-import");
    worker.setDaemon(true);
    worker.start();
    return true;
  }
  @Override public Cursor query(Uri u,String[] columns,String s,String[] b,String o){return null;}
  @Override public String getType(Uri u){return null;}
  @Override public Uri insert(Uri u,ContentValues v){return null;}
  @Override public int delete(Uri u,String s,String[] b){return 0;}
  @Override public int update(Uri u,ContentValues v,String s,String[] b){return 0;}
}
