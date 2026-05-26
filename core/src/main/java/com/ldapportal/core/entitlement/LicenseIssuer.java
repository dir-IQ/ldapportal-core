// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.entitlement;

import io.jsonwebtoken.Jwts;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Command-line utility for signing a license JWT with the production
 * Ed25519 private key. Run by a release engineer (or whoever holds the
 * signing key) when a customer needs a license.
 *
 * <p>Lives in main scope so it ships with the distribution JAR — the
 * tool is harmless without the private key file, and shipping it keeps
 * operators from having to maintain a separate signing toolchain.</p>
 *
 * <h2>Invocation</h2>
 *
 * <p>From a fat JAR using Spring Boot's PropertiesLauncher:</p>
 * <pre>
 *   java -cp ldapportal-commercial-&lt;ver&gt;.jar \
 *        -Dloader.main=com.ldapportal.core.entitlement.LicenseIssuer \
 *        org.springframework.boot.loader.launch.PropertiesLauncher \
 *        --private-key /path/to/private.pem \
 *        --customer-id &lt;uuid&gt; \
 *        --edition BUSINESS \
 *        --addons GOVERNANCE,HR_SYNC \
 *        --limits DIRECTORIES=10,ADMIN_ACCOUNTS=50 \
 *        --expires 2027-04-22 \
 *        --output customer-license.jwt
 * </pre>
 *
 * <p>From a Maven checkout (no JAR needed):</p>
 * <pre>
 *   mvn -pl core exec:java \
 *       -Dexec.mainClass=com.ldapportal.core.entitlement.LicenseIssuer \
 *       -Dexec.args="--private-key /path/to/private.pem --edition BUSINESS ..."
 * </pre>
 *
 * <p>See {@code docs/licensing-runbook.md} for the full operator
 * workflow.</p>
 */
public class LicenseIssuer {

    public static void main(String[] rawArgs) throws Exception {
        Map<String, String> args = parseArgs(rawArgs);
        if (args.containsKey("help") || args.isEmpty()) {
            printHelp();
            System.exit(0);
        }

        Path privateKeyPath = Path.of(require(args, "private-key"));
        String editionName = require(args, "edition");
        String expiresStr = require(args, "expires");

        UUID customerId = args.containsKey("customer-id")
                ? UUID.fromString(args.get("customer-id"))
                : UUID.randomUUID();

        Edition edition = Edition.valueOf(editionName);
        Instant expiresAt = parseExpiresAt(expiresStr);
        Instant issuedAt = Instant.now();

        List<String> addOns = parseCsvList(args.get("addons"));
        Map<String, Long> limits = parseLimitsList(args.get("limits"));

        // Ensure we aren't silently accepting typos. Fail-fast on unknown
        // names rather than emitting a JWT the verifier will tolerate
        // (verifier logs a warning but skips unknown values).
        for (String ao : addOns) {
            Entitlement.valueOf(ao);
        }
        for (String l : limits.keySet()) {
            LimitType.valueOf(l);
        }

        PrivateKey signingKey = loadPrivateKey(privateKeyPath);
        String jwt = signLicense(signingKey, customerId, edition,
                addOns, limits, issuedAt, expiresAt);

        String outputPath = args.get("output");
        if (outputPath != null) {
            Files.writeString(Path.of(outputPath), jwt);
            System.err.println("Wrote " + outputPath);
        } else {
            System.out.println(jwt);
        }

        // Summary on stderr so stdout-only consumers (if output is unset)
        // get just the JWT.
        System.err.println();
        System.err.println("Signed license summary:");
        System.err.println("  customer-id : " + customerId);
        System.err.println("  edition     : " + edition);
        System.err.println("  addOns      : " + (addOns.isEmpty() ? "(none)" : addOns));
        System.err.println("  limits      : " + (limits.isEmpty() ? "(unlimited)" : limits));
        System.err.println("  issued-at   : " + issuedAt);
        System.err.println("  expires-at  : " + expiresAt);
    }

    // ── argument parsing ─────────────────────────────────────────────────────

    private static Map<String, String> parseArgs(String[] raw) {
        Map<String, String> out = new LinkedHashMap<>();
        int i = 0;
        while (i < raw.length) {
            String a = raw[i];
            if (!a.startsWith("--")) {
                throw new IllegalArgumentException("Unexpected positional argument: " + a);
            }
            String key = a.substring(2);
            if (key.contains("=")) {
                int eq = key.indexOf('=');
                out.put(key.substring(0, eq), key.substring(eq + 1));
                i++;
            } else if (i + 1 < raw.length && !raw[i + 1].startsWith("--")) {
                out.put(key, raw[i + 1]);
                i += 2;
            } else {
                // Flag with no value — only valid for --help.
                out.put(key, "true");
                i++;
            }
        }
        return out;
    }

