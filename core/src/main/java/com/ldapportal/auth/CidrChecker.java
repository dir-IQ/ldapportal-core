// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.auth;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Parses a newline-separated list of CIDR blocks once and answers
 * {@link #contains(String)} queries against it. Supports both IPv4 and IPv6
 * ({@code 10.0.0.0/8}, {@code 2001:db8::/32}, {@code 127.0.0.1/32}, etc.).
 *
 * <p>Empty / null input yields a checker that never matches — callers are
 * expected to treat that as "feature disabled".</p>
 */
public final class CidrChecker {

    private final List<Cidr> ranges;

    private CidrChecker(List<Cidr> ranges) {
        this.ranges = ranges;
    }

    public static CidrChecker parse(String newlineSeparated) {
        if (newlineSeparated == null || newlineSeparated.isBlank()) {
            return new CidrChecker(Collections.emptyList());
        }
        List<Cidr> parsed = new ArrayList<>();
        for (String raw : newlineSeparated.split("\\R")) {
            String trimmed = raw.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            try {
                parsed.add(Cidr.of(trimmed));
            } catch (Exception e) {
                // Skip malformed entries rather than fail setup. The admin UI
                // can lint the value separately; a bad line shouldn't wedge
                // the whole feature.
            }
        }
        return new CidrChecker(parsed);
    }

    /** Returns true when the given IP literal falls inside any configured range. */
    public boolean contains(String ip) {
        if (ranges.isEmpty() || ip == null || ip.isBlank()) return false;
        byte[] addr;
        try {
            addr = InetAddress.getByName(ip).getAddress();
        } catch (UnknownHostException e) {
            return false;
        }
        for (Cidr c : ranges) {
            if (c.contains(addr)) return true;
        }
        return false;
    }

    public boolean isEmpty() {
        return ranges.isEmpty();
    }

    // ── Internal ────────────────────────────────────────────────────────────

    private record Cidr(byte[] network, int prefixBits) {
        static Cidr of(String literal) throws UnknownHostException {
            int slash = literal.indexOf('/');
            String addressPart = slash < 0 ? literal : literal.substring(0, slash);
            byte[] address = InetAddress.getByName(addressPart).getAddress();
            int maxBits = address.length * 8;
            int prefix = slash < 0 ? maxBits : Integer.parseInt(literal.substring(slash + 1));
            if (prefix < 0 || prefix > maxBits) {
                throw new IllegalArgumentException("Invalid CIDR prefix in: " + literal);
            }
            // Zero the host bits of the network address so /24-style input
            // like "10.0.0.7/24" still works.
            byte[] masked = maskAddress(address, prefix);
            return new Cidr(masked, prefix);
        }

        boolean contains(byte[] ip) {
            // Address families must match (v4 vs v6).
            if (ip.length != network.length) return false;
            byte[] masked = maskAddress(ip, prefixBits);
            for (int i = 0; i < network.length; i++) {
                if (masked[i] != network[i]) return false;
            }
            return true;
        }

        private static byte[] maskAddress(byte[] addr, int prefixBits) {
            byte[] out = addr.clone();
            int fullBytes = prefixBits / 8;
            int remainingBits = prefixBits % 8;
            for (int i = fullBytes + (remainingBits > 0 ? 1 : 0); i < out.length; i++) {
                out[i] = 0;
            }
            if (remainingBits > 0 && fullBytes < out.length) {
                int mask = (0xFF << (8 - remainingBits)) & 0xFF;
                out[fullBytes] = (byte) (out[fullBytes] & mask);
            }
            return out;
        }
    }
}
