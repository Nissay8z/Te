"use strict";

const fs = require("node:fs/promises");
const path = require("node:path");
const zlib = require("node:zlib");

// ===== CONFIGURATION =====
const CATALOG_URL = "https://vavoo.to/mediahubmx-catalog.json";
const M3U_FILE = path.join(__dirname, "..", "iptv.m3u");
const EPG_FILE = path.join(__dirname, "..", "epg.xml");
const FETCH_TIMEOUT_MS = 20000;

// Proxies (le premier qui répond sera utilisé)
const PROXY_BASES = [
  process.env.PROXY_BASE || "https://vavoo-iptv-proxy.vavoo-iptv.workers.dev",
  // Ajoute d'autres proxies si besoin
].filter(Boolean);

// EPG upstream
const EPG_UPSTREAM_URL =
  process.env.EPG_UPSTREAM_URL ||
  "https://epgshare01.online/epgshare01/epg_ripper_TR1.xml.gz";

const EPG_URL =
  process.env.EPG_URL ||
  `https://raw.githubusercontent.com/${process.env.GITHUB_REPOSITORY || "TON_USER/TON_REPO"}/main/epg.xml`;

// Filtre pays (ex: "France") – laisser vide pour tout
const COUNTRY_FILTER = process.env.COUNTRY_FILTER || "";

// ===== HEADERS =====
const HEADERS = {
  "content-type": "application/json; charset=utf-8",
  accept: "*/*",
  "accept-language": "fr-FR,fr;q=0.9,en;q=0.8",
  origin: "https://vavoo.to",
  referer: "https://vavoo.to/live",
  "user-agent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36",
};

// ===== RÉCUPÉRATION DU CATALOGUE =====
function buildBody(cursor) {
  return JSON.stringify({
    language: "fr",
    region: "FR",
    catalogId: "iptv",
    id: "",
    adult: false,
    search: "",
    sort: "name",
    filter: COUNTRY_FILTER ? { group: COUNTRY_FILTER } : {},
    cursor,
  });
}

async function fetchPage(cursor) {
  const body = buildBody(cursor);
  for (let attempt = 1; attempt <= 5; attempt++) {
    try {
      const res = await fetch(CATALOG_URL, {
        method: "POST",
        headers: HEADERS,
        body,
        signal: AbortSignal.timeout(FETCH_TIMEOUT_MS),
      });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const data = await res.json();
      if (data?.error) throw new Error(`Vavoo error: ${data.error}`);
      return data;
    } catch (err) {
      console.warn(`Tentative ${attempt} échouée (${err.message})`);
      await new Promise(r => setTimeout(r, 1000 * attempt));
    }
  }
  throw new Error("Impossible de récupérer le catalogue après 5 tentatives.");
}

async function fetchAll() {
  const items = [];
  let cursor = null;
  let page = 0;
  do {
    page++;
    const data = await fetchPage(cursor);
    if (Array.isArray(data.items)) items.push(...data.items);
    console.log(`📡 Page ${page}: ${data.items?.length ?? 0} chaînes`);
    cursor = data.nextCursor ?? null;
  } while (cursor);
  return items;
}

// ===== NETTOYAGE DES NOMS =====
function cleanName(raw) {
  let name = String(raw || "");
  // Supprime les suffixes .s .b .c .hd .sd .fhd .uhd etc.
  name = name.replace(/\s*\.(s|b|c|hd|sd|fhd|uhd|hevc|raw)\b/gi, "");
  // Supprime "4K TR:" et autres préfixes inutiles
  name = name.replace(/^\s*4K TR:\s*/i, "");
  name = name.replace(/^\s*4K\s*/i, "");
  // Supprime les mentions de qualité en fin de ligne
  name = name.replace(/\s+(UHD|FHD|HD\+|HD|SD|HEVC|RAW|H265|4K|8K|FEED|LIVE|BACKUP|PREMIERE)\b/gi, "");
  // Supprime les espaces multiples
  name = name.replace(/\s+/g, " ").trim();
  return name;
}

