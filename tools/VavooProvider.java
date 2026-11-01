package ru.iptvremote.android.iptv.vavoo;

import android.os.Build;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Native VAVOO catalog and stream resolver.
 *
 * Uses VAVOO's app ping to obtain a short-lived addon signature, the
 * MediaHubMX catalog endpoint for channel metadata, and the resolve endpoint
 * when a channel is played. No separate Python/Node service is required.
 */
public final class VavooProvider {
    private static final String[] PING_URLS = {
        "https://www.vavoo.tv/api/app/ping",
        "https://vavoo.tv/api/app/ping"
    };
    private static final String[] BASE_URLS = {
        "https://vavoo.to",
        "https://kool.to",
        "https://vavoo.top",
        "https://vavoo.net"
    };
    private static final String LANGUAGE = "fr";
    // A broad region returns a larger catalog; channels are grouped by their own country metadata.
    private static final String REGION = "US";
    private static final String CLIENT_VERSION = "3.0.2";
    private static final long SIGNATURE_CACHE_MS = 4 * 60 * 1000L;
    private static final long CATALOG_CACHE_MS = 5 * 60 * 1000L;

    private static volatile String cachedSignature;
    private static volatile long signatureExpiryMs;
    private static volatile List<VavooChannel> cachedChannels = Collections.emptyList();
    private static volatile long catalogExpiryMs;

    private VavooProvider() {}

    public interface CatalogListener {
        /** Called on the loader thread after a catalogue page has been appended. */
        void onChannelsUpdated(List<VavooChannel> channels);
    }

    public static final class VavooChannel {
        public final String id;
        public final String name;
        public final String sourceUrl;
        public final String logo;
        public final String group;
        public final String country;

        VavooChannel(String id, String name, String sourceUrl, String logo, String group, String country) {
            this.id = id;
            this.name = name;
            this.sourceUrl = sourceUrl;
            this.logo = logo;
            this.group = group;
            this.country = country;
        }
    }

    public static List<VavooChannel> loadChannels() throws IOException, JSONException {
        return loadChannels(null);
    }

    public static List<VavooChannel> loadChannels(CatalogListener listener) throws IOException, JSONException {
        long now = System.currentTimeMillis();
        List<VavooChannel> current = cachedChannels;
        if (!current.isEmpty() && now < catalogExpiryMs) {
            List<VavooChannel> result = new ArrayList<>(current);
            notifyCatalogListener(listener, result);
            return result;
        }

        String signature = getAddonSignature();
        IOException lastError = null;
        for (String base : BASE_URLS) {
            try {
                List<VavooChannel> channels = loadCatalogFromBase(base, signature, listener);
                if (!channels.isEmpty()) {
                    cachedChannels = new ArrayList<>(channels);
                    catalogExpiryMs = System.currentTimeMillis() + CATALOG_CACHE_MS;
                    notifyCatalogListener(listener, channels);
                    return channels;
                }
                lastError = new IOException("Le catalogue VAVOO reçu depuis " + host(base) + " est vide.");
            } catch (IOException | JSONException e) {
                lastError = e instanceof IOException ? (IOException) e : new IOException(e.getMessage(), e);
            }
        }
        throw lastError != null ? lastError : new IOException("Impossible de récupérer le catalogue VAVOO.");
    }

    private static void notifyCatalogListener(CatalogListener listener, List<VavooChannel> channels) {
        if (listener == null || channels == null || channels.isEmpty()) return;
        try {
            listener.onChannelsUpdated(new ArrayList<>(channels));
        } catch (RuntimeException ignored) {
            // A UI listener must not break the network catalogue loader.
        }
    }

    public static void invalidateCatalogCache() {
        cachedChannels = Collections.emptyList();
        catalogExpiryMs = 0L;
    }

    public static String resolveStream(String id) throws IOException, JSONException {
        if (id == null || id.trim().isEmpty()) {
            throw new IOException("ID de chaîne VAVOO vide.");
        }
        String signature = getAddonSignature();
        IOException lastError = null;
        for (String base : BASE_URLS) {
            try {
                JSONObject payload = new JSONObject();
                payload.put("language", LANGUAGE);
                payload.put("region", REGION);
                payload.put("url", buildPlayUrl(id));
                payload.put("clientVersion", CLIENT_VERSION);

                String response = postJson(base + "/mediahubmx-resolve.json", payload, signature, true);
                String stream = extractResolvedUrl(response);
                if (stream != null && (stream.startsWith("http://") || stream.startsWith("https://"))) {
                    return stream;
                }
                lastError = new IOException("VAVOO n'a renvoyé aucune URL de flux depuis " + host(base) + ".");
            } catch (IOException | JSONException e) {
                lastError = e instanceof IOException ? (IOException) e : new IOException(e.getMessage(), e);
            }
        }
        throw lastError != null ? lastError : new IOException("Impossible de résoudre ce flux VAVOO.");
    }

