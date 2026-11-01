#!/usr/bin/env python3
"""Global TV-only VAVOO: public NISSKENZI snapshot + real iptv-org logos."""
import base64,re,unicodedata,urllib.request
from pathlib import Path
from collections import defaultdict,Counter

BASE="https://raw.githubusercontent.com/Nissay8z/NISSKENZI/main/morocco-tv/app/src/main/assets/"
ROOT=Path(__file__).resolve().parent.parent
EPG="https://raw.githubusercontent.com/Nissay8z/Te/main/epg.xml"
OUT=ROOT/"iptv-global.m3u"

def fetch(url):
  req=urllib.request.Request(url,headers={"User-Agent":"Nisskenzi-TV-global/1.0"})
  with urllib.request.urlopen(req,timeout=95) as r: return r.read().decode("utf-8-sig")

def att(s,k):
  m=re.search(r'(?<![\w-])'+re.escape(k)+r'="([^"]*)"',s)
  return m.group(1) if m else ""

def entries(s):
  pending=None
  for x in s.splitlines():
    x=x.strip()
    if x.startswith("#EXTINF:"):pending=x
    elif pending and x and not x.startswith("#"):
      yield pending,x
      pending=None

def norm(s):
  s=unicodedata.normalize("NFKD",s).upper()
  s="".join(c for c in s if not unicodedata.combining(c))
  s=re.sub(r'\([0-9]{3,4}[pi]\)'," ",s,flags=re.I)
  s=re.sub(r'\s*\.(?:B|C|S)\s*$'," ",s)
  s=re.sub(r'\b(?:HEVC|FULL HD|FHD|UHD|HD|SD|4K|1080P|720P|480P)\b'," ",s)
  return re.sub(r'[^A-Z0-9]+'," ",s).strip()

def clean(s):return (s or "").replace('"',"'").replace("\r"," ").replace("\n"," ").strip()

def main():
  global_m3u=fetch(BASE+"vavoo_catalog.m3u")
  iptv_org=fetch(BASE+"iptv_org_catalog.m3u")
  logo_idx=defaultdict(set)
  for meta,url in entries(iptv_org):
    key=(att(meta,"group-title"),norm(meta.split(",",1)[-1]))
    logo=att(meta,"tvg-logo")
    if logo.startswith("https://"):logo_idx[key].add(logo)
  own_logo=defaultdict(set)
  world=[]
  for meta,url in entries(global_m3u):
    name=meta.split(",",1)[-1].strip()
    country=att(meta,"group-title")
    if url.startswith("vavoo-catalog://"):
      raw=url.split("://",1)[1]
      try:url=base64.urlsafe_b64decode(raw+"="*((-len(raw))%4)).decode("utf-8")
      except (UnicodeError,ValueError):continue
    if not url.startswith("https://") or re.search(r'\b(?:XXX|ADULT|PORN)\b',name,re.I):continue
    logo=att(meta,"tvg-logo")
    if logo.startswith("https://"):own_logo[(country,norm(name))].add(logo)
    world.append((name,country,url,logo,att(meta,"tvg-id")))
  if len(world)<2000 or len(set(x[1] for x in world))<8:raise RuntimeError("Incomplete global VAVOO data")
  epg_map={url:att(meta,"tvg-id") for meta,url in entries((ROOT/"iptv.m3u").read_text("utf-8"))}
  prefer=["France","France Sport","Arabia","Germany","United Kingdom","Spain","Italy","Portugal","Netherlands","Turkey","Albania","Balkans","Poland","Romania","Russia","Bulgaria","Croatia"]
  world.sort(key=lambda x:(prefer.index(x[1]) if x[1] in prefer else 999,norm(x[0]),x[2]))
  result=[f'#EXTM3U url-tvg="{EPG}" x-tvg-url="{EPG}"']
  seen=set();countries=Counter();logos=0
  for name,country,url,logo,tvgid in world:
    if url in seen:continue
    seen.add(url)
    if not logo:
      candidates=logo_idx[(country,norm(name))]
      if len(candidates)==1:logo=next(iter(candidates))
      elif len(own_logo[(country,norm(name))])==1:logo=next(iter(own_logo[(country,norm(name))]))
    logos+=bool(logo)
    tvgid=epg_map.get(url,tvgid)
    result.append(f'#EXTINF:-1 tvg-id="{clean(tvgid)}" tvg-name="{clean(name)}" tvg-logo="{clean(logo)}" group-title="{clean(country)}",{clean(name)}')
    result.append(url)
    countries[country]+=1
  if len(seen)<2000:raise RuntimeError("Too few channels")
  OUT.write_text("\n".join(result)+"\n",encoding="utf-8")
  print("Worldwide VAVOO:",len(seen),"channels,",len(countries),"groups,",logos,"logos")
  print(dict(countries))
if __name__=="__main__":main()