// ===== CATÉGORISATION EN FRANÇAIS =====
function categorize(name) {
  const s = name.toLowerCase();

  // Pays
  if (/\bfrance\b|\bfrench\b|tf1|m6|france 2|france 3|france 4|france 5|arte|canal|13 eme rue|paris|première|ciné|cinema/i.test(s))
    return "France";
  if (/\buk\b|\bgreat britain\b|\bengland\b|\bscotland\b|bbc|itv|sky news|channel 4|channel 5/i.test(s))
    return "Royaume-Uni";
  if (/\busa\b|\bamerica\b|\bus\b|cnn|fox news|abc|nbc|cbs|hbo|showtime|starz/i.test(s))
    return "USA";
  if (/\bgermany\b|\bdeutsch\b|ard|zdf|das erste|prosieben|sat\.1|rtl/i.test(s))
    return "Allemagne";
  if (/\bitaly\b|\bitalia\b|rai|canale|mediaset|la7|sky italia/i.test(s))
    return "Italie";
  if (/\bspain\b|\bespaña\b|rtve|antena 3|telecinco|la sexta|cuatro/i.test(s))
    return "Espagne";
  if (/\bportugal\b|\bportuguês\b|rtp|sic|tvi/i.test(s))
    return "Portugal";
  if (/\bnetherlands\b|\bholland\b|\bnederland\b|rtl|sbs|npo/i.test(s))
    return "Pays-Bas";
  if (/\bpoland\b|\bpolski\b|tvp|polsat|tvn/i.test(s))
    return "Pologne";
  if (/\brussia\b|\brussian\b|rtr|ntv|1tv|rossiya/i.test(s))
    return "Russie";
  if (/\bturkey\b|\bturk\b|trt|kanal d|atv|show tv|star tv|fox|now/i.test(s))
    return "Turquie";
  if (/\bromania\b|\bromân\b|digi|antena|pro tv|romania tv/i.test(s))
    return "Roumanie";
  if (/\bbulgaria\b|\bbulgar\b|bnt|nova|bTV/i.test(s))
    return "Bulgarie";
  if (/\bcroatia\b|\bcroat\b|hrt|nova|rtl/i.test(s))
    return "Croatie";
  if (/\balbania\b|\balban\b|rtsh|top channel|tv klan/i.test(s))
    return "Albanie";
  if (/\barabia\b|\bsaudi\b|\bemirats\b|\buae\b|mbc|roya|dubai|al arabiya/i.test(s))
    return "Arabie";

  // Genres (après les pays)
  if (/documentaire|docu|discovery|nat geo|history|animal planet|viasat|bbc earth|love nature|tlc|planète/i.test(s))
    return "Documentaire";
  if (/sport|foot|tennis|f1|nba|bein sport|eurosport|spor|spor/i.test(s))
    return "Sport";
  if (/film|cinema|movie|sinema|box office|horror|comedy|drama|action|fx/i.test(s))
    return "Cinéma";
  if (/series|dizi|série/i.test(s))
    return "Séries";
  if (/radio|music|muzik|power|kral|mtv|number one/i.test(s))
    return "Musique";
  if (/news|haber|bloomberg|cnn|info/i.test(s))
    return "Actualités";
  if (/religion|diyanet|akıt|kudus|semerkand|islam|christian/i.test(s))
    return "Religieux";
  if (/jeunesse|kids|children|animation|cartoon|disney|nick|baby|paw|peppa|ben 10|spider|barbie/i.test(s))
    return "Jeunesse";

  return "Général";
}

// ===== RÉSOLUTION DU PROXY =====
async function findWorkingProxy() {
  for (const proxy of PROXY_BASES) {
    try {
      const testUrl = `${proxy}/play/test`;
      const res = await fetch(testUrl, { method: "HEAD", signal: AbortSignal.timeout(5000) });
      if (res.status === 200 || res.status === 302) {
        console.log(`✅ Proxy fonctionnel : ${proxy}`);
        return proxy;
      }
    } catch (_) {}
  }
  console.warn("⚠️ Aucun proxy fonctionnel. URLs brutes utilisées.");
  return null;
}

