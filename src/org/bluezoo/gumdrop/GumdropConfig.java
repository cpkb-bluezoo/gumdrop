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

/**
 * Configuration for {@link Gumdrop#start(GumdropConfig)}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see Gumdrop#start(GumdropConfig)
 */
public final class GumdropConfig {

    private int workerThreads =
            Integer.getInteger("gumdrop.workers",
                    Runtime.getRuntime().availableProcessors() * 2);
    private long drainTimeoutMs =
            Long.getLong("gumdrop.drainTimeoutMs", 25_000L);

    private GumdropConfig() {
    }

    /**
     * Creates a config with defaults: worker thread count from the
     * {@code gumdrop.workers} system property (falling back to
     * {@code availableProcessors() * 2}), and graceful-drain timeout from
     * the {@code gumdrop.drainTimeoutMs} system property (falling back to
     * 25 seconds). If {@code GUMDROP_DRAIN_TIMEOUT_MS} is set in the
     * environment, it overrides the drain timeout (same as the former
     * {@code Gumdrop.main} launcher).
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

    int getWorkerThreads() {
        return workerThreads;
    }

    long getDrainTimeoutMs() {
        return drainTimeoutMs;
    }

}
