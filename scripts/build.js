"use strict";

const fs = require("node:fs/promises");
const path = require("node:path");

// ===== CONFIGURATION =====
const CATALOG_URL = "https://vavoo.to/mediahubmx-catalog.json";
const M3U_FILE = path.join(__dirname, "..", "iptv.m3u");
const EPG_FILE = path.join(__dirname, "..", "epg.xml");
const FETCH_TIMEOUT_MS = 20000;

// Tu peux éventuellement mettre un proxy HTTP si tu veux utiliser un VPN
// mais laisse vide pour utiliser la connexion directe.
const HTTP_PROXY = process.env.HTTP_PROXY || "";

// URL publique de l'EPG (pour le lien dans la M3U)
const EPG_URL =
  process.env.EPG_URL ||
  `https://raw.githubusercontent.com/${process.env.GITHUB_REPOSITORY || "TON_USER/TON_REPO"}/main/epg.xml`;

// Filtre pays (ex: "France") – laisser vide = tous
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
  // Supprime les suffixes .s .b .c .hd .sd .fhd .uhd .hevc .raw .backup .live .feed
  name = name.replace(/\s*\.(s|b|c|hd|sd|fhd|uhd|hevc|raw|backup|live|feed)\b/gi, "");
  // Supprime "4K TR:" et autres préfixes
  name = name.replace(/^\s*4K TR:\s*/i, "");
  // Supprime les mentions de qualité
  name = name.replace(/\s+(UHD|FHD|HD\+|HD|SD|HEVC|RAW|H265|4K|8K|FEED|LIVE|BACKUP)\b/gi, "");
  // Supprime "TR:" en début
  name = name.replace(/^\s*TR:\s*/i, "");
  // Supprime les espaces multiples et les tirets inutiles
  name = name.replace(/\s+/g, " ").trim();
  return name;
}

// ===== CATÉGORISATION COMPLÈTE (avec Arabia) =====
function categorize(name) {
  const s = name.toLowerCase();

  // --- PAYS ---
  if (/\bfrance\b|\bfrench\b|tf1|m6|france 2|france 3|france 4|france 5|arte|canal|13 eme rue|paris|première|franco/i.test(s))
    return "France";
  if (/\buk\b|\bgreat britain\b|\bengland\b|\bscotland\b|bbc|itv|sky news|channel 4|channel 5|british/i.test(s))
    return "United Kingdom";
  if (/\busa\b|\bamerica\b|\bus\b|cnn|fox news|abc|nbc|cbs|hbo|showtime|starz|american/i.test(s))
    return "USA";
  if (/\bgermany\b|\bdeutsch\b|ard|zdf|das erste|prosieben|sat\.1|rtl|german/i.test(s))
    return "Germany";
  if (/\bitaly\b|\bitalia\b|rai|canale|mediaset|la7|sky italia|italian/i.test(s))
    return "Italy";
  if (/\bspain\b|\bespaña\b|rtve|antena 3|telecinco|la sexta|cuatro|spanish/i.test(s))
    return "Spain";
  if (/\bportugal\b|\bportuguês\b|rtp|sic|tvi|portuguese/i.test(s))
    return "Portugal";
  if (/\bnetherlands\b|\bholland\b|\bnederland\b|rtl|sbs|npo|dutch/i.test(s))
    return "Netherlands";
  if (/\bpoland\b|\bpolski\b|tvp|polsat|tvn|polish/i.test(s))
    return "Poland";
  if (/\brussia\b|\brussian\b|rtr|ntv|1tv|rossiya|russian/i.test(s))
    return "Russia";
  if (/\bturkey\b|\bturk\b|trt|kanal d|atv|show tv|star tv|fox|now|turkish/i.test(s))
    return "Turkey";
  if (/\bromania\b|\bromân\b|digi|antena|pro tv|romania tv|romanian/i.test(s))
    return "Romania";
  if (/\bbulgaria\b|\bbulgar\b|bnt|nova|bTV|bulgarian/i.test(s))
    return "Bulgaria";
  if (/\bcroatia\b|\bcroat\b|hrt|nova|rtl|croatian/i.test(s))
    return "Croatia";
  if (/\balbania\b|\balban\b|rtsh|top channel|tv klan|albanian/i.test(s))
    return "Albania";
  if (/\barabia\b|\bara[bm]e?\b|\bsaudi\b|\bemirats\b|\buae\b|mbc|roya|dubai|al arabiya|aljazeera|bein arab|orient|arabic|middle east|qatar|kuwait|oman|bahrain|jordan|lebanon|syria|iraq|yemen|egypt|maghreb|tunisia|algeria|morocco|libya|sudan|palestine/is)
    return "Arabia";
  if (/\bbalkans\b|balkan|ex-yu|jugoslav|serbia|bosnia|slovenia|montenegro|north macedonia/i.test(s))
    return "Balkans";

  // --- GENRES ---
  if (/documentaire|docu|discovery|nat geo|history|animal planet|viasat|bbc earth|love nature|tlc|planète/i.test(s))
    return "Belgesel";
  if (/sport|foot|tennis|f1|nba|bein sport|eurosport|spor|spor/i.test(s))
    return "Spor";
  if (/cinema|film|movie|sinema|fx|box office|horror|comedy|drama|action|film/i.test(s))
    return "Film";
  if (/series|dizi|série|serie/i.test(s))
    return "Dizi";
  if (/radio|music|muzik|power|kral|mtv|number one/i.test(s))
    return "Müzik";
  if (/news|haber|bloomberg|cnn|info|actualité/i.test(s))
    return "Haber";
  if (/religion|diyanet|akıt|kudus|semerkand|islam|christian|dini/i.test(s))
    return "Dini";
  if (/yaşam|lifestyle|life|style|fashion|wm tv|kadin|woman/i.test(s))
    return "Yaşam";
  if (/national|ulusal|devlet|milli|resmi/i.test(s))
    return "Ulusal";

  return "Diğer";
}

