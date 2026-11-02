# Audit TV Hub ONYX 1.8.11 et migration IPTV (2 novembre 2026)

APK inspectée : `onyx-v1.8.11-universal.apk` (fourni par l'utilisateur, 65 533 084 octets).
Méthode : inspection de l'archive Android, inventaire des descripteurs de classes et des méthodes DEX. Pas de décompilation complète des implémentations, pas de test sur appareil, pas d'accès confirmé aux flux.

## Décision d'architecture

**Repartir de la section TV d'ONYX plutôt que convertir chacun de ses providers en playlist M3U dans IPTV Pro.** Les modules Vegeta TV et OLA TV ne sont pas de simples M3U : ils gèrent le chargement du catalogue, l'agrégation des serveurs, les caches, l'état réseau, et l'obtention de flux au lancement.

**Retirer Free-TV, Pluto, Plex et Samsung** des sources *ajoutées artificiellement dans IPTV Pro*. Les rubriques éventuellement présentes à l'intérieur du TV Hub d'ONYX restent des rubriques natives de son moteur, pas des playlists autonomes dans l'accueil.

Deux sources favorites explicites : (1) VAVOO ; (2) IPTV-org. Garder les autres modules TV *réellement présents dans ONYX* dans leur hiérarchie du TV Hub (Vegeta, OLA, replays, etc.). Aucun autre onglet VOD/films/séries/animes dans le lecteur TV demandé.

## Modules vérifiés dans le DEX de l'APK fournie

| Classe présente | Fonctions observables par noms de méthodes | Statut |
|---|---|---|
| `VavooProvider` | `getAddonSignature`, `loadCatalogFromBase`, `resolveStreamUrlsMulti`, `serveursVavoo`, `getOrderedChannelIds`, `getServers`, `setCountryFilter` | Code compilé présent, flux non testés |
| `VegetaTvProvider` | `ensureRegistry`, `loadRemoteRegistry`, `ingestServerChannelsViaStalker`, `ingestServerChannelsViaXtreamM3u`, `getServers`, `resolveStreamCmd` | Code compilé présent, dépend de serveurs distants |
| `OlaTvProvider` | `chargerRegistreComplet`, `olaListMacPortalChannels`, `scanAdditionalFrCids`, `getServers`, `probeStreamPlayable` | Code compilé présent, flux non testés |
| `LiveTvHubProvider` | `groupSectionsIntoFolders`, `getFolderContents`, `getServers`, `fetchStream4CfCategoriesLive`, `fetchMixFrCategoriesPublic`, `fetchWorldwideCategoriesPublic`, `fetchReplayCategoriesPublic` | Organisation native TV Hub / replays |
| `BoxXtemusProvider`, `Dric4rTvProvider` | classes de catalogue et lecteurs, `resolveExternalChannel`, `loadAllChannels` | Code compilé présent |
| `IptvSourcesActivity`, `IptvProvider`, `MyIptvProvider` | composants d'import de sources IPTV | UI présente, import IPTV-org Monde à confirmer |
| `VavooMirrorSettings`, `VavooCountrySettings`, `LiveHubFolderDialog` | miroirs, pays, dossiers et navigation | Code compilé présent |

D'autres noms repérés dans le TV Hub : **Reneveo**, **Mix FR**, **Stream4Free**, **OTF**, **replays France.tv**, **Arte**, **BFM**, **TF1/M6**, **World Live TV**. Il faut les qualifier avec un test fonctionnel avant d'annoncer leur disponibilité.

L'APK contient littéralement :
- `https://iptv-org.github.io/iptv/countries/fr.m3u`
- `https://iptv-org.github.io/iptv/categories/music.m3u`

Pour le catalogue IPTV-org tous pays demandé : `https://iptv-org.github.io/iptv/index.country.m3u`. Ne pas confondre une référence de playlist avec un flux garanti lisible.

## Ce qui manque encore à la livraison

1. **Base d'application :** le module TV d'ONYX doit être conservé / réutilisé comme moteur. Les DEX de providers dépendent des modèles et services internes d'ONYX ; les greffer seuls dans IPTV Pro sans adapter les dépendances ne fonctionne pas.
2. **Mode TV uniquement :** masquer les onglets hors TV dans la navigation tout en conservant les classes TV nécessaires. Ceci exige l'inspection et la modification ciblée de l'interface ONYX, pas un renommage du fichier APK.
3. **IPTV-org monde :** ajouter ce profil dans l'interface d'import native ou dans son état initial, sans affecter les providers internes.
4. **UI et tiroirs :** préserver les dossiers TV Hub, sous-dossiers, favoris et les serveurs alternatifs. La source ne se change pas par la suppression des variantes HD/FHD.
5. **Tests obligatoires :** démarrage de l'APK signée, TV Hub visible, VAVOO, Vegeta TV, OLA TV, IPTV-org, D-pad sur box TV, lecture, retour et choix du serveur, reconnection réseau. Sans ces tests, ne pas promettre « tout fonctionne ».

## Limitations et précautions

- L'APK ONYX fournie est un binaire tiers. La présence d'une classe prouve une implémentation empaquetée, **pas** que le fournisseur est actuellement accessible.
- Aucun contournement d'authentification, de DRM, de comptes ou de portails privés n'est inclus.
- Les informations personnelles et identifiants des serveurs ne doivent pas être intégrés à des builds publics.
- L'existant `Nissay8z/Te` utilise une architecture IPTV Pro + M3U qui ne reproduit pas les providers ONYX.
