package ru.iptvremote.android.iptv.vavoo;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;
import java.util.ArrayList;

/** Upgrade the V11 installed VAVOO catalogue without discarding favorites.
 *  This only changes obsolete local channel aliases belonging to VAVOO,
 *  never IPTV-org, any other user playlist, or the actual original source IDs.
 */
public final class VavooDeduplicator {
    private static final String TAG="VavooDedupV12";
    private static final String DONE="dedup_v13_applied";
    private static final String PREFIX="http://127.0.0.1:18497/play/";
    private static final String PROFILE_URL=
        "https://raw.githubusercontent.com/Nissay8z/Te/main/iptv-pro-vavoo-organized.m3u";
    private VavooDeduplicator(){}

    private static long channelId(SQLiteDatabase db,long playlistId,String url) {
        try(Cursor c=db.rawQuery(
            "SELECT id FROM Channel WHERE playlistId=? AND url=? LIMIT 1",
            new String[]{String.valueOf(playlistId),url})) {
            return c.moveToFirst()?c.getLong(0):-1L;
        }
    }
    private static String name(SQLiteDatabase db,long id) {
        try(Cursor c=db.rawQuery("SELECT name FROM Channel WHERE id=?",
            new String[]{String.valueOf(id)})) {
            return c.moveToFirst()?c.getString(0):"";
        }
    }
    private static boolean has(SQLiteDatabase db,String table) {
        try(Cursor c=db.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1",
            new String[]{table})) {return c.moveToFirst();}
    }
    private static Map<String,String> redirects(Context context)throws Exception {
        Map<String,String> map=new HashMap<>();
        try(BufferedReader r=new BufferedReader(new InputStreamReader(
            context.getAssets().open("vavoo/redirect.tsv"),"UTF-8"))) {
            for(String line;(line=r.readLine())!=null;) {
                String[] p=line.split("\t",-1);
                if(p.length!=2||!p[0].matches("g[0-9a-f]{24}")||
                    !p[1].matches("g[0-9a-f]{24}")||p[0].equals(p[1]))
                    throw new IllegalStateException("Invalid redirect row");
                if(map.put(p[0],p[1])!=null)
                    throw new IllegalStateException("Repeated redirect row");
            }
        }
        if(map.size()<100||map.size()>1000)
            throw new IllegalStateException("Incomplete VAVOO dedup index "+map.size());
        return map;
    }
    private static void transfer(SQLiteDatabase db,long oldId,long newId,
            String oldName,String newName,String oldUrl,String newUrl) {
        if(has(db,"ChannelPreferenceByUrl")) {
            db.execSQL("INSERT OR IGNORE INTO ChannelPreferenceByUrl "+
                "(channelUrl,favorite,sortId,recent) "+
                "SELECT ?,favorite,sortId,recent FROM ChannelPreferenceByUrl WHERE channelUrl=?",
                new Object[]{newUrl,oldUrl});
            db.execSQL("UPDATE ChannelPreferenceByUrl SET favorite=1 WHERE channelUrl=? "+
                "AND EXISTS (SELECT 1 FROM ChannelPreferenceByUrl "+
                "WHERE channelUrl=? AND favorite=1)",new Object[]{newUrl,oldUrl});
        }
        if(has(db,"ChannelPreferenceByName")) {
            db.execSQL("INSERT OR IGNORE INTO ChannelPreferenceByName "+
                "(channelName,favorite,parentalControl,logoUri,sortId,recent,position,duration) "+
                "SELECT ?,favorite,parentalControl,logoUri,sortId,recent,position,duration "+
                "FROM ChannelPreferenceByName WHERE channelName=?",
                new Object[]{newName,oldName});
            db.execSQL("UPDATE ChannelPreferenceByName SET favorite=1 WHERE channelName=? "+
                "AND EXISTS (SELECT 1 FROM ChannelPreferenceByName "+
                "WHERE channelName=? AND favorite=1)",new Object[]{newName,oldName});
        }
        if(has(db,"ChannelCategory")) {
            db.execSQL("INSERT OR IGNORE INTO ChannelCategory(channelId,categoryId) "+
                "SELECT ?,categoryId FROM ChannelCategory WHERE channelId=?",
                new Object[]{newId,oldId});
        }
        if(has(db,"ChannelTvgChannel")) {
            db.execSQL("INSERT OR IGNORE INTO ChannelTvgChannel "+
                "(tvgSourceId,channelId,tvgChannelId) "+
                "SELECT tvgSourceId,?,tvgChannelId FROM ChannelTvgChannel WHERE channelId=?",
                new Object[]{newId,oldId});
        }
        if(has(db,"ChannelExtras")) {
            db.execSQL("INSERT OR IGNORE INTO ChannelExtras "+
                "(channelId,externalId,description,backgroundIcon,\"cast\",director,genre,rating) "+
                "SELECT ?,externalId,description,backgroundIcon,\"cast\",director,genre,rating "+
                "FROM ChannelExtras WHERE channelId=?",new Object[]{newId,oldId});
        }
        if(has(db,"FavoriteReference")) {
            // Preserve the original bookmarked URL but point it to the
            // surviving channel. No foreign-key dangling favorites.
            db.execSQL("UPDATE FavoriteReference SET favoriteId=? WHERE favoriteId=?",
                new Object[]{newId,oldId});
        }
    }

    public static int migrate(Context ctx)throws Exception {
        SharedPreferences state=ctx.getSharedPreferences("vavoo_dedup_version",
            Context.MODE_PRIVATE);
        if(state.getBoolean(DONE,false))return 0;
        Map<String,String> redirects=redirects(ctx);
        SQLiteDatabase db=SQLiteDatabase.openDatabase(
            ctx.getDatabasePath("iptv.db").getPath(),null,SQLiteDatabase.OPEN_READWRITE);
        try {
            long playlistId=-1;
            try(Cursor c=db.rawQuery("SELECT id FROM Playlist WHERE url=? LIMIT 1",
                new String[]{PROFILE_URL})) {
                if(c.moveToFirst())playlistId=c.getLong(0);
            }
            if(playlistId<0)return 0;
            int removed=0,unresolved=0;
            ArrayList<String[]> saved=new ArrayList<>();
            db.beginTransaction();
            try {
                for(Map.Entry<String,String> row:redirects.entrySet()) {
                    String oldUrl=PREFIX+row.getKey(),newUrl=PREFIX+row.getValue();
                    long oldId=channelId(db,playlistId,oldUrl);
                    if(oldId<0)continue;
                    long newId=channelId(db,playlistId,newUrl);
                    if(newId<0) {
                        // Never remove an alias if its destination is missing.
                        unresolved++;
                        continue;
                    }
                    transfer(db,oldId,newId,name(db,oldId),name(db,newId),oldUrl,newUrl);
                    if(db.delete("Channel","id=? AND playlistId=?",
                        new String[]{String.valueOf(oldId),String.valueOf(playlistId)})==1) {
                        removed++;
                        saved.add(new String[]{row.getKey(),row.getValue()});
                    }
                }
                db.setTransactionSuccessful();
            }finally{db.endTransaction();}
            for(String[] pair:saved)VavooVariants.migrateSaved(pair[0],pair[1]);
            if(unresolved==0)state.edit().putBoolean(DONE,true).commit();
            Log.i(TAG,"Removed "+removed+" stale channel rows, unresolved "+unresolved);
            return removed;
        }finally{db.close();}
    }
}