// ===== GÉNÉRATION M3U =====
function escapeAttr(value) {
  return String(value ?? "").replace(/\r?\n/g, " ").replace(/"/g, "'");
}

function toM3U(items, vavooToEpgId, proxyBase) {
  const header = `#EXTM3U url-tvg="${escapeAttr(EPG_URL)}" x-tvg-url="${escapeAttr(EPG_URL)}"`;
  const lines = [header];
  for (const it of items) {
    if (!it || !it.url) continue;
    const vavooId = it.ids?.id ?? "";
    const rawName = it.name || "Inconnu";
    const clean = cleanName(rawName);
    const displayName = clean || rawName;
    const group = categorize(displayName);
    const tvgId = vavooToEpgId.get(vavooId) || vavooId;
    const streamUrl = proxyBase ? `${proxyBase}/play/${vavooId}` : it.url;
    lines.push(
      `#EXTINF:-1 tvg-id="${escapeAttr(tvgId)}" tvg-name="${escapeAttr(displayName)}" tvg-logo="${escapeAttr(it.logo || "")}" group-title="${escapeAttr(group)}",${displayName}`
    );
    lines.push(streamUrl);
  }
  lines.push("");
  return lines.join("\n");
}

// ===== EPG =====
function xmlEscape(v) {
  return String(v ?? "").replace(/[&<>"']/g, c =>
    c === "&" ? "&amp;" : c === "<" ? "&lt;" : c === ">" ? "&gt;" : c === '"' ? "&quot;" : "&apos;"
  );
}

function xmltvTime(sec) {
  const d = new Date(sec * 1000);
  const pad = n => String(n).padStart(2, "0");
  return `${d.getUTCFullYear()}${pad(d.getUTCMonth()+1)}${pad(d.getUTCDate())}${pad(d.getUTCHours())}${pad(d.getUTCMinutes())}${pad(d.getUTCSeconds())} +0000`;
}

async function fetchUpstreamXmltv(url) {
  const res = await fetch(url, { signal: AbortSignal.timeout(60000) });
  if (!res.ok) throw new Error(`EPG HTTP ${res.status}`);
  const buf = Buffer.from(await res.arrayBuffer());
  const isGz = url.toLowerCase().endsWith(".gz") || (buf[0] === 0x1f && buf[1] === 0x8b);
  const bytes = isGz ? zlib.gunzipSync(buf) : buf;
  return bytes.toString("utf8");
}

function parseXmltv(xml) {
  const channels = new Map();
  const programmes = [];
  const chRe = /<channel\s+id="([^"]+)"[^>]*>([\s\S]*?)<\/channel>/gi;
  for (const m of xml.matchAll(chRe)) {
    const id = m[1];
    const body = m[2];
    const names = [...body.matchAll(/<display-name[^>]*>([^<]+)<\/display-name>/gi)].map(n => n[1].trim()).filter(Boolean);
    const icon = body.match(/<icon\s+src="([^"]+)"/i)?.[1] || "";
    channels.set(id, { names, icon });
  }
  const prRe = /<programme\s+([^>]*)>([\s\S]*?)<\/programme>/gi;
  for (const m of xml.matchAll(prRe)) {
    const attrs = m[1];
    const body = m[2];
    const start = attrs.match(/start="([^"]+)"/i)?.[1];
    const stop = attrs.match(/stop="([^"]+)"/i)?.[1];
    const channel = attrs.match(/channel="([^"]+)"/i)?.[1];
    if (start && stop && channel) programmes.push({ start, stop, channel, body: body.trim() });
  }
  return { channels, programmes };
}

function normalizeForMatch(name) {
  let s = String(name || "").toUpperCase()
    .replace(/^\s*4K TR:\s*/i, "")
    .replace(/\s*\.(?:B|C|S)\b/gi, "")
    .replace(/\[[^\]]*\]/g, " ").replace(/\([^\)]*\)/g, " ")
    .replace(/\bT RK\b/g, "TURK").replace(/\bAK T\b/g, "AKIT")
    .replace(/\bS NEMA\b/g, "SINEMA").replace(/\bM N KA\b/g, "MINIKA")
    .replace(/\bOCUK\b/g, "COCUK").replace(/\bM Z K\b/g, "MUZIK")
    .replace(/\bBENG\b/g, "BENGU")
    .replace(/[İI]/g, "I").replace(/Ü/g, "U").replace(/Ö/g, "O")
    .replace(/Ç/g, "C").replace(/Ş/g, "S").replace(/Ğ/g, "G")
    .replace(/[^A-Z0-9\s]/g, " ").replace(/\s+/g, " ").trim();
  return s;
}

function normalizeStripQuality(s) {
  return s.replace(/\b(?:UHD|FHD|HD\+|HD|SD|HEVC|RAW|H265|4K|8K|FEED|LIVE|BACKUP)\b/g, "").replace(/\s+/g, " ").trim();
}

function buildMatchIndex(upstreamChannels) {
  const idx = new Map();
  for (const [id, data] of upstreamChannels) {
    for (const raw of data.names) {
      const k1 = normalizeForMatch(raw);
      const k2 = normalizeStripQuality(k1);
      if (k1 && !idx.has(k1)) idx.set(k1, id);
      if (k2 && !idx.has(k2)) idx.set(k2, id);
    }
  }
  return idx;
}

function matchUpstreamId(vavooName, idx) {
  const k1 = normalizeForMatch(vavooName);
  if (idx.has(k1)) return idx.get(k1);
  const k2 = normalizeStripQuality(k1);
  if (idx.has(k2)) return idx.get(k2);
  return null;
}

