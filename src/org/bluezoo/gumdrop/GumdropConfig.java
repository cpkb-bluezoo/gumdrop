/*
 * GumdropConfig.java
 * Copyright (C) 2026 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 *
 * gumdrop is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * gumdrop is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with gumdrop.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.bluezoo.gumdrop;

import org.bluezoo.gumdrop.crypto.CryptoServices;

/**
 * Configuration for {@link Gumdrop#boot(GumdropConfig)}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see Gumdrop#boot(GumdropConfig)
 */
public final class GumdropConfig {

    private int workerThreads =
            Integer.getInteger("gumdrop.workers",
                    Runtime.getRuntime().availableProcessors() * 2);
    private long drainTimeoutMs =
            Long.getLong("gumdrop.drainTimeoutMs", 25_000L);
    private String fipsProvider = System.getProperty("gumdrop.fipsProvider");
    private String fipsSecureRandomAlgorithm = System.getProperty("gumdrop.fipsSecureRandom");

    private GumdropConfig() {
    }

    /**
     * Creates a config with defaults: worker thread count from the
     * {@code gumdrop.workers} system property (falling back to
     * {@code availableProcessors() * 2}), and graceful-drain timeout from
     * the {@code gumdrop.drainTimeoutMs} system property (falling back to
     * 25 seconds). If {@code GUMDROP_DRAIN_TIMEOUT_MS} is set in the
     * environment, it overrides the drain timeout (same as the former
     * {@code Gumdrop.main} launcher). FIPS mode is off unless the
     * {@code gumdrop.fipsProvider} system property names a provider, with
     * {@code gumdrop.fipsSecureRandom} optionally naming its random source
     * algorithm (see {@link #fipsProvider}).
     */
    public static GumdropConfig create() {
        GumdropConfig config = new GumdropConfig();
        String drainEnv = System.getenv("GUMDROP_DRAIN_TIMEOUT_MS");
        if (drainEnv != null && !drainEnv.isEmpty()) {
            try {
                config.drainTimeoutMs(Long.parseLong(drainEnv.trim()));
            } catch (NumberFormatException e) {
                // leave property/default; ContainerMain and composed mains may log
            }
        }
        return config;
    }

    /**
     * Sets the number of worker {@link SelectorLoop} threads.
     */
    public GumdropConfig workerThreads(int workerThreads) {
        if (workerThreads < 1) {
            throw new IllegalArgumentException("workerThreads must be at least 1");
        }
        this.workerThreads = workerThreads;
        return this;
    }

    /**
     * Sets the graceful-drain timeout in milliseconds. On shutdown, the
     * server stops accepting new connections and waits up to this long for
     * in-flight connections to finish before force-closing. 0 disables
     * draining (immediate force-close on shutdown).
     */
    public GumdropConfig drainTimeoutMs(long drainTimeoutMs) {
        this.drainTimeoutMs = drainTimeoutMs;
        return this;
    }

    /**
     * Turns FIPS mode on, naming the installed JCA provider that every
     * cipher, digest, signature, key exchange, key derivation and random
     * value of the runtime's TLS, DTLS and QUIC connections must come from.
     * Gumdrop requests each service from that provider and never falls back
     * to another; {@link Gumdrop#boot(GumdropConfig)} fails if the provider
     * is not installed or cannot supply a required service. Which provider
     * is validated, and in which mode, is the operator's responsibility:
     * Gumdrop does not detect it.
     *
     * @param providerName the provider's name as registered with
     *        {@link java.security.Security}, or null to turn the mode off
     */
    public GumdropConfig fipsProvider(String providerName) {
        this.fipsProvider = providerName;
        return this;
    }

    /**
     * Names the FIPS provider's random source algorithm, since providers
     * do not agree on one ({@code "DRBG"} for the JDK's own, for example).
     * Unset, the provider's first registered {@code SecureRandom} service
     * is used. Only meaningful with {@link #fipsProvider}.
     *
     * @param algorithm the algorithm name, or null for the provider's first
     */
    public GumdropConfig fipsSecureRandomAlgorithm(String algorithm) {
        this.fipsSecureRandomAlgorithm = algorithm;
        return this;
    }

    /**
     * Returns whether a runtime booted from this configuration runs in
     * FIPS mode, that is, whether {@link #fipsProvider} names a provider.
     *
     * @return true if FIPS mode is on
     */
    public boolean isFipsMode() {
        return fipsProvider != null;
    }

    /**
     * Resolves the cryptographic services a runtime booted from this
     * configuration uses: pinned to the FIPS provider when the mode is on,
     * the JVM's provider list otherwise. {@link Gumdrop#boot(GumdropConfig)}
     * calls this; so may code that loads TLS material before the runtime
     * exists, such as a configuration file loader, so that the material is
     * read with the same services the runtime will use.
     *
     * @return the services
     * @throws IllegalStateException if the FIPS provider is not installed
     *         or cannot supply a required service
     */
    public CryptoServices createCryptoServices() {
        if (fipsProvider == null) {
            return new CryptoServices();
        }
        return CryptoServices.pinned(fipsProvider, fipsSecureRandomAlgorithm);
    }

    int getWorkerThreads() {
        return workerThreads;
    }

    long getDrainTimeoutMs() {
        return drainTimeoutMs;
    }

}
