package com.codex.qwenchat;

import android.content.Context;
import android.net.Uri;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class StaticAssetCache {
    private static final long DEFAULT_TTL_MS = 7L * 24L * 60L * 60L * 1000L;
    private static final long VERSIONED_TTL_MS = 30L * 24L * 60L * 60L * 1000L;
    private static final Pattern HTML_ASSET_PATTERN =
            Pattern.compile("(?:src|href)=['\"]?([^'\" >]+)");
    private static final Set<String> STATIC_EXTENSIONS = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList(
                    ".css", ".js", ".mjs", ".json", ".map",
                    ".png", ".jpg", ".jpeg", ".gif", ".webp", ".svg", ".ico", ".apng",
                    ".woff", ".woff2", ".ttf", ".otf"
            ))
    );

    private final File cacheDir;
    private final String userAgent;
    private final ExecutorService prefetchExecutor = Executors.newSingleThreadExecutor();
    private final Set<String> inFlightUrls = ConcurrentHashMap.newKeySet();
    private volatile boolean entryPrefetchScheduled;

    StaticAssetCache(Context context, String userAgent) {
        this.cacheDir = new File(context.getCacheDir(), "qwen-static-assets");
        this.userAgent = userAgent;
        //noinspection ResultOfMethodCallIgnored
        this.cacheDir.mkdirs();
    }

    WebResourceResponse intercept(WebResourceRequest request) {
        if (request == null || request.getUrl() == null) {
            return null;
        }
        if (!"GET".equalsIgnoreCase(request.getMethod())) {
            return null;
        }

        String url = request.getUrl().toString();
        if (!isCacheable(url)) {
            return null;
        }

        CachedAsset cachedAsset = readCachedAsset(url);
        if (cachedAsset != null) {
            if (cachedAsset.isExpired()) {
                prefetchUrl(url, request.getRequestHeaders());
            }
            return cachedAsset.toResponse();
        }

        prefetchUrl(url, request.getRequestHeaders());
        return null;
    }

    void prefetchEntryAssets(final String entryUrl) {
        if (entryPrefetchScheduled) {
            return;
        }
        entryPrefetchScheduled = true;
        prefetchExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    for (String assetUrl : extractEntryAssets(entryUrl)) {
                        prefetchUrl(assetUrl, Collections.<String, String>emptyMap());
                    }
                } finally {
                    entryPrefetchScheduled = false;
                }
            }
        });
    }

    void shutdown() {
        prefetchExecutor.shutdownNow();
    }

    private void prefetchUrl(final String rawUrl, final Map<String, String> requestHeaders) {
        final String normalizedUrl = normalizeUrl(rawUrl);
        if (normalizedUrl == null || !isCacheable(normalizedUrl)) {
            return;
        }
        CachedAsset cachedAsset = readCachedAsset(normalizedUrl);
        if (cachedAsset != null && !cachedAsset.isExpired()) {
            return;
        }
        if (!inFlightUrls.add(normalizedUrl)) {
            return;
        }
        prefetchExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    downloadAndStore(normalizedUrl, requestHeaders);
                } finally {
                    inFlightUrls.remove(normalizedUrl);
                }
            }
        });
    }

    private List<String> extractEntryAssets(String entryUrl) {
        String html = downloadText(entryUrl);
        if (html == null || html.trim().isEmpty()) {
            return Collections.emptyList();
        }

        LinkedHashSet<String> assets = new LinkedHashSet<>();
        Matcher matcher = HTML_ASSET_PATTERN.matcher(html);
        while (matcher.find()) {
            String candidate = normalizeUrl(resolveUrl(entryUrl, matcher.group(1)));
            if (candidate != null && isCacheable(candidate)) {
                assets.add(candidate);
            }
        }
        return new ArrayList<>(assets);
    }

    private String downloadText(String url) {
        HttpURLConnection connection = null;
        try {
            connection = openConnection(url, Collections.<String, String>emptyMap());
            if (connection.getResponseCode() < 200 || connection.getResponseCode() >= 300) {
                return null;
            }
            InputStream inputStream = connection.getInputStream();
            StringBuilder builder = new StringBuilder();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                builder.append(new String(buffer, 0, read, "UTF-8"));
            }
            return builder.toString();
        } catch (IOException e) {
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private void downloadAndStore(String url, Map<String, String> requestHeaders) {
        HttpURLConnection connection = null;
        InputStream inputStream = null;
        OutputStream outputStream = null;
        try {
            connection = openConnection(url, requestHeaders);
            int statusCode = connection.getResponseCode();
            if (statusCode < 200 || statusCode >= 300) {
                return;
            }

            File tempDataFile = new File(cacheDir, sha256(url) + ".tmp");
            inputStream = connection.getInputStream();
            outputStream = new FileOutputStream(tempDataFile);
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, read);
            }
            outputStream.flush();
            outputStream.close();
            outputStream = null;

            String contentType = connection.getContentType();
            if (contentType == null || contentType.trim().isEmpty()) {
                contentType = URLConnection.guessContentTypeFromName(url);
            }
            if (contentType == null || contentType.trim().isEmpty()) {
                contentType = "application/octet-stream";
            }

            File dataFile = new File(cacheDir, sha256(url) + ".data");
            if (dataFile.exists() && !dataFile.delete()) {
                return;
            }
            if (!tempDataFile.renameTo(dataFile)) {
                return;
            }

            Properties properties = new Properties();
            properties.setProperty("url", url);
            properties.setProperty("savedAt", String.valueOf(System.currentTimeMillis()));
            properties.setProperty("ttlMs", String.valueOf(ttlFor(url)));
            properties.setProperty("contentType", contentType);
            properties.setProperty(
                    "cacheControl",
                    valueOrDefault(connection.getHeaderField("Cache-Control"), "public, max-age=604800")
            );
            properties.setProperty(
                    "accessControlAllowOrigin",
                    valueOrDefault(connection.getHeaderField("Access-Control-Allow-Origin"), "*")
            );
            properties.setProperty(
                    "timingAllowOrigin",
                    valueOrDefault(connection.getHeaderField("Timing-Allow-Origin"), "*")
            );

            File tempMetaFile = new File(cacheDir, sha256(url) + ".properties.tmp");
            FileOutputStream metaOutputStream = new FileOutputStream(tempMetaFile);
            try {
                properties.store(metaOutputStream, "Qwen static asset cache");
            } finally {
                metaOutputStream.close();
            }

            File metaFile = new File(cacheDir, sha256(url) + ".properties");
            if (metaFile.exists() && !metaFile.delete()) {
                return;
            }
            //noinspection ResultOfMethodCallIgnored
            tempMetaFile.renameTo(metaFile);
        } catch (IOException ignored) {
        } finally {
            closeQuietly(inputStream);
            closeQuietly(outputStream);
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private HttpURLConnection openConnection(String url, Map<String, String> requestHeaders)
            throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(15_000);
        connection.setUseCaches(true);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", userAgent);
        connection.setRequestProperty("Accept-Encoding", "identity");
        if (requestHeaders != null) {
            copyHeaderIfPresent(connection, requestHeaders, "Accept");
            copyHeaderIfPresent(connection, requestHeaders, "Accept-Language");
            copyHeaderIfPresent(connection, requestHeaders, "Referer");
            copyHeaderIfPresent(connection, requestHeaders, "Origin");
        }
        return connection;
    }

    private void copyHeaderIfPresent(
            HttpURLConnection connection,
            Map<String, String> requestHeaders,
            String headerName
    ) {
        String value = requestHeaders.get(headerName);
        if (value != null && !value.trim().isEmpty()) {
            connection.setRequestProperty(headerName, value);
        }
    }

    private CachedAsset readCachedAsset(String url) {
        try {
            String fileKey = sha256(url);
            File dataFile = new File(cacheDir, fileKey + ".data");
            File metaFile = new File(cacheDir, fileKey + ".properties");
            if (!dataFile.exists() || !metaFile.exists()) {
                return null;
            }

            Properties properties = new Properties();
            FileInputStream metaInputStream = new FileInputStream(metaFile);
            try {
                properties.load(metaInputStream);
            } finally {
                metaInputStream.close();
            }

            long savedAt = parseLong(properties.getProperty("savedAt"), 0L);
            long ttlMs = parseLong(properties.getProperty("ttlMs"), ttlFor(url));
            String contentType = valueOrDefault(
                    properties.getProperty("contentType"),
                    "application/octet-stream"
            );

            HashMap<String, String> responseHeaders = new HashMap<>();
            responseHeaders.put(
                    "Cache-Control",
                    valueOrDefault(properties.getProperty("cacheControl"), "public, max-age=604800")
            );
            responseHeaders.put(
                    "Access-Control-Allow-Origin",
                    valueOrDefault(properties.getProperty("accessControlAllowOrigin"), "*")
            );
            responseHeaders.put(
                    "Timing-Allow-Origin",
                    valueOrDefault(properties.getProperty("timingAllowOrigin"), "*")
            );
            responseHeaders.put("X-Cache-Source", "disk");
            return new CachedAsset(dataFile, contentType, savedAt, ttlMs, responseHeaders);
        } catch (IOException e) {
            return null;
        }
    }

    private boolean isCacheable(String rawUrl) {
        String normalizedUrl = normalizeUrl(rawUrl);
        if (normalizedUrl == null) {
            return false;
        }

        Uri uri = Uri.parse(normalizedUrl);
        String scheme = uri.getScheme();
        if (scheme == null
                || (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme))) {
            return false;
        }

        String host = uri.getHost();
        if (host == null) {
            return false;
        }
        host = host.toLowerCase(Locale.US);
        if (!(host.endsWith("alicdn.com")
                || host.endsWith("qwen.ai")
                || host.endsWith("qianwen.com"))) {
            return false;
        }

        String path = uri.getPath();
        if (path == null || path.trim().isEmpty()) {
            return false;
        }
        String lowerPath = path.toLowerCase(Locale.US);
        if (lowerPath.contains("/api/") || "/".equals(lowerPath) || lowerPath.startsWith("/c/")) {
            return false;
        }
        if (lowerPath.contains("/js/")
                || lowerPath.contains("/css/")
                || lowerPath.contains("/images/")
                || lowerPath.contains("/img/")
                || lowerPath.contains("/fonts/")
                || lowerPath.contains("/assets/")) {
            return true;
        }
        for (String extension : STATIC_EXTENSIONS) {
            if (lowerPath.endsWith(extension)) {
                return true;
            }
        }
        return false;
    }

    private long ttlFor(String url) {
        String lowerUrl = url.toLowerCase(Locale.US);
        if (lowerUrl.contains("/qwen-chat-fe/") || lowerUrl.contains("/g/")) {
            return VERSIONED_TTL_MS;
        }
        return DEFAULT_TTL_MS;
    }

    private String resolveUrl(String baseUrl, String candidate) {
        if (candidate == null || candidate.trim().isEmpty()) {
            return null;
        }
        if (candidate.startsWith("data:") || candidate.startsWith("blob:")) {
            return null;
        }
        if (candidate.startsWith("//")) {
            return "https:" + candidate;
        }
        try {
            return new URL(new URL(baseUrl), candidate).toString();
        } catch (IOException e) {
            return null;
        }
    }

    private String normalizeUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.trim().isEmpty()) {
            return null;
        }
        String normalizedUrl = rawUrl.trim();
        if (normalizedUrl.startsWith("//")) {
            normalizedUrl = "https:" + normalizedUrl;
        }
        int fragmentIndex = normalizedUrl.indexOf('#');
        if (fragmentIndex >= 0) {
            normalizedUrl = normalizedUrl.substring(0, fragmentIndex);
        }
        return normalizedUrl;
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes("UTF-8"));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte currentByte : bytes) {
                builder.append(String.format(Locale.US, "%02x", currentByte));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to hash URL", e);
        }
    }

    private String valueOrDefault(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value;
    }

    private long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private void closeQuietly(InputStream inputStream) {
        if (inputStream == null) {
            return;
        }
        try {
            inputStream.close();
        } catch (IOException ignored) {
        }
    }

    private void closeQuietly(OutputStream outputStream) {
        if (outputStream == null) {
            return;
        }
        try {
            outputStream.close();
        } catch (IOException ignored) {
        }
    }

    private static final class CachedAsset {
        private final File dataFile;
        private final String contentType;
        private final long savedAt;
        private final long ttlMs;
        private final Map<String, String> responseHeaders;

        private CachedAsset(
                File dataFile,
                String contentType,
                long savedAt,
                long ttlMs,
                Map<String, String> responseHeaders
        ) {
            this.dataFile = dataFile;
            this.contentType = contentType;
            this.savedAt = savedAt;
            this.ttlMs = ttlMs;
            this.responseHeaders = responseHeaders;
        }

        private boolean isExpired() {
            return System.currentTimeMillis() - savedAt > ttlMs;
        }

        private WebResourceResponse toResponse() {
            try {
                String mimeType = contentType;
                String encoding = null;
                int separatorIndex = contentType.indexOf(';');
                if (separatorIndex >= 0) {
                    mimeType = contentType.substring(0, separatorIndex).trim();
                    String meta = contentType.substring(separatorIndex + 1).trim().toLowerCase(Locale.US);
                    if (meta.startsWith("charset=")) {
                        encoding = meta.substring("charset=".length()).trim();
                    }
                }
                if (mimeType == null || mimeType.trim().isEmpty()) {
                    mimeType = "application/octet-stream";
                }
                return new WebResourceResponse(
                        mimeType,
                        encoding,
                        200,
                        "OK",
                        responseHeaders,
                        new FileInputStream(dataFile)
                );
            } catch (IOException e) {
                return null;
            }
        }
    }
}