function toXMLTV(items, vavooToEpgId, upstreamChannels, upstreamProgByChannel) {
  const seen = new Set();
  const channels = [];
  const programmes = [];
  for (const it of items) {
    const vavooId = it?.ids?.id;
    if (!vavooId) continue;
    const rawName = it.name || "Inconnu";
    const displayName = cleanName(rawName);
    if (!displayName) continue;
    const routedId = vavooToEpgId.get(vavooId) || vavooId;
    if (seen.has(routedId)) continue;
    seen.add(routedId);
    const sourceCh = upstreamChannels.get(routedId) || null;
    const sourceProgs = upstreamProgByChannel.get(routedId) || [];
    const epgName = sourceCh?.names?.[0] || displayName;
    const icon = sourceCh?.icon || it.logo || "";
    const iconTag = icon ? `\n    <icon src="${xmlEscape(icon)}"/>` : "";
    channels.push(
      `  <channel id="${xmlEscape(routedId)}">\n` +
      `    <display-name>${xmlEscape(epgName)}</display-name>${iconTag}\n  </channel>`
    );
    for (const p of sourceProgs) {
      programmes.push(
        `  <programme start="${xmlEscape(p.start)}" stop="${xmlEscape(p.stop)}" channel="${xmlEscape(routedId)}">\n    ${p.body}\n  </programme>`
      );
    }
  }
  return (
    `<?xml version="1.0" encoding="UTF-8"?>\n` +
    `<tv generator-info-name="vavoo-iptv" generator-info-url="https://github.com/${process.env.GITHUB_REPOSITORY || "TON_USER/TON_REPO"}">\n` +
    `${channels.join("\n")}\n${programmes.join("\n")}\n</tv>\n`
  );
}

// ===== MAIN =====
async function main() {
  console.log("🚀 Récupération du catalogue Vavoo...");
  if (COUNTRY_FILTER) console.log(`Filtre pays : ${COUNTRY_FILTER}`);
  else console.log("🌍 Tous les pays seront inclus.");

  const items = await fetchAll();
  console.log(`📡 ${items.length} chaînes trouvées.`);

  // Tri
  items.sort((a, b) => (a.name || "").localeCompare(b.name || "fr"));

  // Proxy
  const proxyBase = await findWorkingProxy();
  if (proxyBase) console.log(`🌐 Proxy utilisé : ${proxyBase}`);
  else console.warn("⚠️ Aucun proxy – URLs brutes.");

  // EPG upstream
  let upstreamChannels = new Map();
  let upstreamProgByChannel = new Map();
  try {
    const xml = await fetchUpstreamXmltv(EPG_UPSTREAM_URL);
    const parsed = parseXmltv(xml);
    upstreamChannels = parsed.channels;
    for (const p of parsed.programmes) {
      if (!upstreamProgByChannel.has(p.channel)) upstreamProgByChannel.set(p.channel, []);
      upstreamProgByChannel.get(p.channel).push(p);
    }
    console.log(`📺 EPG upstream : ${upstreamChannels.size} chaînes, ${parsed.programmes.length} programmes.`);
  } catch (err) {
    console.warn(`⚠️ EPG non disponible (${err.message}).`);
  }

  // Association EPG
  const upstreamIdx = buildMatchIndex(upstreamChannels);
  const vavooToEpgId = new Map();
  let matched = 0;
  for (const it of items) {
    const vavooId = it?.ids?.id;
    if (!vavooId) continue;
    const rawName = it.name || "";
    const displayName = cleanName(rawName);
    if (!displayName) continue;
    const upstreamId = matchUpstreamId(displayName, upstreamIdx);
    if (upstreamId) {
      vavooToEpgId.set(vavooId, upstreamId);
      matched++;
    } else {
      vavooToEpgId.set(vavooId, vavooId);
    }
  }
  console.log(`🔗 EPG lié à ${matched}/${items.length} chaînes.`);

  // M3U
  const m3u = toM3U(items, vavooToEpgId, proxyBase);
  await fs.writeFile(M3U_FILE, m3u, "utf8");
  console.log(`✅ M3U générée : ${M3U_FILE} (${m3u.length} octets, ${items.length} chaînes)`);

  // EPG
  const epg = toXMLTV(items, vavooToEpgId, upstreamChannels, upstreamProgByChannel);
  await fs.writeFile(EPG_FILE, epg, "utf8");
  const countP = (epg.match(/<programme /g) || []).length;
  const countC = (epg.match(/<channel /g) || []).length;
  console.log(`✅ EPG généré : ${EPG_FILE} (${epg.length} octets, ${countC} chaînes, ${countP} programmes)`);

  // Stats
  const dist = new Map();
  for (const it of items) {
    const rawName = it.name || "";
    const displayName = cleanName(rawName);
    if (displayName) {
      const c = categorize(displayName);
      dist.set(c, (dist.get(c) || 0) + 1);
    }
  }
  console.log("\n📊 Répartition des catégories :");
  for (const [c, n] of [...dist.entries()].sort((a, b) => b[1] - a[1])) {
    console.log(`  ${c.padEnd(15)} : ${n}`);
  }
}

main().catch(err => {
  console.error("❌ Erreur fatale :", err);
  process.exit(1);
});