// ===== GÉNÉRATION M3U =====
function escapeAttr(value) {
  return String(value ?? "").replace(/\r?\n/g, " ").replace(/"/g, "'");
}

function sanitizeName(name) {
  return String(name ?? "").replace(/\r?\n/g, " ").trim();
}

function toM3U(items) {
  const header = `#EXTM3U url-tvg="${escapeAttr(EPG_URL)}" x-tvg-url="${escapeAttr(EPG_URL)}"`;
  const lines = [header];
  for (const it of items) {
    if (!it || !it.url) continue;
    const name = cleanName(it.name);
    if (!name) continue;
    const group = categorize(it.name);
    // On utilise l'URL brute (sans proxy) – si elle ne marche pas, il faudra un VPN
    const streamUrl = it.url;
    lines.push(
      `#EXTINF:-1 tvg-id="${escapeAttr(it.ids?.id || '')}" tvg-name="${escapeAttr(name)}" tvg-logo="${escapeAttr(it.logo || '')}" group-title="${escapeAttr(group)}",${name}`
    );
    lines.push(streamUrl);
  }
  lines.push("");
  return lines.join("\n");
}

// ===== GÉNÉRATION EPG (minimaliste, sans sources externes) =====
function xmlEscape(v) {
  return String(v ?? "").replace(/[&<>"']/g, c =>
    c === "&" ? "&amp;" : c === "<" ? "&lt;" : c === ">" ? "&gt;" : c === '"' ? "&quot;" : "&apos;"
  );
}

function toXMLTV(items) {
  const seen = new Set();
  const channels = [];
  for (const it of items) {
    const id = it?.ids?.id;
    if (!id) continue;
    if (seen.has(id)) continue;
    seen.add(id);
    const name = cleanName(it.name);
    if (!name) continue;
    const icon = it.logo || "";
    const iconTag = icon ? `\n    <icon src="${xmlEscape(icon)}"/>` : "";
    channels.push(
      `  <channel id="${xmlEscape(id)}">\n` +
      `    <display-name>${xmlEscape(name)}</display-name>${iconTag}\n  </channel>`
    );
  }
  return (
    `<?xml version="1.0" encoding="UTF-8"?>\n` +
    `<tv generator-info-name="vavoo-iptv" generator-info-url="https://github.com/${process.env.GITHUB_REPOSITORY || "TON_USER/TON_REPO"}">\n` +
    `${channels.join("\n")}\n` +
    `</tv>\n`
  );
}

// ===== MAIN =====
async function main() {
  console.log("🚀 Récupération du catalogue Vavoo...");
  if (COUNTRY_FILTER) console.log(`Filtre pays : ${COUNTRY_FILTER}`);
  else console.log("🌍 Tous les pays seront inclus.");

  const items = await fetchAll();
  console.log(`📡 ${items.length} chaînes trouvées.`);

  // Trier par nom
  items.sort((a, b) => (a.name || "").localeCompare(b.name || "fr"));

  // Générer la M3U
  const m3u = toM3U(items);
  await fs.writeFile(M3U_FILE, m3u, "utf8");
  console.log(`✅ M3U générée : ${M3U_FILE} (${m3u.length} octets, ${items.length} chaînes)`);

  // Générer l'EPG (sans programmes, juste les chaînes)
  const epg = toXMLTV(items);
  await fs.writeFile(EPG_FILE, epg, "utf8");
  console.log(`✅ EPG généré : ${EPG_FILE} (${epg.length} octets)`);

  // Statistiques
  const dist = new Map();
  for (const it of items) {
    const name = cleanName(it.name);
    if (name) {
      const c = categorize(it.name);
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
