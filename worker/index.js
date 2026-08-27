// ============================================================
// VAVOO IPTV PROXY — /play/<id> resolver + HLS rewriter
// ============================================================

const CACHE_TTL = 300;
const CHANNELS_CACHE_KEY = 'vavoo_channels';
const LANGUAGE = 'fr';
const REGION = 'FR';

const BASE_SITES = ['https://vavoo.to', 'https://kool.to'];
const PING_URL = 'https://www.vavoo.tv/api/app/ping';
const RESOLVE_PATH = '/mediahubmx-resolve.json';
const CATALOG_PATH = '/mediahubmx-catalog.json';

const ALLOWED_EXTENSIONS = new Set([
  '.m3u8', '.ts', '.aac', '.mp3', '.m4s', '.mp4', '.m4a', '.key', '.vtt', '.webvtt'
]);

function pathExtension(urlString) {
  try {
    const p = new URL(urlString).pathname.toLowerCase();
    const dot = p.lastIndexOf('.');
    return dot === -1 ? '' : p.slice(dot);
  } catch {
    return '';
  }
}

function getCatalogHeaders(signature) {
  return {
    'Content-Type': 'application/json; charset=utf-8',
    'mediahubmx-signature': signature,
    'User-Agent': 'MediaHubMX/2',
    'Accept': '*/*',
    'Accept-Language': LANGUAGE,
    'Accept-Encoding': 'gzip, deflate',
    'Connection': 'close',
  };
}

function getStreamHeaders() {
  return {
    'User-Agent': 'VAVOO/2.6',
    'Accept': '*/*',
    'Accept-Language': LANGUAGE,
    'Origin': 'https://vavoo.to',
    'Referer': 'https://vavoo.to/',
    'Connection': 'close'
  };
}

function getPlaylistHeaders() {
  return {
    'User-Agent': 'libmpv',
    'Accept': 'application/vnd.apple.mpegurl, application/x-mpegURL, */*',
    'Accept-Language': LANGUAGE,
    'Origin': 'https://vavoo.to',
    'Referer': 'https://vavoo.to/',
    'Connection': 'close'
  };
}

function isM3u8Url(url) {
  try {
    return new URL(url).pathname.toLowerCase().endsWith('.m3u8');
  } catch {
    return false;
  }
}

function isM3u8Response(url, contentType) {
  const ct = String(contentType || '').toLowerCase();
  return ct.includes('mpegurl') ||
    ct.includes('mpegURL') ||
    ct.includes('application/vnd.apple') ||
    isM3u8Url(url);
}

function describeUrl(url) {
  try {
    const u = new URL(url);
    return `${u.hostname}${u.pathname}`;
  } catch {
    return url;
  }
}

function getProxiedUrl(baseUrl, upstreamUrl) {
  return `${baseUrl}/hls-proxy?url=${encodeURIComponent(upstreamUrl)}`;
}

function shouldRewriteUri(uri) {
  const trimmed = String(uri || '').trim();
  if (!trimmed) return false;
  return !/^(data|urn|skd):/i.test(trimmed);
}

function rewritePlaylistUri(baseUrl, playlistBase, uri) {
  if (!shouldRewriteUri(uri)) return uri;
  try {
    const absolute = new URL(uri, playlistBase).toString();
    return getProxiedUrl(baseUrl, absolute);
  } catch {
    return uri;
  }
}

function rewritePlaylist(baseUrl, upstreamUrl, playlist) {
  return String(playlist)
    .split(/\r?\n/)
    .map(line => {
      const trimmed = line.trim();
      if (!trimmed) return line;

      if (trimmed.startsWith('#')) {
        return line.replace(/URI="([^"]+)"/g, (match, uri) => {
          return `URI="${rewritePlaylistUri(baseUrl, upstreamUrl, uri)}"`;
        });
      }

      return rewritePlaylistUri(baseUrl, upstreamUrl, trimmed);
    })
    .join('\n');
}

// ===== API REQUESTS =====

async function fetchJson(url, options = {}) {
  const response = await fetch(url, {
    method: options.method || 'GET',
    headers: options.headers || {},
    body: options.body ? JSON.stringify(options.body) : undefined,
    signal: AbortSignal.timeout(options.timeout || 30000),
  });

  if (!response.ok) {
    const error = new Error(`HTTP ${response.status} for ${url}`);
    error.status = response.status;
    throw error;
  }

  return response.json();
}

