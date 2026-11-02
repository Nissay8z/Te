package ru.iptvremote.android.iptv.vavoo;

import android.net.Uri;
import android.util.Log;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.*;

/**
 * Loopback-only, on-device VAVOO resolver and HLS relay.
 *
 * IPTV Pro's normal player uses http://127.0.0.1:18497/play/<vavoo-id>.
 * When playback starts we call the native VAVOO provider (ping/addonSig +
 * mediahubmx-resolve.json) to obtain a short-lived media URL.
 *
 * Unlike publishing direct /vavoo-iptv/play/<id> links, this keeps resolution
 * fresh for each channel. HLS master/variant playlists and segment URLs are
 * rewritten to pass through this local relay. Nothing is exposed on Wi-Fi.
 */
public final class VavooStreamServer {
    public static final int PORT=18497;
    private static final String TAG="VavooStream";
    private static volatile ServerSocket listener;
    private static volatile String activeLogicalId="";
    private static volatile long activeAt=0L;
    public static String playingId(){return activeLogicalId;}
    public static boolean wasPlayingRecently(long maxMs){
        return !activeLogicalId.isEmpty() &&
          System.currentTimeMillis()-activeAt<maxMs;
    }
    public static void invalidate(String logicalId){resolved.remove(logicalId);}
    public static void invalidateAll(){resolved.clear();}

    private static final Pattern ATTR_URI=Pattern.compile("URI=\"([^\"]+)\"");
    private static final ConcurrentHashMap<String,Cache> resolved=new ConcurrentHashMap<>();
    private static final class Cache {
        final String url;final long until;
        Cache(String u,long t){url=u;until=t;}
    }
    private VavooStreamServer(){}

    public static String localUrl(String remote) {
        try {
            URL u=new URL(remote);
            String marker="/vavoo-iptv/play/";
            String path=u.getPath();
            int pos=path.indexOf(marker);
            if(pos<0)return remote;
            String id=path.substring(pos+marker.length()).replaceAll("/.*$","");
            if(id.isEmpty())return remote;
            return "http://127.0.0.1:"+PORT+"/play/"+Uri.encode(Uri.decode(id));
        }catch(Exception e){return remote;}
    }

    public static synchronized void start(){
        if(listener!=null&&!listener.isClosed())return;
        Thread t=new Thread(new Runnable(){
            @Override public void run(){
                ServerSocket socket=null;
                try {
                    socket=new ServerSocket(PORT,32,InetAddress.getByName("127.0.0.1"));
                    listener=socket;
                    Log.i(TAG,"Loopback playback resolver listening on 127.0.0.1:"+PORT);
                    while(!socket.isClosed()){
                        final Socket client=socket.accept();
                        client.setSoTimeout(35000);
                        Thread worker=new Thread(new Runnable(){
                            @Override public void run(){serve(client);}
                        },"Vavoo-HLS-client");
                        worker.setDaemon(true);worker.start();
                    }
                }catch(Exception e){Log.e(TAG,"Cannot start VAVOO resolver",e);}
                finally{if(socket!=null)try{socket.close();}catch(IOException ignored){}
                    listener=null;}
            }
        },"Vavoo-loopback-resolver");
        t.setDaemon(true);t.start();
    }

