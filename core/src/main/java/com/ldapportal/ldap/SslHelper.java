// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap;

import com.unboundid.util.ssl.SSLUtil;
import com.unboundid.util.ssl.TrustAllTrustManager;

import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.Collection;

/**
 * Shared SSL/TLS helper for building {@link SSLUtil} instances.
 *
 * <p>Consolidates the three trust strategies used across LDAP connections:
 * <ol>
 *   <li>Trust-all (development / internal networks)</li>
 *   <li>Custom PEM certificate(s) — loads the full chain</li>
 *   <li>JVM default trust store</li>
 * </ol>
 */
public final class SslHelper {

    private SslHelper() {}

    /**
     * Builds an {@link SSLUtil} according to the given trust parameters.
     *
     * @param trustAllCerts if {@code true}, all certificates are trusted
     * @param trustedCertPem optional PEM-encoded certificate(s); may be {@code null} or blank
     * @return a configured {@link SSLUtil}
     */
    public static SSLUtil buildSslUtil(boolean trustAllCerts, String trustedCertPem) throws Exception {
        if (trustAllCerts) {
            return new SSLUtil(new TrustAllTrustManager());
        }
        if (trustedCertPem != null && !trustedCertPem.isBlank()) {
            return new SSLUtil(buildPemTrustManagers(trustedCertPem));
        }
        return new SSLUtil();
    }

    /**
     * Parses one or more PEM-encoded X.509 certificates and returns
     * {@link TrustManager}s that trust exactly those certificates.
     */
    public static TrustManager[] buildPemTrustManagers(String pem) throws Exception {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        Collection<? extends Certificate> certs = cf.generateCertificates(
                new ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8)));

        KeyStore trustStore = KeyStore.getInstance("JKS");
        trustStore.load(null, null);
        int index = 0;
        for (Certificate cert : certs) {
            trustStore.setCertificateEntry("trusted-ca-" + index++, cert);
        }

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);
        return tmf.getTrustManagers();
    }
}
