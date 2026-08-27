"use strict";

const fs = require("node:fs/promises");
const path = require("node:path");
const zlib = require("node:zlib");

// ===== CONFIGURATION =====
const CATALOG_URL = "https://vavoo.to/mediahubmx-catalog.json";
const M3U_FILE = path.join(__dirname, "..", "iptv.m3u");
const EPG_FILE = path.join(__dirname, "..", "epg.xml");
const FETCH_TIMEOUT_MS = 20000;

// Liste des proxies (le premier qui répond sera utilisé)
const PROXY_BASES = [
  process.env.PROXY_BASE || "https://vavoo-iptv-proxy.vavoo-iptv.workers.dev",
  "https://vavoo-proxy.herokuapp.com", // autre proxy (à vérifier)
].filter(Boolean);

// EPG upstream
const EPG_UPSTREAM_URL =
  process.env.EPG_UPSTREAM_URL ||
  "https://epgshare01.online/epgshare01/epg_ripper_TR1.xml.gz";

const EPG_URL =
  process.env.EPG_URL ||
  `https://raw.githubusercontent.com/${process.env.GITHUB_REPOSITORY || "TON_USER/TON_REPO"}/main/epg.xml`;

const COUNTRY_FILTER = process.env.COUNTRY_FILTER || "";

// ===== HEADERS =====
const HEADERS = {
  "content-type": "application/json; charset=utf-8",
  accept: "*/*",
  "accept-language": "fr-FR,fr;q=0.9,en;q=0.8",
  "cache-control": "no-cache",
  pragma: "no-cache",
  origin: "https://vavoo.to",
  referer: "https://vavoo.to/live",
  dnt: "1",
  "sec-ch-ua":
    '"Not=A?Brand";v="99", "Google Chrome";v="151", "Chromium";v="151"',
  "sec-ch-ua-mobile": "?0",
  "sec-ch-ua-platform": '"macOS"',
  "sec-fetch-dest": "empty",
  "sec-fetch-mode": "cors",
  "sec-fetch-site": "same-origin",
  "user-agent":
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36",
};

// ===== FETCH CATALOG =====
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
  let lastErr;
  for (let attempt = 1; attempt <= 5; attempt++) {
    try {
      const res = await fetch(CATALOG_URL, {
        method: "POST",
        headers: HEADERS,
        body,
        signal: AbortSignal.timeout(FETCH_TIMEOUT_MS),
      });
      if (!res.ok) throw new Error(`HTTP ${res.status} ${res.statusText}`);
      const data = await res.json();
      if (data && data.error) throw new Error(`Vavoo error: ${data.error}`);
      return data;
    } catch (err) {
      lastErr = err;
      console.warn(`Tentative ${attempt} échouée (${err.message}). Nouvel essai...`);
      await new Promise((r) => setTimeout(r, 1000 * attempt));
    }
  }
  throw lastErr;
}

async function fetchAll() {
  const items = [];
  let cursor = null;
  let page = 0;
  do {
    page++;
    const data = await fetchPage(cursor);
    if (Array.isArray(data.items)) items.push(...data.items);
    console.log(`Page ${page}: ${data.items?.length ?? 0} chaînes`);
    cursor = data.nextCursor ?? null;
  } while (cursor);
  return items;
}

// ===== CATÉGORISATION =====
function categorize(name) {
  const s = String(name || "").toLowerCase();
  if (/discovery|nat geo|history|animal planet|viasat|bbc earth|love nature|tlc|docu/i.test(s))
    return "Documentaire";
  if (/cartoon|disney|nick|baby|kids|children|animation|dessin|paw|peppa|ben 10|spider|barbie/i.test(s))
    return "Jeunesse";
  if (/sport|foot|tennis|f1|nba|bein sport|eurosport|spor/i.test(s))
    return "Sport";
  if (/cinema|film|movie|sinema|fx|box office|horror|comedy|drama|action/i.test(s))
    return "Cinéma";
  if (/series|dizi|série/i.test(s))
    return "Séries";
  if (/radio|music|muzik|power|kral|mtv|number one/i.test(s))
    return "Musique";
  if (/news|haber|bloomberg|cnn|info/i.test(s))
    return "Actualités";
  if (/religion|diyanet|akıt|kudus|semerkand|islam|christian/i.test(s))
    return "Religieux";
  if (/france|french/i.test(s))
    return "France";
  if (/uk|gb|british|england/i.test(s))
    return "UK";
  if (/usa|america/i.test(s))
    return "USA";
  if (/germany|deutsch|allemagne/i.test(s))
    return "Allemagne";
  if (/italy|italia/i.test(s))
    return "Italie";
  if (/spain|españa/i.test(s))
    return "Espagne";
  if (/portugal|portuguese/i.test(s))
    return "Portugal";
  if (/netherlands|holland|nederland/i.test(s))
    return "Pays-Bas";
  if (/poland|polski/i.test(s))
    return "Pologne";
  if (/russia|russian/i.test(s))
    return "Russie";
  if (/turkey|turc|turk/i.test(s))
    return "Turquie";
  return "Général";
}

