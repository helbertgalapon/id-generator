package org.financial;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.net.ConnectException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLParameters;

/**
 * Uploads generated files (e.g., ID PDFs) to Supabase Storage so the QR code can
 * be opened from any network (not only the same WiFi).
 *
 * This uses the Supabase Storage HTTP API directly via java.net.http.
 */
public final class SupabaseStorageService {

    private static final Gson GSON = new Gson();

    /** Default transport. */
    private static final HttpClient HTTP = buildHttpClient(false);
    /** Compatibility transport for networks/endpoints that reject the default TLS negotiation. */
    private static final HttpClient HTTP_TLS12 = buildHttpClient(true);

    private static final Duration UPLOAD_REQUEST_TIMEOUT = Duration.ofSeconds(120);
    private static final int UPLOAD_MAX_ATTEMPTS = 4;

    private static final ScheduledExecutorService EXECUTOR = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "supabase-storage");
        t.setDaemon(true);
        return t;
    });

    private static final AtomicBoolean backgroundStarted = new AtomicBoolean(false);
    private static final Set<Integer> pendingIdPdfUploads = ConcurrentHashMap.newKeySet();
    private static final Set<Integer> uploadedIdPdfRecords = ConcurrentHashMap.newKeySet();
    private static final Path PENDING_QUEUE_PATH = Path.of(
            System.getProperty("user.home"),
            "IdGenerator",
            "pending-supabase-uploads.json"
    );
    private static final Path UPLOADED_MARKER_PATH = Path.of(
            System.getProperty("user.home"),
            "IdGenerator",
            "uploaded-supabase-pdfs.json"
    );

    private SupabaseStorageService() {
        // no instances
    }

    private static HttpClient buildHttpClient(boolean forceTls12) {
        // Respect OS proxy configuration (important on managed/corporate networks).
        System.setProperty("java.net.useSystemProxies", "true");
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(60))
                .version(HttpClient.Version.HTTP_1_1)
                .proxy(ProxySelector.getDefault());
        if (forceTls12) {
            builder.sslParameters(new SSLParameters(new String[]{"TLSv1.2"}));
        }
        return builder.build();
    }

    /**
     * Starts the background uploader that retries pending uploads when internet is available.
     * Safe to call multiple times.
     */
    public static void startBackgroundUploader() {
        if (!backgroundStarted.compareAndSet(false, true)) {
            return;
        }

        EXECUTOR.execute(() -> {
            loadPendingQueueFromDisk();
            loadUploadedMarkersFromDisk();
            enqueueKnownRecordsIfNeeded();
            // Quick first attempt on app start.
            flushPendingUploads();
        });

        EXECUTOR.scheduleAtFixedRate(() -> {
            try {
                flushPendingUploads();
            } catch (Exception e) {
                SyncLogger.log("SupabaseStorage background flush error", e);
            }
        }, 10, 30, TimeUnit.SECONDS);
    }

    /**
     * Queue the current ID record's PDF for upload. If offline, it will be retried automatically.
     */
    public static void enqueueIdPdfUpload(IdRecord r) {
        if (r == null || r.id() <= 0) return;
        startBackgroundUploader();
        pendingIdPdfUploads.add(r.id());
        uploadedIdPdfRecords.remove(r.id());
        persistPendingQueueToDiskAsync();
        persistUploadedMarkersToDiskAsync();
        EXECUTOR.execute(SupabaseStorageService::flushPendingUploads);
    }

    /**
     * Generates the ID PDF and uploads it to Supabase Storage.
     *
     * @return public URL to the PDF (if upload succeeds), otherwise empty.
     */
    public static Optional<String> uploadIdPdfAndGetPublicUrl(IdRecord r) {
        if (r == null) return Optional.empty();
        byte[] pdf = IdPdfGenerator.toPdfFromBackground(r);
        if (pdf == null || pdf.length == 0) {
            SyncLogger.log("SupabaseStorage: no PDF bytes generated for record id=" + r.id());
            return Optional.empty();
        }
        Optional<String> url = uploadBytesAndGetPublicUrl(
                "application/pdf",
                buildObjectPathForIdPdf(r),
                pdf
        );
        if (url.isPresent() && r.id() > 0) {
            uploadedIdPdfRecords.add(r.id());
            persistUploadedMarkersToDiskAsync();
        }
        return url;
    }

    /**
     * Public Storage URL for this record's PDF when Supabase is configured.
     * Same path as {@link #uploadIdPdfAndGetPublicUrl(IdRecord)} returns on success, so the QR can
     * point at the hosted PDF before/without blocking on upload.
     */
    public static Optional<String> getPublicPdfUrlForRecord(IdRecord r) {
        if (r == null || r.id() <= 0) return Optional.empty();
        startBackgroundUploader();
        if (pendingIdPdfUploads.contains(r.id())) {
            return Optional.empty();
        }
        Optional<SupabaseConfig> cfgOpt = SupabaseConfig.load();
        if (cfgOpt.isEmpty()) return Optional.empty();
        SupabaseConfig cfg = cfgOpt.get();
        String supabaseUrl = normalizeBaseUrl(cfg.getUrl());
        String bucket = cfg.getStorageBucket();
        if (supabaseUrl.isEmpty() || bucket.isEmpty()) return Optional.empty();
        if (!uploadedIdPdfRecords.contains(r.id())) {
            enqueueIdPdfUpload(r);
            return Optional.empty();
        }
        return Optional.of(buildPublicUrl(supabaseUrl, bucket, buildObjectPathForIdPdf(r)));
    }

    /**
     * Deletes the Supabase Storage object for the given ID PDF, if possible.
     * This is best-effort: failures are logged but do not throw.
     */
    public static void deleteIdPdf(IdRecord r) {
        if (r == null) return;
        // Try deleting current and legacy naming schemes (best-effort).
        deleteObject(buildObjectPathForIdPdf(r));
        deleteObject(buildLegacyObjectPathForIdPdf(r));
        deleteObject(buildLegacyObjectPathV2IdQrNoName(r));
        if (r.id() > 0) {
            pendingIdPdfUploads.remove(r.id());
            uploadedIdPdfRecords.remove(r.id());
            persistPendingQueueToDiskAsync();
            persistUploadedMarkersToDiskAsync();
        }
    }

    /**
     * Deletes only legacy object keys (for cleanup/migrations), and never deletes the current key.
     */
    public static void deleteLegacyIdPdfOnly(IdRecord r) {
        if (r == null) return;
        deleteObject(buildLegacyObjectPathForIdPdf(r));
        deleteObject(buildLegacyObjectPathV2IdQrNoName(r));
    }

    private static String buildObjectPathForIdPdf(IdRecord r) {
        // Keep the object path stable (so re-uploads replace the same object),
        // but also include the numeric record id to make ownership identifiable in the bucket.
        String uid = (r.qrUid() != null && !r.qrUid().isBlank()) ? r.qrUid() : "";
        int id = r.id();
        String nameSlug = ownerSlug(r.name());
        if (!uid.isBlank() && id > 0) {
            // Preferred: contains record id + qr uid + readable owner name
            return "pdf/id-" + id + "_qr-" + uid + (nameSlug.isBlank() ? "" : ("_" + nameSlug)) + ".pdf";
        }
        if (!uid.isBlank()) {
            return "pdf/qr-" + uid + (nameSlug.isBlank() ? "" : ("_" + nameSlug)) + ".pdf";
        }
        if (id > 0) {
            return "pdf/id-" + id + (nameSlug.isBlank() ? "" : ("_" + nameSlug)) + ".pdf";
        }
        return "pdf/unknown.pdf";
    }

    private static String buildLegacyObjectPathForIdPdf(IdRecord r) {
        String uid = (r.qrUid() != null && !r.qrUid().isBlank()) ? r.qrUid() : ("id-" + r.id());
        return "pdf/qr-" + uid + ".pdf";
    }

    private static String buildLegacyObjectPathV2IdQrNoName(IdRecord r) {
        String uid = (r.qrUid() != null && !r.qrUid().isBlank()) ? r.qrUid() : "";
        int id = r.id();
        if (!uid.isBlank() && id > 0) {
            return "pdf/id-" + id + "_qr-" + uid + ".pdf";
        }
        return "";
    }

    private static String ownerSlug(String name) {
        if (name == null) return "";
        String s = name.trim();
        if (s.isEmpty()) return "";
        // Keep it filesystem & URL friendly. Encode still happens later per-segment.
        s = s.replaceAll("[^A-Za-z0-9]+", "-");
        s = s.replaceAll("^-+|-+$", "");
        if (s.length() > 40) {
            s = s.substring(0, 40);
        }
        return s;
    }

    private static Optional<String> uploadBytesAndGetPublicUrl(String contentType, String objectPath, byte[] bytes) {
        Optional<SupabaseConfig> cfgOpt = SupabaseConfig.load();
        if (cfgOpt.isEmpty()) return Optional.empty();

        SupabaseConfig cfg = cfgOpt.get();
        String supabaseUrl = normalizeBaseUrl(cfg.getUrl());
        String bucket = cfg.getStorageBucket();
        if (supabaseUrl.isEmpty() || bucket.isEmpty() || objectPath == null || objectPath.isBlank()) {
            return Optional.empty();
        }

        // Upload endpoint:
        //   PUT {SUPABASE_URL}/storage/v1/object/{bucket}/{objectPath}
        // Public URL:
        //   {SUPABASE_URL}/storage/v1/object/public/{bucket}/{objectPath}
        String encodedPath = encodePath(objectPath);
        String uploadUrl = supabaseUrl + "/storage/v1/object/" + urlEncode(bucket) + "/" + encodedPath;
        String publicUrl = buildPublicUrl(supabaseUrl, bucket, objectPath);

        for (int attempt = 1; attempt <= UPLOAD_MAX_ATTEMPTS; attempt++) {
            UploadAttemptResult result = tryUploadOnce(uploadUrl, contentType, bytes, cfg.getAnonKey(), attempt);
            if (result == UploadAttemptResult.SUCCESS) {
                return Optional.of(publicUrl);
            }
            if (result == UploadAttemptResult.NON_RETRYABLE) {
                return Optional.empty();
            }
            if (attempt < UPLOAD_MAX_ATTEMPTS) {
                sleepBackoffMs(attempt);
            }
        }
        SyncLogger.log("SupabaseStorage upload gave up after " + UPLOAD_MAX_ATTEMPTS + " attempts");
        return Optional.empty();
    }

    private enum UploadAttemptResult {
        SUCCESS,
        RETRYABLE,
        NON_RETRYABLE
    }

    private static UploadAttemptResult tryUploadOnce(
            String uploadUrl,
            String contentType,
            byte[] bytes,
            String anonKey,
            int attempt) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(uploadUrl))
                    .timeout(UPLOAD_REQUEST_TIMEOUT)
                    .header("Content-Type", contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType)
                    .header("apikey", anonKey)
                    .header("Authorization", "Bearer " + anonKey)
                    .header("x-upsert", "true")
                    .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes))
                    .build();

            HttpResponse<String> resp = sendWithTlsFallback(req);
            int code = resp.statusCode();
            if (code >= 200 && code < 300) {
                return UploadAttemptResult.SUCCESS;
            }

            SyncLogger.log("SupabaseStorage upload failed (attempt " + attempt + "): HTTP " + code + " body=" + safeBody(resp.body()));
            return isRetryableHttpStatus(code) ? UploadAttemptResult.RETRYABLE : UploadAttemptResult.NON_RETRYABLE;
        } catch (Exception e) {
            if (!isRetryableUploadException(e)) {
                SyncLogger.log("SupabaseStorage upload error", e);
                return UploadAttemptResult.NON_RETRYABLE;
            }
            SyncLogger.log("SupabaseStorage upload error (attempt " + attempt + ", will retry): " + e.getMessage());
            return UploadAttemptResult.RETRYABLE;
        }
    }

    private static HttpResponse<String> sendWithTlsFallback(HttpRequest req) throws IOException, InterruptedException {
        try {
            return HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (SSLHandshakeException e) {
            SyncLogger.log("SupabaseStorage TLS handshake failed on default client; retrying with TLSv1.2");
            return HTTP_TLS12.send(req, HttpResponse.BodyHandlers.ofString());
        }
    }

    private static boolean isRetryableHttpStatus(int code) {
        return code == 408 || code == 429 || code == 500 || code == 502 || code == 503 || code == 504;
    }

    private static boolean isRetryableUploadException(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof HttpConnectTimeoutException
                    || c instanceof ConnectException
                    || c instanceof java.net.http.HttpTimeoutException) {
                return true;
            }
            if (c instanceof IOException) {
                return true;
            }
        }
        return false;
    }

    private static void sleepBackoffMs(int attemptAfterFailure) {
        long base = 1500L * (1L << (attemptAfterFailure - 1));
        long capped = Math.min(base, 20_000L);
        try {
            Thread.sleep(capped);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private static void deleteObject(String objectPath) {
        Optional<SupabaseConfig> cfgOpt = SupabaseConfig.load();
        if (cfgOpt.isEmpty()) return;

        SupabaseConfig cfg = cfgOpt.get();
        String supabaseUrl = normalizeBaseUrl(cfg.getUrl());
        String bucket = cfg.getStorageBucket();
        if (supabaseUrl.isEmpty() || bucket.isEmpty() || objectPath == null || objectPath.isBlank()) {
            return;
        }

        String encodedPath = encodePath(objectPath);
        String deleteUrl = supabaseUrl + "/storage/v1/object/" + urlEncode(bucket) + "/" + encodedPath;

        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(deleteUrl))
                    .timeout(Duration.ofSeconds(15))
                    .header("apikey", cfg.getAnonKey())
                    .header("Authorization", "Bearer " + cfg.getAnonKey())
                    .DELETE()
                    .build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 300) {
                SyncLogger.log("SupabaseStorage delete failed: HTTP " + resp.statusCode() + " body=" + safeBody(resp.body()));
            }
        } catch (Exception e) {
            SyncLogger.log("SupabaseStorage delete error", e);
        }
    }

    private static String normalizeBaseUrl(String url) {
        if (url == null) return "";
        String trimmed = url.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static void flushPendingUploads() {
        if (pendingIdPdfUploads.isEmpty()) return;

        Optional<SupabaseConfig> cfgOpt = SupabaseConfig.load();
        if (cfgOpt.isEmpty()) return;
        SupabaseConfig cfg = cfgOpt.get();

        List<Integer> ids = new ArrayList<>(pendingIdPdfUploads);
        boolean changed = false;
        for (Integer id : ids) {
            if (id == null || id <= 0) {
                pendingIdPdfUploads.remove(id);
                changed = true;
                continue;
            }
            IdRecord r = DbHelper.getIdRecord(id);
            if (r == null) {
                pendingIdPdfUploads.remove(id);
                changed = true;
                continue;
            }
            Optional<String> url = uploadIdPdfAndGetPublicUrl(r);
            if (url.isPresent()) {
                pendingIdPdfUploads.remove(id);
                uploadedIdPdfRecords.add(id);
                changed = true;
            }
        }

        if (changed) {
            persistPendingQueueToDisk();
            persistUploadedMarkersToDisk();
        }
    }

    /**
     * On a fresh install/new PC, uploaded markers may be empty even with existing local records.
     * Queue those records so cloud PDFs are rebuilt automatically in the background.
     */
    private static void enqueueKnownRecordsIfNeeded() {
        Optional<SupabaseConfig> cfgOpt = SupabaseConfig.load();
        if (cfgOpt.isEmpty()) return;
        try {
            List<IdRecord> records = DbHelper.getAllIdRecords();
            boolean changed = false;
            for (IdRecord r : records) {
                if (r == null || r.id() <= 0) continue;
                if (uploadedIdPdfRecords.contains(r.id())) continue;
                if (pendingIdPdfUploads.add(r.id())) {
                    changed = true;
                }
            }
            if (changed) {
                persistPendingQueueToDisk();
            }
        } catch (Exception e) {
            SyncLogger.log("SupabaseStorage: failed to enqueue known records", e);
        }
    }

    private static void loadPendingQueueFromDisk() {
        try {
            if (!Files.isRegularFile(PENDING_QUEUE_PATH)) return;
            String json = Files.readString(PENDING_QUEUE_PATH);
            if (json == null || json.isBlank()) return;
            List<Integer> ids = GSON.fromJson(json, new TypeToken<List<Integer>>() {}.getType());
            if (ids == null || ids.isEmpty()) return;
            for (Integer id : ids) {
                if (id != null && id > 0) pendingIdPdfUploads.add(id);
            }
        } catch (Exception e) {
            SyncLogger.log("SupabaseStorage: failed to load pending queue", e);
        }
    }

    private static void persistPendingQueueToDiskAsync() {
        EXECUTOR.execute(SupabaseStorageService::persistPendingQueueToDisk);
    }

    private static void persistUploadedMarkersToDiskAsync() {
        EXECUTOR.execute(SupabaseStorageService::persistUploadedMarkersToDisk);
    }

    private static void persistPendingQueueToDisk() {
        try {
            Files.createDirectories(PENDING_QUEUE_PATH.getParent());
            List<Integer> ids = new ArrayList<>(pendingIdPdfUploads);
            Files.writeString(PENDING_QUEUE_PATH, GSON.toJson(ids), StandardCharsets.UTF_8);
        } catch (Exception e) {
            SyncLogger.log("SupabaseStorage: failed to persist pending queue", e);
        }
    }

    private static void loadUploadedMarkersFromDisk() {
        try {
            if (!Files.isRegularFile(UPLOADED_MARKER_PATH)) return;
            String json = Files.readString(UPLOADED_MARKER_PATH);
            if (json == null || json.isBlank()) return;
            List<Integer> ids = GSON.fromJson(json, new TypeToken<List<Integer>>() {}.getType());
            if (ids == null || ids.isEmpty()) return;
            for (Integer id : ids) {
                if (id != null && id > 0) uploadedIdPdfRecords.add(id);
            }
        } catch (Exception e) {
            SyncLogger.log("SupabaseStorage: failed to load uploaded markers", e);
        }
    }

    private static void persistUploadedMarkersToDisk() {
        try {
            Files.createDirectories(UPLOADED_MARKER_PATH.getParent());
            List<Integer> ids = new ArrayList<>(uploadedIdPdfRecords);
            Files.writeString(UPLOADED_MARKER_PATH, GSON.toJson(ids), StandardCharsets.UTF_8);
        } catch (Exception e) {
            SyncLogger.log("SupabaseStorage: failed to persist uploaded markers", e);
        }
    }

    private static String buildPublicUrl(String supabaseUrl, String bucket, String objectPath) {
        String encodedPath = encodePath(objectPath);
        return supabaseUrl + "/storage/v1/object/public/" + urlEncode(bucket) + "/" + encodedPath;
    }

    private static String encodePath(String path) {
        // Encode each segment but keep slashes.
        String[] parts = path.split("/");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append("/");
            sb.append(urlEncode(parts[i]));
        }
        return sb.toString();
    }

    private static String urlEncode(String s) {
        // URLEncoder is for query strings, but works fine for segments when we avoid '+'.
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String safeBody(String body) {
        if (body == null) return "";
        String b = body.replace("\r", " ").replace("\n", " ");
        return b.length() > 600 ? b.substring(0, 600) + "..." : b;
    }
}

