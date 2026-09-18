/*
 * HstsPolicy.java
 * Copyright (C) 2026 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 */

package org.bluezoo.gumdrop.http.server;

/**
 * RFC 6797 HTTP Strict Transport Security (HSTS) policy for HTTPS listeners.
 */
public final class HstsPolicy {

    /** Default {@code max-age} when HSTS is enabled (one year). */
    public static final long DEFAULT_MAX_AGE_SECONDS = 31_536_000L;

    private static final HstsPolicy DISABLED =
            new HstsPolicy(false, 0, false, false);

    private final boolean enabled;
    private final long maxAgeSeconds;
    private final boolean includeSubDomains;
    private final boolean preload;

    private HstsPolicy(boolean enabled, long maxAgeSeconds,
                       boolean includeSubDomains, boolean preload) {
        this.enabled = enabled;
        this.maxAgeSeconds = maxAgeSeconds;
        this.includeSubDomains = includeSubDomains;
        this.preload = preload;
    }

    /**
     * Returns a disabled policy (no {@code Strict-Transport-Security} header).
     */
    public static HstsPolicy disabled() {
        return DISABLED;
    }

    /**
     * Returns an enabled policy with the given {@code max-age}.
     */
    public static HstsPolicy enabled(long maxAgeSeconds) {
        if (maxAgeSeconds < 0) {
            throw new IllegalArgumentException("max-age must be non-negative");
        }
        return new HstsPolicy(true, maxAgeSeconds, false, false);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public long getMaxAgeSeconds() {
        return maxAgeSeconds;
    }

    public boolean isIncludeSubDomains() {
        return includeSubDomains;
    }

    public boolean isPreload() {
        return preload;
    }

    public HstsPolicy includeSubDomains(boolean includeSubDomains) {
        if (!enabled) {
            return this;
        }
        return new HstsPolicy(true, maxAgeSeconds, includeSubDomains, preload);
    }

    public HstsPolicy preload(boolean preload) {
        if (!enabled) {
            return this;
        }
        return new HstsPolicy(true, maxAgeSeconds, includeSubDomains, preload);
    }

    /**
     * Returns the RFC 6797 header field value, or {@code null} when disabled.
     */
    public String headerValue() {
        if (!enabled) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("max-age=").append(maxAgeSeconds);
        if (includeSubDomains) {
            sb.append("; includeSubDomains");
        }
        if (preload) {
            sb.append("; preload");
        }
        return sb.toString();
    }
}
