#!/usr/bin/env python3
"""Download free public IPTV sources as separate, offline Android IPTV playlists.

Not a DRM circumvention tool. Unavailable sources are skipped; no paywalled
credentials or private stream URLs. The app can refresh each public source later.
"""
from __future__ import annotations
import os,re,time,json,urllib.request
from pathlib import Path
from collections import Counter
from urllib.parse import urlparse

ROOT=Path("build/free-tv")
ROOT.mkdir(parents=True,exist_ok=True)
SRC=[
 ("free_tv","Free-TV • Chaînes gratuites","https://raw.githubusercontent.com/Free-TV/IPTV/master/playlist.m3u8",100),
 ("iptv_org","IPTV-org • Pays du monde","https://iptv-org.github.io/iptv/index.country.m3u",1000),
 ("pluto_fr","Pluto TV • France","https://raw.githubusercontent.com/BuddyChewChew/app-m3u-generator/main/playlists/plutotv_fr.m3u",80),
 ("plex_fr","Plex TV • France","https://raw.githubusercontent.com/BuddyChewChew/app-m3u-generator/main/playlists/plex_fr.m3u",80),
 ("samsung_fr","Samsung TV Plus • France","https://raw.githubusercontent.com/BuddyChewChew/app-m3u-generator/main/playlists/samsungtvplus_fr.m3u",80),
]
FALLBACK_IPTV="https://raw.githubusercontent.com/Nissay8z/NISSKENZI/main/morocco-tv/app/src/main/assets/iptv_org_catalog.m3u"
ATTR=re.compile(r'(?<![\w-])([A-Za-z][A-Za-z0-9-]*)="([^"]*)"')
EXCLUDE=re.compile(r'(?i)(?:^|\W)(?:adult|porn|xxx|18\+|erotic|nudity)(?:\W|$)')
def fetch(url):
    req=urllib.request.Request(url,headers={"User-Agent":"Mozilla/5.0 (compatible; Nisskenzi-TV-Free-Build/1.0)","Accept":"application/x-mpegurl,text/plain,*/*"})
    with urllib.request.urlopen(req,timeout=85) as r:
        raw=r.read(30_000_000)
        if len(raw)>26_000_000:raise ValueError("Playlist too big")
        text=raw.decode("utf-8-sig","replace")
        if not text.lstrip().startswith("#EXTM3U"):raise ValueError("Missing #EXTM3U header")
        return text

def clean(x):return x.replace('"',"'").replace('\r',' ').replace('\n',' ').strip()
def normalize(title,text):
    header=text.splitlines()[0] if text.splitlines() else "#EXTM3U"
    urls=re.findall(r'(?:url-tvg|x-tvg-url)="([^"]+)"',header)
    url_epg=urls[0].split(",")[0].strip() if urls else ""
    out=["#EXTM3U"+(f' url-tvg="{clean(url_epg)}"' if url_epg.startswith("https://") else "")]
    meta=None
    seen=set();groups=Counter();rejected=0
    for line in text.splitlines():
        line=line.strip()
        if line.startswith("#EXTINF:"):
            meta=line
            continue
        if line.startswith("#") or not line or meta is None:continue
        parsed=urlparse(line)
        if parsed.scheme not in ("http","https") or not parsed.hostname:
            meta=None;rejected+=1;continue
        attrs=dict(ATTR.findall(meta))
        label=meta.split(",",1)[-1].strip()
        if not label or EXCLUDE.search(label+" "+attrs.get("group-title","")):
            meta=None;rejected+=1;continue
        # Exact repeated link is skipped inside one provider, but the same
        # channel across providers remains independently accessible.
        if line in seen:meta=None;continue
        seen.add(line)
        group=attrs.get("group-title","Général") or "Général"
        group=f"{title} · {group}"
        epg=attrs.get("tvg-id","")
        logo=attrs.get("tvg-logo","")
        out.extend([f'#EXTINF:-1 tvg-id="{clean(epg)}" tvg-name="{clean(label)}" tvg-logo="{clean(logo)}" group-title="{clean(group)}",{clean(label)}',line])
        groups[group]+=1
        meta=None
    return "\n".join(out)+"\n",len(seen),groups,url_epg,rejected

def run():
    report={}
    manifest=[]
    for slug,title,url,minimum in SRC:
        err=None;content=None
        for candidate in ([url,FALLBACK_IPTV] if slug=="iptv_org" else [url]):
            for attempt in range(2):
                try:
                    original=fetch(candidate)
                    payload,count,groups,epg,rejected=normalize(title,original)
                    if count<minimum:raise ValueError(f"{count} channels, minimum {minimum}")
                    content=(payload,count,groups,epg,rejected)
                    break
                except Exception as e:
                    err=str(e);time.sleep(2)
            if content:break
        if content is None:
            print(f"WARNING source {slug}: {err}",flush=True)
            if slug in ("iptv_org","free_tv"):raise RuntimeError("Required public source unavailable: "+slug)
            continue
        payload,count,groups,epg,rejected=content
        (ROOT/f"{slug}.m3u").write_text(payload,encoding="utf8")
        manifest.append((slug,title,url,epg))
        report[slug]={"channels":count,"categories":len(groups),"rejected":rejected,"guide":epg}
        print(f"OK {slug}: {count} channels, {len(groups)} groups; epg={bool(epg)}",flush=True)
    (ROOT/"manifest.tsv").write_text("".join("\t".join(fields)+"\n" for fields in manifest),encoding="utf8")
    (ROOT/"report.json").write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding="utf8")
    assert len(manifest)>=2
    print("Source summary:",json.dumps(report,ensure_ascii=False),flush=True)
if __name__=="__main__":run()