    private static String require(Map<String, String> args, String name) {
        String v = args.get(name);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException(
                    "Missing required argument: --" + name + " (run with --help for usage)");
        }
        return v;
    }

    private static Instant parseExpiresAt(String s) {
        // Accept either ISO-8601 date (YYYY-MM-DD, end of day UTC) or full
        // ISO-8601 instant. Dates are the common case — nobody cares about
        // license expiry at hour granularity.
        try {
            LocalDate d = LocalDate.parse(s);
            return d.atTime(23, 59, 59).toInstant(ZoneOffset.UTC);
        } catch (Exception ignored) {
            return Instant.parse(s);
        }
    }

    private static List<String> parseCsvList(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String s : raw.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private static Map<String, Long> parseLimitsList(String raw) {
        if (raw == null || raw.isBlank()) return Map.of();
        Map<String, Long> out = new LinkedHashMap<>();
        for (String pair : raw.split(",")) {
            String t = pair.trim();
            if (t.isEmpty()) continue;
            int eq = t.indexOf('=');
            if (eq < 0) {
                throw new IllegalArgumentException(
                        "Bad --limits entry (expected NAME=NUMBER): " + pair);
            }
            out.put(t.substring(0, eq).trim(), Long.parseLong(t.substring(eq + 1).trim()));
        }
        return out;
    }

    // ── key loading ──────────────────────────────────────────────────────────

    private static PrivateKey loadPrivateKey(Path path) throws IOException {
        String pem = Files.readString(path, StandardCharsets.UTF_8);
        String base64 = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(base64);
        try {
            return KeyFactory.getInstance("Ed25519")
                    .generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to parse Ed25519 private key from " + path
                    + " — expected PKCS#8 PEM format (generated by "
                    + "`openssl genpkey -algorithm Ed25519`).", e);
        }
    }

    // ── JWT construction ─────────────────────────────────────────────────────

    private static String signLicense(
            PrivateKey key,
            UUID customerId,
            Edition edition,
            List<String> addOns,
            Map<String, Long> limits,
            Instant issuedAt,
            Instant expiresAt) {

        Map<String, Object> claims = new HashMap<>();
        claims.put("edition", edition.name());
        if (!addOns.isEmpty()) claims.put("addOns", addOns);
        if (!limits.isEmpty()) claims.put("limits", limits);

        return Jwts.builder()
                .issuer("ldapadmin")
                .audience().add("ldapadmin").and()
                .subject(customerId.toString())
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
                .claims(claims)
                .signWith(key, Jwts.SIG.EdDSA)
                .compact();
    }

    // ── help ─────────────────────────────────────────────────────────────────

    private static void printHelp() {
        String help = """
                LicenseIssuer — mint a signed ldapadmin license JWT.

                Required:
                  --private-key <path>    Ed25519 PKCS#8 PEM private key file
                  --edition <NAME>        COMMUNITY | TEAM | BUSINESS | ENTERPRISE
                  --expires <YYYY-MM-DD>  Expiration date (UTC end-of-day)
                                          or a full ISO-8601 instant

                Optional:
                  --customer-id <uuid>    Customer UUID; auto-generated if omitted
                  --addons <CSV>          Extra entitlements, e.g.
                                          GOVERNANCE,HR_SYNC
                  --limits <CSV>          Resource limits, e.g.
                                          DIRECTORIES=10,ADMIN_ACCOUNTS=50
                  --output <path>         Write JWT to file (default: stdout)

                Examples:
                  # ENTERPRISE license valid one year, write to stdout
                  LicenseIssuer --private-key ./priv.pem \\
                                --edition ENTERPRISE --expires 2027-04-22

                  # BUSINESS with add-ons + limits, write to file
                  LicenseIssuer --private-key ./priv.pem \\
                                --edition BUSINESS \\
                                --addons GOVERNANCE,HR_SYNC \\
                                --limits DIRECTORIES=25,ADMIN_ACCOUNTS=100 \\
                                --expires 2027-04-22 \\
                                --output customer-abc.jwt

                See docs/licensing-runbook.md for the full operator workflow.
                """;
        System.out.println(help);
    }
}