async function getAddonSignature() {
  const cached = await VAVOO_KV?.get('signature');
  if (cached) return cached;

  const payload = {
    reason: 'app-focus',
    locale: LANGUAGE,
    theme: 'dark',
    metadata: {
      device: { type: 'desktop', uniqueId: `cf-${Date.now()}` },
      os: { name: 'linux', version: 'Linux', abis: ['x64'], host: 'cloudflare' },
      app: { platform: 'electron' }
    },
    appFocusTime: 0,
    playerActive: false,
    playDuration: 0,
    devMode: false,
    hasAddon: true,
    castConnected: false,
    package: 'tv.vavoo.app',
    version: '3.1.8',
    process: 'app',
    firstAppStart: Date.now(),
    lastAppStart: Date.now(),
    ipLocation: null,
    adblockEnabled: true,
    proxy: { supported: ['ss'], engine: 'Mu', enabled: false, autoServer: true },
    iap: { supported: false }
  };

  try {
    const body = await fetchJson(PING_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: payload
    });

    const signature = body?.addonSig;
    if (signature) {
      await VAVOO_KV?.put('signature', signature, { expirationTtl: 300 });
      return signature;
    }
  } catch (error) {
    console.log(`[vavoo] addonSig failed: ${error.message}`);
  }

  throw new Error('Addon signature could not be obtained');
}

async function loadCatalog(baseUrl, signature) {
  const catalogUrl = `${baseUrl.replace(/\/$/, '')}${CATALOG_PATH}`;
  const headers = getCatalogHeaders(signature);
  const channels = [];
  let cursor = null;

  while (true) {
    try {
      const body = await fetchJson(catalogUrl, {
        method: 'POST',
        headers,
        body: {
          language: LANGUAGE,
          region: REGION,
          catalogId: 'iptv',
          id: 'iptv',
          adult: false,
          search: '',
          sort: '',
          filter: {},  // pas de filtre de pays
          cursor,
          clientVersion: '3.0.2'
        }
      });

      const items = Array.isArray(body?.items) ? body.items : [];
      for (const item of items) {
        const vavooId = item?.ids?.id || item?.id;
        if (item?.type === 'iptv' && item?.url && vavooId) {
          channels.push({
            url: item.url,
            name: item.name || 'Inconnu',
            logo: item.logo || '',
            vavooId
          });
        }
      }

      if (!body?.nextCursor) break;
      cursor = body.nextCursor;
    } catch (error) {
      console.log(`[vavoo] Catalog loading failed: ${error.message}`);
      break;
    }
  }

  return channels;
}

async function getChannels() {
  const cached = await VAVOO_KV?.get(CHANNELS_CACHE_KEY, 'json');
  if (cached && Array.isArray(cached)) return cached;

  const signature = await getAddonSignature();

  for (const baseUrl of BASE_SITES) {
    try {
      const channels = await loadCatalog(baseUrl, signature);
      if (channels.length > 0) {
        await VAVOO_KV?.put(CHANNELS_CACHE_KEY, JSON.stringify(channels), { expirationTtl: CACHE_TTL });
        return channels;
      }
    } catch (error) {
      console.log(`[vavoo] Catalog load failed (${baseUrl}): ${error.message}`);
    }
  }

  throw new Error('Impossible de charger le catalogue');
}

async function findChannel(id) {
  const channels = await getChannels();
  return channels.find(c => String(c.vavooId) === String(id));
}

async function resolveStream(channel) {
  const signature = await getAddonSignature();

  for (const baseUrl of BASE_SITES) {
    const resolveUrl = `${baseUrl.replace(/\/$/, '')}${RESOLVE_PATH}`;

    try {
      const body = await fetchJson(resolveUrl, {
        method: 'POST',
        headers: getCatalogHeaders(signature),
        body: {
          language: LANGUAGE,
          region: REGION,
          url: channel.url,
          clientVersion: '3.0.2'
        }
      });

      if (Array.isArray(body) && body[0]?.url) return body[0].url;
      if (body?.url) return body.url;
      if (body?.streamUrl) return body.streamUrl;
    } catch (error) {
      console.log(`[vavoo] Resolve failed (${baseUrl}): ${error.message}`);
    }
  }

  throw new Error(`Impossible de résoudre le flux pour: ${channel.name}`);
}

async function resolveDirect(id) {
  const signature = await getAddonSignature();
  const directUrl = `https://vavoo.to/watch?live=${id}`;

  for (const baseUrl of BASE_SITES) {
    const resolveUrl = `${baseUrl.replace(/\/$/, '')}${RESOLVE_PATH}`;
    try {
      const body = await fetchJson(resolveUrl, {
        method: 'POST',
        headers: getCatalogHeaders(signature),
        body: {
          language: LANGUAGE,
          region: REGION,
          url: directUrl,
          clientVersion: '3.0.2'
        }
      });

      if (Array.isArray(body) && body[0]?.url) return body[0].url;
      if (body?.url) return body.url;
      if (body?.streamUrl) return body.streamUrl;
    } catch (error) {
      console.log(`[vavoo] Direct resolve failed (${baseUrl}): ${error.message}`);
    }
  }

  return null;
}

// ===== CORS HEADERS =====

function corsHeaders() {
  return {
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
    'Access-Control-Allow-Headers': 'Content-Type, Range, User-Agent',
    'Access-Control-Expose-Headers': 'Content-Length, Content-Range, Content-Type'
  };
}

