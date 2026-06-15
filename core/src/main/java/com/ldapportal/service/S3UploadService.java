// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.ldapportal.entity.ApplicationSettings;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;

/**
 * Uploads files to S3-compatible object storage using AWS Signature V4.
 *
 * <p>Uses raw {@link HttpURLConnection} with manual signature computation,
 * consistent with the project's pattern of avoiding heavy client SDKs
 * (cf. SMTP via raw sockets in {@link ApprovalNotificationService}).</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class S3UploadService {

    private static final DateTimeFormatter AMZ_DATE_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");
    private static final DateTimeFormatter DATE_STAMP_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd");

    private final ApplicationSettingsService appSettingsService;
    private final EncryptionService encryptionService;

    /**
     * Uploads {@code data} to the configured S3 bucket under {@code objectKey}.
     *
     * @param objectKey   full S3 object key (e.g. "reports/2026-03/users-in-group.csv")
     * @param data        file content
     * @param contentType MIME type (e.g. "text/csv")
     * @throws IOException              if the upload fails
     * @throws IllegalStateException    if S3 is not configured
     */
    public void upload(String objectKey, byte[] data, String contentType) throws IOException {
        ApplicationSettings settings = appSettingsService.getEntity();
        validateS3Config(settings);

        String secretKey = encryptionService.decrypt(settings.getS3SecretKeyEncrypted());
        String accessKey = settings.getS3AccessKey();
        String region = settings.getS3Region() != null ? settings.getS3Region() : "us-east-1";
        String bucket = settings.getS3BucketName();
        String endpoint = settings.getS3EndpointUrl();
        com.ldapportal.util.UrlValidator.requireSafeUrl(endpoint);

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        String amzDate = now.format(AMZ_DATE_FMT);
        String dateStamp = now.format(DATE_STAMP_FMT);

        // Normalise object key (strip leading slash)
        String normKey = objectKey.startsWith("/") ? objectKey.substring(1) : objectKey;

        // Build URL: path-style addressing for compatibility with S3-compatible stores
        String url = buildUrl(endpoint, bucket, normKey);
        URI uri = URI.create(url);
        String host = uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "");

        // Hash the payload
        String payloadHash = sha256Hex(data);

        // Canonical request
        String canonicalUri = "/" + bucket + "/" + normKey;
        // For custom endpoints that already include the bucket, use just the key
        if (endpoint != null && !endpoint.contains("amazonaws.com")) {
            canonicalUri = "/" + normKey;
        }
        String canonicalQueryString = "";
        String canonicalHeaders =
                "content-type:" + contentType + "\n"
                + "host:" + host + "\n"
                + "x-amz-content-sha256:" + payloadHash + "\n"
                + "x-amz-date:" + amzDate + "\n";
        String signedHeaders = "content-type;host;x-amz-content-sha256;x-amz-date";
        String canonicalRequest = "PUT\n" + canonicalUri + "\n" + canonicalQueryString + "\n"
                + canonicalHeaders + "\n" + signedHeaders + "\n" + payloadHash;

        // String to sign
        String credentialScope = dateStamp + "/" + region + "/s3/aws4_request";
        String stringToSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n"
                + credentialScope + "\n" + sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));

        // Signing key
        byte[] signingKey = getSignatureKey(secretKey, dateStamp, region, "s3");
        String signature = hmacSha256Hex(signingKey, stringToSign);

        // Authorization header
        String authorization = "AWS4-HMAC-SHA256 Credential=" + accessKey + "/" + credentialScope
                + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature;

        // Execute request
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestMethod("PUT");
        conn.setDoOutput(true);
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(30_000);
        conn.setRequestProperty("Content-Type", contentType);
        conn.setRequestProperty("Host", host);
        conn.setRequestProperty("x-amz-content-sha256", payloadHash);
        conn.setRequestProperty("x-amz-date", amzDate);
        conn.setRequestProperty("Authorization", authorization);
        conn.setRequestProperty("Content-Length", String.valueOf(data.length));

        try (OutputStream os = conn.getOutputStream()) {
            os.write(data);
        }

        int responseCode = conn.getResponseCode();
        if (responseCode < 200 || responseCode >= 300) {
            String errorBody = "";
            try {
                errorBody = new String(conn.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            } catch (Exception ignored) { }
            throw new IOException("S3 upload failed with HTTP " + responseCode
                    + " for key '" + normKey + "': " + errorBody);
        }

        log.info("Uploaded report to S3: bucket={}, key={}, size={} bytes", bucket, normKey, data.length);
    }

    /**
     * Returns true if S3 storage is configured in application settings.
     */
    public boolean isConfigured() {
        ApplicationSettings settings = appSettingsService.getEntity();
        return settings.getS3EndpointUrl() != null && !settings.getS3EndpointUrl().isBlank()
                && settings.getS3BucketName() != null && !settings.getS3BucketName().isBlank()
                && settings.getS3AccessKey() != null && !settings.getS3AccessKey().isBlank()
                && settings.getS3SecretKeyEncrypted() != null;
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void validateS3Config(ApplicationSettings settings) {
        if (settings.getS3EndpointUrl() == null || settings.getS3EndpointUrl().isBlank()) {
            throw new IllegalStateException("S3 endpoint URL is not configured");
        }
        if (settings.getS3BucketName() == null || settings.getS3BucketName().isBlank()) {
            throw new IllegalStateException("S3 bucket name is not configured");
        }
        if (settings.getS3AccessKey() == null || settings.getS3AccessKey().isBlank()) {
            throw new IllegalStateException("S3 access key is not configured");
        }
        if (settings.getS3SecretKeyEncrypted() == null) {
            throw new IllegalStateException("S3 secret key is not configured");
        }
    }

    private String buildUrl(String endpoint, String bucket, String key) {
        String base = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        if (base.contains("amazonaws.com")) {
            // Path-style: https://s3.region.amazonaws.com/bucket/key
            return base + "/" + bucket + "/" + key;
        }
        // S3-compatible (MinIO etc): endpoint/key
        return base + "/" + key;
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(data));
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private static String sha256Hex(String data) {
        return sha256Hex(data.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] hmacSha256(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException("HmacSHA256 failed", e);
        }
    }

    private static String hmacSha256Hex(byte[] key, String data) {
        return HexFormat.of().formatHex(hmacSha256(key, data));
    }

    private static byte[] getSignatureKey(String secretKey, String dateStamp,
                                           String region, String service) {
        byte[] kDate = hmacSha256(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), dateStamp);
        byte[] kRegion = hmacSha256(kDate, region);
        byte[] kService = hmacSha256(kRegion, service);
        return hmacSha256(kService, "aws4_request");
    }
}
