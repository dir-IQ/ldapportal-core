// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.util;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * Validates URLs used in server-side requests to prevent SSRF attacks.
 * Blocks private IP ranges, link-local addresses, loopback, and non-HTTP schemes.
 */
public final class UrlValidator {

    private UrlValidator() {}

    /**
     * Validates that a URL is safe for server-side requests.
     *
     * @throws IllegalArgumentException if the URL targets a blocked destination
     */
    public static void requireSafeUrl(String url) {
        if (url == null || url.isBlank()) return;

        URI uri;
        try {
            uri = URI.create(url);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid URL: " + url);
        }

        String scheme = uri.getScheme();
        if (scheme == null || (!scheme.equals("https") && !scheme.equals("http"))) {
            throw new IllegalArgumentException("URL scheme must be http or https: " + scheme);
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("URL has no host");
        }

        // Block known dangerous hostnames
        String lower = host.toLowerCase();
        if (lower.equals("localhost") || lower.equals("metadata.google.internal")
                || lower.endsWith(".internal") || lower.endsWith(".local")) {
            throw new IllegalArgumentException("URL targets a blocked host: " + host);
        }

        // Resolve and check IP
        try {
            InetAddress addr = InetAddress.getByName(host);
            byte[] ip = addr.getAddress();

            if (addr.isLoopbackAddress()
                    || addr.isLinkLocalAddress()
                    || addr.isSiteLocalAddress()
                    || isMetadataAddress(ip)) {
                throw new IllegalArgumentException(
                        "URL resolves to a private/internal address: " + host + " → " + addr.getHostAddress());
            }
        } catch (UnknownHostException e) {
            // If DNS doesn't resolve, allow it — the actual HTTP call will fail
        }
    }

    private static boolean isMetadataAddress(byte[] ip) {
        if (ip.length != 4) return false;
        // 169.254.169.254 — cloud metadata endpoint
        return (ip[0] & 0xFF) == 169 && (ip[1] & 0xFF) == 254
                && (ip[2] & 0xFF) == 169 && (ip[3] & 0xFF) == 254;
    }
}