    /**
     * MediaHubMX resolver expects the canonical VAVOO play URL, not a bare ID.
     * A playlist item stores only the final stream ID, so rebuild that URL here.
     */
    private static String buildPlayUrl(String rawId) throws IOException {
        String id = rawId == null ? "" : rawId.trim();
        if (id.isEmpty()) throw new IOException("ID de chaîne VAVOO vide.");
        return "https://vavoo.to/vavoo-iptv/play/" + Uri.encode(id);
    }

    /** Extracts the upstream ID from a MediaHub catalog item's URL. */
    private static String extractStreamId(String sourceUrl) {
        try {
            String path = Uri.parse(sourceUrl).getPath();
            if (path == null || path.trim().isEmpty()) return "";
            String marker = "/vavoo-iptv/play/";
            int markerIndex = path.indexOf(marker);
            String value = markerIndex >= 0
                    ? path.substring(markerIndex + marker.length())
                    : path.substring(path.lastIndexOf('/') + 1);
            int slash = value.indexOf('/');
            if (slash >= 0) value = value.substring(0, slash);
            if (value.endsWith(".m3u8")) value = value.substring(0, value.length() - 5);
            value = Uri.decode(value).trim();
            if (value.isEmpty() || value.equalsIgnoreCase("play")
                    || value.contains("?") || value.contains("#")) return "";
            return value;
        } catch (Exception ignored) {
            return "";
        }
    }

