# Vavoo IPTV – Playlist complète

Génère automatiquement une playlist M3U de toutes les chaînes Vavoo (tous pays) avec catégories en français.

## Liens

- **Playlist M3U** : `https://raw.githubusercontent.com/Nissay8z/Te/main/iptv.m3u`
- **Guide EPG** : `https://raw.githubusercontent.com/Nissay8z/Te/main/epg.xml`

## Fonctionnement

- Le script `scripts/build.js` interroge l’API Vavoo et nettoie les noms.
- Les chaînes sont catégorisées par pays ou genre (France, Sport, Cinéma…).
- Un proxy Cloudflare résout les flux.
- Mise à jour automatique toutes les 6 heures.