    private static String resolve(String id)throws Exception {
        Cache cached=resolved.get(id);
        long now=System.currentTimeMillis();
        if(cached!=null&&cached.until>now)return cached.url;
        java.util.List<VavooVariants.Source> options=VavooVariants.ranked(id);
        // ONYX preferred mirror comes FIRST, all others remain as fallbacks.
        String[] mirrors=VavooVariants.orderedMirrors(id);
        if(options.isEmpty()){
            String original=VavooProvider.resolveStream(id);
            resolved.put(id,new Cache(original,now+60000));
            return original;
        }
        // ONYX getServers-style ordered alternatives: preserve original
        // HD/FHD variants, try preferred mirror, then alternative mirrors,
        // then source IDs from the same canonical channel family.
        Exception last=null;
        int variantsTried=0;
        int limit=VavooVariants.getSelected(id).isEmpty()?3:4;
        for(VavooVariants.Source option:options){
            if(++variantsTried>limit)break;
            for(String mirror:mirrors){
                try{
                    String stream=VavooProvider.resolveStream(option.id,mirror);
                    if(stream==null||!stream.startsWith("http"))continue;
                    resolved.put(id,new Cache(stream,System.currentTimeMillis()+60000));
                    Log.i(TAG,"Live "+id+": "+option.name+" via "+mirror);
                    return stream;
                }catch(Exception e){
                    last=e;
                    Log.w(TAG,"Live source failed: "+option.name+" via "+mirror+": "+e.getMessage());
                }
            }
        }
        throw last!=null?last:new IOException("Aucun flux trouvé sur le miroir choisi");
    }

    private static void serve(Socket socket){
        try{
            Socket s=socket;
            BufferedReader headers=new BufferedReader(
                new InputStreamReader(s.getInputStream(),StandardCharsets.ISO_8859_1));
            String request=headers.readLine();
            if(request==null)return;
            String[] parts=request.split(" ");
            if(parts.length<2||!(parts[0].equals("GET")||parts[0].equals("HEAD"))){
                reply(s,405,"text/plain","GET/HEAD only".getBytes(StandardCharsets.UTF_8));return;
            }
            String method=parts[0],route=parts[1];
            String range=null;
            for(int i=0;i<50;i++){
                String line=headers.readLine();
                if(line==null||line.length()==0)break;
                if(line.toLowerCase(Locale.ROOT).startsWith("range:"))range=line.substring(6).trim();
            }
            if(route.equals("/health")){
                reply(s,200,"text/plain","VavooStreamServer OK".getBytes(StandardCharsets.UTF_8));return;
            }
            String media;
            if(route.startsWith("/play/")){
                String id=URLDecoder.decode(route.substring(6).split("\\?")[0],"UTF-8");
                if(!id.matches("[A-Za-z0-9_-]{4,120}")) {
                    reply(s,400,"text/plain","Invalid id".getBytes(StandardCharsets.UTF_8));return;
                }
                activeLogicalId=id;
                activeAt=System.currentTimeMillis();
                VavooUiHook.onPlaying();
                media=resolve(id);
            } else if(route.startsWith("/hls?u=")){
                media=URLDecoder.decode(route.substring(7),"UTF-8");
            } else {
                reply(s,404,"text/plain","Not found".getBytes(StandardCharsets.UTF_8));return;
            }
            URL target=new URL(media);
            if(!("http".equals(target.getProtocol())||"https".equals(target.getProtocol())))
                throw new IOException("Unsupported protocol");
            String host=target.getHost();
            if(host.equalsIgnoreCase("localhost")||host.equals("127.0.0.1")
                ||host.equals("0.0.0.0")||host.equals("169.254.169.254"))
                throw new IOException("Loopback/metadata upstream denied");
            relay(s,target,method,range);
        }catch(Exception e){
            Log.w(TAG,"Playback proxy failure: "+e.getMessage(),e);
            try{if(!socket.isClosed())reply(socket,502,"text/plain",
                ("VAVOO resolution/stream error: "+e.getMessage())
                .getBytes(StandardCharsets.UTF_8));}catch(Exception ignored){}
        }finally{try{socket.close();}catch(IOException ignored){}}
    }

