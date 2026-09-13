/*
 * ClientDefaults.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.client;

import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.dns.client.DnsResolver;
import org.bluezoo.gumdrop.tls.ClientTlsConfig;

/**
 * Process-wide outbound client defaults until {@code Runtime} (§C.4) replaces
 * the {@link org.bluezoo.gumdrop.Gumdrop} singleton for this role.
 *
 * <p>TLS: without explicit {@link ClientTlsConfig} material on the client or
 * here, connections are <strong>plaintext only</strong> — {@code secure(true)}
 * alone does not enable TLS.
 *
 * <p>DNS: {@link #dnsResolver(SelectorLoop, DnsResolver)} uses the per-client
 * override when set; otherwise {@link DnsResolver#forLoop} ({@code resolv.conf},
 * then Cloudflare → Quad9 → Google public fallbacks).
 */
public final class ClientDefaults {

    private static volatile ClientTlsConfig defaultTls;

    private ClientDefaults() {
    }

    /**
     * Sets TLS defaults inherited by clients that do not configure their own
     * {@link ClientTlsConfig} material. {@code null} clears the default.
     */
    public static void setDefaultTls(ClientTlsConfig defaultTls) {
        ClientDefaults.defaultTls = defaultTls;
    }

    public static ClientTlsConfig getDefaultTls() {
        return defaultTls;
    }

    /**
     * Merges per-client TLS settings with {@link #getDefaultTls()}. When neither
     * side has TLS material, returns an empty config (plaintext only).
     */
    public static ClientTlsConfig effectiveTls(ClientTlsConfig perClient) {
        return ClientTlsConfig.effective(perClient, defaultTls);
    }

    /**
     * Returns the DNS resolver for an outbound dial. Per-client override wins;
     * otherwise the loop-associated resolver from {@link DnsResolver#forLoop}.
     */
    public static DnsResolver dnsResolver(SelectorLoop loop, DnsResolver perClient) {
        if (perClient != null) {
            return perClient;
        }
        return DnsResolver.forLoop(loop);
    }

}