// ===== WORKER HANDLER =====

export default {
  async fetch(request, env) {
    globalThis.VAVOO_KV = env?.VAVOO_KV;

    if (request.method === 'OPTIONS') {
      return new Response(null, { headers: corsHeaders() });
    }

    const url = new URL(request.url);
    const baseUrl = `${url.protocol}//${url.host}`;
    const path = url.pathname;

    // ===== /play/<id> =====
    if (path.startsWith('/play/')) {
      const channelId = path.split('/')[2]?.split('|')[0];
      if (!channelId) {
        return new Response('ID manquant', { status: 400, headers: corsHeaders() });
      }

      try {
        const channel = await findChannel(channelId);
        const streamUrl = channel
          ? await resolveStream(channel)
          : await resolveDirect(channelId);

        if (!streamUrl) {
          return new Response(`Flux introuvable: ${channelId}`, { status: 404, headers: corsHeaders() });
        }

        if (channel) {
          console.log(`[vavoo] "${channel.name}" résolu: ${describeUrl(streamUrl)}`);
        }

        return await proxyStream(baseUrl, streamUrl);

      } catch (error) {
        console.log(`[vavoo] Erreur: ${error.message}`);
        return new Response(`Erreur: ${error.message}`, { status: 500, headers: corsHeaders() });
      }
    }

    // ===== /hls-proxy =====
    if (path === '/hls-proxy') {
      const upstreamUrl = url.searchParams.get('url');
      if (!upstreamUrl) {
        return new Response('Paramètre URL manquant', { status: 400, headers: corsHeaders() });
      }

      try {
        const parsed = new URL(upstreamUrl);
        if (!['http:', 'https:'].includes(parsed.protocol)) {
          return new Response('Protocole non supporté', { status: 400, headers: corsHeaders() });
        }
        const ext = pathExtension(upstreamUrl);
        if (ext && !ALLOWED_EXTENSIONS.has(ext)) {
          return new Response('Type de fichier non autorisé', { status: 403, headers: corsHeaders() });
        }

        let response = await fetch(upstreamUrl, {
          headers: getStreamHeaders()
        });

        if (response.status === 403 || response.status === 401) {
          response = await fetch(upstreamUrl, {
            headers: getPlaylistHeaders()
          });
        }

        console.log(`[vavoo] hls-proxy ${describeUrl(upstreamUrl)} -> ${response.status}`);

        if (!response.ok) {
          return new Response(`Erreur serveur: ${response.status}`, { status: response.status, headers: corsHeaders() });
        }

        const contentType = response.headers.get('content-type') || '';

        if (isM3u8Response(upstreamUrl, contentType)) {
          const playlist = await response.text();
          const rewritten = rewritePlaylist(baseUrl, upstreamUrl, playlist);
          return new Response(rewritten, {
            headers: {
              'Content-Type': 'application/vnd.apple.mpegurl; charset=utf-8',
              'Cache-Control': 'no-cache, no-store, must-revalidate',
              ...corsHeaders()
            }
          });
        }

        return new Response(response.body, {
          status: response.status,
          headers: {
            'Content-Type': contentType,
            'Content-Length': response.headers.get('content-length') || '',
            'Accept-Ranges': response.headers.get('accept-ranges') || '',
            'Cache-Control': 'public, max-age=3600',
            ...corsHeaders()
          }
        });

      } catch (error) {
        console.log(`[vavoo] Erreur proxy: ${error.message}`);
        return new Response(`Erreur proxy: ${error.message}`, { status: 500, headers: corsHeaders() });
      }
    }

    // ===== Accueil =====
    return new Response('Utilisation: /play/<id>', {
      status: 404,
      headers: corsHeaders()
    });
  }
};

async function proxyStream(baseUrl, streamUrl) {
  const response = await fetch(streamUrl, {
    headers: getStreamHeaders()
  });

  console.log(`[vavoo] play ${describeUrl(streamUrl)} -> ${response.status}`);

  if (!response.ok) {
    return new Response(`Erreur flux: ${response.status}`, { status: response.status, headers: corsHeaders() });
  }

  const contentType = response.headers.get('content-type') || '';

  if (isM3u8Response(streamUrl, contentType)) {
    const playlist = await response.text();
    const rewritten = rewritePlaylist(baseUrl, streamUrl, playlist);
    return new Response(rewritten, {
      headers: {
        'Content-Type': 'application/vnd.apple.mpegurl; charset=utf-8',
        'Cache-Control': 'no-cache, no-store, must-revalidate',
        ...corsHeaders()
      }
    });
  }

  return new Response(response.body, {
    status: response.status,
    headers: {
      'Content-Type': contentType,
      'Content-Length': response.headers.get('content-length') || '',
      'Accept-Ranges': response.headers.get('accept-ranges') || '',
      'Cache-Control': 'public, max-age=3600',
      ...corsHeaders()
    }
  });
}
