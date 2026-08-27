"use strict";

const fs = require("node:fs/promises");
const path = require("node:path");
const zlib = require("node:zlib");

// ===== CONFIGURATION =====
const CATALOG_URL = "https://vavoo.to/mediahubmx-catalog.json";
const M3U_FILE = path.join(__dirname, "..", "iptv.m3u");
const EPG_FILE = path.join(__dirname, "..", "epg.xml");
const FETCH_TIMEOUT_MS = 20000;

// Liste des proxies à essayer (le premier qui fonctionne sera utilisé)
const PROXY_BASES = [
  process.env.PROXY_BASE || "https://vavoo-iptv-proxy.vavoo-iptv.workers.dev",
  "https://vavoo-proxy.herokuapp.com", // autre proxy public (à vérifier)
  // Ajoute ici ton propre proxy si tu en déploies un
].filter(Boolean);

// EPG upstream (source EPGshare01 pour la Turquie, mais tu peux changer)
const EPG_UPSTREAM_URL =
  process.env.EPG_UPSTREAM_URL ||
  "https://epgshare01.online/epgshare01/epg_ripper_TR1.xml.gz";

// URL publique de l'EPG (déduite automatiquement du dépôt)
const EPG_URL =
  process.env.EPG_URL ||
  `https://raw.githubusercontent.com/${process.env.GITHUB_REPOSITORY || "TON_USER/TON_REPO"}/main/epg.xml`;

// Filtre éventuel par pays (ex: "France") – laisser vide pour tout
const COUNTRY_FILTER = process.env.COUNTRY_FILTER || "";

// ===== HEADERS POUR L'API =====
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

// ===== FONCTIONS DE FETCH =====
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
      if (!res.ok) {
        throw new Error(`HTTP ${res.status} ${res.statusText}`);
      }
      const data = await res.json();
      if (data && data.error) {
        throw new Error(`Vavoo error: ${data.error}`);
      }
      return data;
    } catch (err) {
      lastErr = err;
      const wait = 1000 * attempt;
      console.warn(
        `Tentative ${attempt} échouée (${err.message}). Nouvel essai dans ${wait}ms...`
      );
      await new Promise((r) => setTimeout(r, wait));
    }
  }
  throw lastErr;
}

async function fetchAll() {
  const items = [];
  let cursor = null;
  let page = 0;
  const MAX_PAGES = 200;
  do {
    page++;
    const data = await fetchPage(cursor);
    if (Array.isArray(data.items)) items.push(...data.items);
    console.log(
      `Page ${page}: ${data.items?.length ?? 0} chaînes, nextCursor=${data.nextCursor ?? "null"}`
    );
    cursor = data.nextCursor ?? null;
    if (page >= MAX_PAGES) {
      console.warn(`Arrêt après ${MAX_PAGES} pages.`);
      break;
    }
  } while (cursor !== null && cursor !== undefined);
  return items;
}

// ===== CATÉGORISATION EN FRANÇAIS =====
// Règles simplifiées mais efficaces pour tous pays
function categorize(name) {
  const s = String(name || "").toLowerCase();
  if (/discovery|nat geo|history|animal planet|viasat|bbc earth|love nature|tlc|docu/i.test(s))
    return "Documentaire";
  if (/cartoon|disney|nick|baby|kids|children|animation|dessin|paw|peppa|ben 10|spider|barbie/i.test(s))
    return "Jeunesse";
  if (/sport|foot|tennis|f1|nba|bein sport|eurosport|spor|spor/i.test(s))
    return "Sport";
  if (/cinema|film|movie|sinema|fx|box office|horror|comedy|drama|action/i.test(s))
    return "Cinéma";
  if (/series|dizi|drama|série/i.test(s))
    return "Séries";
  if (/radio|music|muzik|power|kral|mtv|number one/i.test(s))
    return "Musique";
  if (/news|haber|bloomberg|cnn|paris|info/i.test(s))
    return "Actualités";
  if (/religion|diyanet|akıt|kudus|semerkand|islam|christian/i.test(s))
    return "Religieux";
  if (/france|french|francophone/i.test(s) && !s.includes("international"))
    return "France";
  if (/uk|gb|british|england/i.test(s))
    return "Royaume-Uni";
  if (/usa|america|us/i.test(s))
    return "USA";
  if (/germany|deutsch|allemagne/i.test(s))
    return "Allemagne";
  if (/italy|italia/i.test(s))
    return "Italie";
  if (/spain|españa|espagne/i.test(s))
    return "Espagne";
  if (/portugal|portuguese/i.test(s))
    return "Portugal";
  if (/netherlands|holland|nederland/i.test(s))
    return "Pays-Bas";
  if (/poland|polski|polonaise/i.test(s))
    return "Pologne";
  if (/russia|russian|русский/i.test(s))
    return "Russie";
  if (/turkey|turc|turk/i.test(s))
    return "Turquie";
  if (/arabia|arab|saudi|emirats/i.test(s))
    return "Arabie";
  if (/balkans|bulgaria|croatia|romania/i.test(s))
    return "Balkans";
  return "Général";
}

// ===== GÉNÉRATION M3U =====
function escapeAttr(value) {
  return String(value ?? "")
    .replace(/\r?\n/g, " ")
    .replace(/"/g, "'");
}

function sanitizeName(name) {
  return String(name ?? "")
    .replace(/\r?\n/g, " ")
    .trim();
}

// Essaie chaque proxy jusqu'à ce qu'un fonctionne
async function resolveStreamUrl(item) {
  const id = item?.ids?.id;
  if (!id) return null;

  for (const proxy of PROXY_BASES) {
    try {
      const testUrl = `${proxy}/play/${id}`;
      // Test rapide : on envoie une requête HEAD pour vérifier que le proxy répond
      const res = await fetch(testUrl, { method: "HEAD", signal: AbortSignal.timeout(5000) });
      if (res.status === 200 || res.status === 302) {
        console.log(`  Proxy OK : ${proxy}`);
        return testUrl;
      }
    } catch (_) {
      // continue
    }
  }
  // Si aucun proxy ne fonctionne, on renvoie l'URL brute (au cas où)
  return item.url;
}

function toM3U(items, vavooToEpgId) {
  const header = `#EXTM3U url-tvg="${escapeAttr(EPG_URL)}" x-tvg-url="${escapeAttr(EPG_URL)}"`;
  const lines = [header];
  for (const it of items) {
    if (!it || !it.url) continue;
    const vavooId = it.ids?.id ?? "";
    const name = sanitizeName(it.name);
    if (!name) continue;

    // On essaie de résoudre l'URL via un proxy fonctionnel
    const streamUrl = resolveStreamUrl(it); // attention : async dans une boucle synchrone ! On va le faire différemment.
    // On va plutôt faire une résolution asynchrone avant la génération.
    // Je modifie la fonction pour qu'elle soit appelée en amont.
    // Je vais réécrire la partie principale.
  }
  // Je vais refaire cette fonction pour qu'elle soit appelée avec des items déjà enrichis.
}

// En fait, je vais réécrire la fonction principale pour résoudre les URLs avant de générer la M3U.
// Voir plus bas.
