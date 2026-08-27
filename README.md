# Vavoo IPTV – Playlist complète (tous pays)

Ce dépôt génère automatiquement une playlist M3U contenant **toutes les chaînes Vavoo** (tous pays) ainsi qu’un guide des programmes (EPG) basé sur `epgshare01.online`.

## 📺 Utilisation

Ajoute les liens suivants dans ton lecteur IPTV (VLC, TiviMate, Kodi, etc.) :

- **Playlist M3U** : `https://raw.githubusercontent.com/[TON_USER]/[TON_REPO]/main/iptv.m3u`
- **Guide EPG** : `https://raw.githubusercontent.com/[TON_USER]/[TON_REPO]/main/epg.xml`

*(Remplace `[TON_USER]` et `[TON_REPO]` par ton nom d’utilisateur GitHub et le nom de ce dépôt.)*

## 🛠️ Fonctionnement

- Le script `scripts/build.js` interroge l’API de Vavoo (`mediahubmx-catalog.json`) pour récupérer toutes les chaînes (sans filtre de pays).
- Il utilise un proxy Cloudflare (par défaut `https://vavoo-iptv-proxy.vavoo-iptv.workers.dev`) pour résoudre les URLs de streaming.
- Un workflow GitHub Actions s’exécute toutes les 6 heures (et à chaque push sur `main`) pour mettre à jour les fichiers générés.
- L’EPG est fusionné à partir de `epgshare01.online` (peut être modifié via variable d’environnement).

## ⚙️ Configuration (variables d’environnement)

Tu peux personnaliser le comportement en définissant ces variables dans les secrets/actions de ton dépôt :

| Variable | Description | Défaut |
|----------|-------------|--------|
| `PROXY_BASE` | URL de ton proxy Vavoo | `https://vavoo-iptv-proxy.vavoo-iptv.workers.dev` |
| `EPG_UPSTREAM_URL` | Source de l’EPG XML | `https://epgshare01.online/epgshare01/epg_ripper_TR1.xml.gz` |
| `COUNTRY_FILTER` | Filtrer les chaînes par pays (ex: `France`) | (vide = tous) |
| `EPG_URL` | URL publique de l’EPG généré | (à adapter) |

## 📄 Licence

MIT