    private static List<VavooChannel> loadCatalogFromBase(String base, String signature,
            CatalogListener listener) throws IOException, JSONException {
        ArrayList<VavooChannel> channels = new ArrayList<>();
        Object cursor = JSONObject.NULL;
        int pages = 0;
        boolean finished = false;
        while (pages < 300) {
            pages++;
            JSONObject payload = new JSONObject();
            payload.put("language", LANGUAGE);
            payload.put("region", REGION);
            payload.put("catalogId", "iptv");
            payload.put("id", "iptv");
            payload.put("adult", false);
            payload.put("search", "");
            payload.put("sort", "");
            payload.put("filter", new JSONObject());
            payload.put("cursor", cursor == null ? JSONObject.NULL : cursor);
            payload.put("clientVersion", CLIENT_VERSION);

            String response = postJson(base + "/mediahubmx-catalog.json", payload, signature, true);
            JSONObject body = new JSONObject(response);
            JSONArray items = body.optJSONArray("items");
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.optJSONObject(i);
                    if (item == null || !"iptv".equalsIgnoreCase(item.optString("type"))) continue;
                    String sourceUrl = item.optString("url", "").trim();
                    if (sourceUrl.isEmpty()) continue;
                    String name = item.optString("name", "Chaîne VAVOO").trim();
                    String group = item.optString("group", "").trim();
                    String logo = item.optString("logo", "").trim();
                    String country = item.optString("country", "").trim();
                    if(country.isEmpty())country=item.optString("countryName", "").trim();
                    if(country.isEmpty())country=item.optString("country_name", "").trim();
                    if(country.isEmpty())country=extractCountry(group);
                    String id = extractStreamId(sourceUrl);
                    if (id.isEmpty()) continue;
                    channels.add(new VavooChannel(id, name, sourceUrl, logo,
                            group.isEmpty() ? "VAVOO Live" : group, country));
                }
            }
            // Publish each completed page so the TV UI can show channels/categories
            // before the entire remote catalogue finishes loading.
            notifyCatalogListener(listener, channels);
            Object next = body.opt("nextCursor");
            if (next == null || next == JSONObject.NULL || String.valueOf(next).trim().isEmpty()) {
                finished = true;
                break;
            }
            cursor = next;
        }
        if (!finished) throw new IOException("Le catalogue VAVOO dépasse la limite de pagination.");
        return channels;
    }

    private static String getAddonSignature() throws IOException, JSONException {
        long now = System.currentTimeMillis();
        String value = cachedSignature;
        if (value != null && !value.isEmpty() && now < signatureExpiryMs) return value;

        long timestamp = now;
        JSONObject device = new JSONObject();
        device.put("type", "mobile");
        device.put("uniqueId", "nisskenzi-" + UUID.randomUUID());

        JSONArray abis = new JSONArray();
        if (Build.SUPPORTED_ABIS != null) {
            for (String abi : Build.SUPPORTED_ABIS) abis.put(abi);
        }
        if (abis.length() == 0) abis.put("unknown");

        JSONObject os = new JSONObject();
        os.put("name", "android");
        os.put("version", Build.VERSION.RELEASE == null ? "unknown" : Build.VERSION.RELEASE);
        os.put("abis", abis);
        os.put("host", Build.MANUFACTURER == null ? "android" : Build.MANUFACTURER);

        JSONObject app = new JSONObject();
        app.put("platform", "android");

        JSONObject version = new JSONObject();
        version.put("package", "tv.vavoo.app");
        version.put("binary", "3.1.8");
        version.put("js", "3.1.8");

        JSONObject metadata = new JSONObject();
        metadata.put("device", device);
        metadata.put("os", os);
        metadata.put("app", app);
        metadata.put("version", version);

        JSONObject proxy = new JSONObject();
        proxy.put("supported", new JSONArray().put("ss"));
        proxy.put("engine", "Mu");
        proxy.put("enabled", false);
        proxy.put("autoServer", true);

        JSONObject iap = new JSONObject();
        iap.put("supported", false);

        JSONObject payload = new JSONObject();
        payload.put("reason", "app-focus");
        payload.put("locale", LANGUAGE);
        payload.put("theme", "dark");
        payload.put("metadata", metadata);
        payload.put("appFocusTime", 0);
        payload.put("playerActive", false);
        payload.put("playDuration", 0);
        payload.put("devMode", false);
        payload.put("hasAddon", true);
        payload.put("castConnected", false);
        payload.put("package", "tv.vavoo.app");
        payload.put("version", "3.1.8");
        payload.put("process", "app");
        payload.put("firstAppStart", timestamp);
        payload.put("lastAppStart", timestamp);
        payload.put("ipLocation", JSONObject.NULL);
        payload.put("adblockEnabled", true);
        payload.put("proxy", proxy);
        payload.put("iap", iap);

        IOException lastError = null;
        for (String pingUrl : PING_URLS) {
            try {
                String response = postJson(pingUrl, payload, null, false);
                JSONObject json = new JSONObject(response);
                String signature = json.optString("addonSig", "").trim();
                if (!signature.isEmpty()) {
                    cachedSignature = signature;
                    signatureExpiryMs = System.currentTimeMillis() + SIGNATURE_CACHE_MS;
                    return signature;
                }
                lastError = new IOException("La réponse VAVOO ne contient pas addonSig.");
            } catch (IOException | JSONException e) {
                lastError = e instanceof IOException ? (IOException) e : new IOException(e.getMessage(), e);
            }
        }
        throw lastError != null ? lastError : new IOException("Impossible d'obtenir la signature VAVOO.");
    }

    private static String postJson(String address, JSONObject payload, String signature, boolean mediaHub)
            throws IOException {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(address).openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            connection.setInstanceFollowRedirects(true);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Connection", "close");
            connection.setRequestProperty("User-Agent", mediaHub ? "MediaHubMX/2" : "VAVOO/2.6");
            if (mediaHub) {
                connection.setRequestProperty("mediahubmx-signature", signature == null ? "" : signature);
                connection.setRequestProperty("Accept-Language", LANGUAGE);
            }

            byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(bytes);
            }

            int code = connection.getResponseCode();
            InputStream stream = code >= 200 && code < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            String body = stream == null ? "" : readAll(stream);
            if (code < 200 || code >= 300) {
                String details = body.length() > 280 ? body.substring(0, 280) : body;
                throw new IOException("HTTP " + code + " sur " + host(address) + (details.isEmpty() ? "" : " : " + details));
            }
            if (body.trim().isEmpty()) throw new IOException("Réponse vide depuis " + host(address) + ".");
            return body;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Requête VAVOO impossible : " + e.getMessage(), e);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String readAll(InputStream in) throws IOException {
        try (InputStream input = in; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toString("UTF-8");
        }
    }

    private static String extractResolvedUrl(String response) throws JSONException {
        String trimmed = response == null ? "" : response.trim();
        if (trimmed.startsWith("[")) {
            JSONArray array = new JSONArray(trimmed);
            if (array.length() == 0) return null;
            JSONObject first = array.optJSONObject(0);
            return first == null ? null : first.optString("url", first.optString("streamUrl", null));
        }
        JSONObject object = new JSONObject(trimmed);
        String url = object.optString("url", "");
        if (url.isEmpty()) url = object.optString("streamUrl", "");
        return url.isEmpty() ? null : url;
    }

    private static String extractCountry(String group) {
        if (group == null || group.trim().isEmpty()) return "default";
        String[] separators = {"➾", "⟾", "->", "→", "»", "›"};
        for (String separator : separators) {
            int index = group.indexOf(separator);
            if (index >= 0) {
                String country = group.substring(0, index).trim();
                return country.isEmpty() ? "default" : country;
            }
        }
        return group.trim();
    }

    private static String stableId(String name, String country) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] bytes = digest.digest((safe(country).toLowerCase() + "|" + safe(name).toLowerCase())
                    .getBytes(StandardCharsets.UTF_8));
            StringBuilder output = new StringBuilder();
            for (int i = 0; i < bytes.length && i < 11; i++) output.append(String.format("%02x", bytes[i]));
            return output.toString();
        } catch (Exception e) {
            return safe(country) + "-" + safe(name);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    private static String host(String url) {
        try {
            return new URL(url).getHost();
        } catch (Exception ignored) {
            return url;
        }
    }
}