    private static void relay(Socket socket,URL target,String method,String range)throws Exception {
        HttpURLConnection c=(HttpURLConnection)target.openConnection();
        try{
            c.setConnectTimeout(16000);
            c.setReadTimeout(30000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent","Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36");
            c.setRequestProperty("Accept","*/*");
            if(range!=null&&!range.isEmpty())c.setRequestProperty("Range",range);
            int status=c.getResponseCode();
            if(status<200||status>=300)throw new IOException("Upstream HTTP "+status);
            URL source=c.getURL();
            String mime=c.getContentType()==null?"application/octet-stream":c.getContentType();
            BufferedInputStream incoming=new BufferedInputStream(c.getInputStream());
            incoming.mark(32);
            byte[] head=new byte[7];
            int nr=incoming.read(head);
            incoming.reset();
            boolean hls=mime.toLowerCase(Locale.ROOT).contains("mpegurl")||
                source.getPath().toLowerCase(Locale.ROOT).endsWith(".m3u8")||
                (nr==7&&new String(head,StandardCharsets.UTF_8).equals("#EXTM3U"));
            if(hls){
                ByteArrayOutputStream buf=new ByteArrayOutputStream();
                try(InputStream in=incoming){
                    byte[] block=new byte[8192];int n;
                    while((n=in.read(block))!=-1){
                        if(buf.size()+n>4*1024*1024)throw new IOException("HLS playlist too large");
                        buf.write(block,0,n);
                    }
                }
                String playlist=new String(buf.toByteArray(),StandardCharsets.UTF_8);
                if(!playlist.trim().startsWith("#EXTM3U"))throw new IOException("Invalid HLS playlist");
                StringBuilder out=new StringBuilder();
                for(String line:playlist.split("\\r?\\n",-1)){
                    if(line.startsWith("#")){
                        Matcher m=ATTR_URI.matcher(line);
                        StringBuffer sb=new StringBuffer();
                        while(m.find()){
                            String uri=proxyUrl(source,m.group(1));
                            m.appendReplacement(sb,Matcher.quoteReplacement("URI=\""+uri+"\""));
                        }
                        m.appendTail(sb);
                        out.append(sb);
                    }else if(!line.trim().isEmpty()){
                        out.append(proxyUrl(source,line.trim()));
                    }else{out.append(line);}
                    out.append('\n');
                }
                byte[] bytes=out.toString().getBytes(StandardCharsets.UTF_8);
                reply(socket,200,"application/vnd.apple.mpegurl",bytes);
                return;
            }
            OutputStream out=socket.getOutputStream();
            StringBuilder h=new StringBuilder("HTTP/1.1 "+(status==206?"206 Partial Content":"200 OK")+"\r\n")
                .append("Content-Type: ").append(mime).append("\r\n")
                .append("Connection: close\r\n")
                .append("Access-Control-Allow-Origin: *\r\n");
            String length=c.getHeaderField("Content-Length");
            if(length!=null)h.append("Content-Length: ").append(length).append("\r\n");
            String contentRange=c.getHeaderField("Content-Range");
            if(contentRange!=null)h.append("Content-Range: ").append(contentRange).append("\r\n");
            h.append("\r\n");
            out.write(h.toString().getBytes(StandardCharsets.ISO_8859_1));
            if(method.equals("HEAD"))return;
            try(InputStream in=incoming){
                byte[] bytes=new byte[65536];int n;
                while((n=in.read(bytes))!=-1){out.write(bytes,0,n);}
            }
            out.flush();
        }finally{c.disconnect();}
    }

    private static String proxyUrl(URL base,String relative)throws Exception {
        URL absolute=new URL(base,relative);
        String p=absolute.getProtocol();
        if(!("http".equals(p)||"https".equals(p)))throw new IOException("Bad HLS URL");
        return "http://127.0.0.1:"+PORT+"/hls?u="+
            URLEncoder.encode(absolute.toString(),"UTF-8");
    }

    private static void reply(Socket s,int status,String type,byte[] body)throws IOException {
        String msg=status==200?"OK":status==400?"Bad Request":
            status==404?"Not Found":status==405?"Method Not Allowed":"Bad Gateway";
        OutputStream out=s.getOutputStream();
        String header="HTTP/1.1 "+status+" "+msg+"\r\nContent-Type: "+type+
            "\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n"+
            "Access-Control-Allow-Origin: *\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }
}