// ===== RÉSOLUTION DU PROXY FONCTIONNEL =====
async function findWorkingProxy() {
  for (const proxy of PROXY_BASES) {
    try {
      const testUrl = `${proxy}/play/test`; // on teste avec un ID bidon
      const res = await fetch(testUrl, { method: "HEAD", signal: AbortSignal.timeout(5000) });
      if (res.status === 200 || res.status === 302) {
        console.log(`✅ Proxy fonctionnel : ${proxy}`);
        return proxy;
      }
    } catch (_) {}
  }
  console.warn("⚠️ Aucun proxy fonctionnel. Les URLs brutes seront utilisées.");
  return null;
}

// ===== GÉNÉRATION M3U =====
function escapeAttr(value) {
  return String(value ?? "").replace(/\r?\n/g, " ").replace(/"/g, "'");
}

function sanitizeName(name) {
  return String(name ?? "").replace(/\r?\n/g, " ").trim();
}

function toM3U(items, vavooToEpgId, proxyBase) {
  const header = `#EXTM3U url-tvg="${escapeAttr(EPG_URL)}" x-tvg-url="${escapeAttr(EPG_URL)}"`;
  const lines = [header];
  for (const it of items) {
    if (!it || !it.url) continue;
    const vavooId = it.ids?.id ?? "";
    const name = sanitizeName(it.name);
    if (!name) continue;
    const group = categorize(name);
    const tvgId = vavooToEpgId.get(vavooId) || vavooId;
    const streamUrl = proxyBase ? `${proxyBase}/play/${vavooId}` : it.url;
    lines.push(
      `#EXTINF:-1 tvg-id="${escapeAttr(tvgId)}" tvg-name="${escapeAttr(name)}" tvg-logo="${escapeAttr(it.logo || "")}" group-title="${escapeAttr(group)}",${name}`
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
    const name = sanitizeName(it.name);
    if (!name) continue;
    const routedId = vavooToEpgId.get(vavooId) || vavooId;
    if (seen.has(routedId)) continue;
    seen.add(routedId);
    const sourceCh = upstreamChannels.get(routedId) || null;
    const sourceProgs = upstreamProgByChannel.get(routedId) || [];
    const displayName = sourceCh?.names?.[0] || name;
    const icon = sourceCh?.icon || it.logo || "";
    const iconTag = icon ? `\n    <icon src="${xmlEscape(icon)}"/>` : "";
    channels.push(
      `  <channel id="${xmlEscape(routedId)}">\n` +
      `    <display-name>${xmlEscape(displayName)}</display-name>${iconTag}\n  </channel>`
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

  // 1. Récupérer toutes les chaînes
  const items = await fetchAll();
  console.log(`📡 ${items.length} chaînes trouvées.`);

  // 2. Trier pour stabilité
  items.sort((a, b) => (a.name || "").localeCompare(b.name || "fr"));

  // 3. Trouver un proxy fonctionnel
  const proxyBase = await findWorkingProxy();
  if (proxyBase) console.log(`🌐 Proxy utilisé : ${proxyBase}`);
  else console.warn("⚠️ Aucun proxy – les URLs brutes seront utilisées (peuvent être bloquées).");

  // 4. Récupérer l'EPG upstream
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

  // 5. Associer les IDs EPG
  const upstreamIdx = buildMatchIndex(upstreamChannels);
  const vavooToEpgId = new Map();
  let matched = 0;
  for (const it of items) {
    const vavooId = it?.ids?.id;
    if (!vavooId) continue;
    const name = sanitizeName(it.name);
    if (!name) continue;
    const upstreamId = matchUpstreamId(name, upstreamIdx);
    if (upstreamId) {
      vavooToEpgId.set(vavooId, upstreamId);
      matched++;
    } else {
      vavooToEpgId.set(vavooId, vavooId);
    }
  }
  console.log(`🔗 EPG lié à ${matched}/${items.length} chaînes.`);

  // 6. Générer la M3U
  const m3u = toM3U(items, vavooToEpgId, proxyBase);
  await fs.writeFile(M3U_FILE, m3u, "utf8");
  console.log(`✅ M3U générée : ${M3U_FILE} (${m3u.length} octets, ${items.length} chaînes)`);

  // 7. Générer l'EPG
  const epg = toXMLTV(items, vavooToEpgId, upstreamChannels, upstreamProgByChannel);
  await fs.writeFile(EPG_FILE, epg, "utf8");
  const countP = (epg.match(/<programme /g) || []).length;
  const countC = (epg.match(/<channel /g) || []).length;
  console.log(`✅ EPG généré : ${EPG_FILE} (${epg.length} octets, ${countC} chaînes, ${countP} programmes)`);

  // 8. Statistiques des catégories
  const dist = new Map();
  for (const it of items) {
    const name = sanitizeName(it.name);
    if (name) {
      const c = categorize(name);
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
