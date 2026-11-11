/*
 * ClusterDiscovery.java
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

package org.bluezoo.gumdrop.redis.client;

import java.io.IOException;
import java.text.MessageFormat;
import java.util.List;

import org.bluezoo.gumdrop.redis.codec.RespValue;

/**
 * Loads a cluster's topology: asks each seed in turn for {@code CLUSTER SLOTS}
 * until one answers. The connection that answered is handed back open: a
 * client-only process would otherwise have no connection at all for a moment,
 * and Gumdrop stops a runtime that has none.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class ClusterDiscovery {

    /** Receives the outcome of a discovery. */
    interface Callback {
        /**
         * The topology was loaded.
         *
         * @param map the slot map
         * @param seedConnection the connection that answered, still open: the
         *                       caller closes it once it has connections of its own
         */
        void discovered(SlotMap map, RedisClientProtocolHandler seedConnection);

        void failed(Exception cause);
    }

    private ClusterDiscovery() {
    }

    /**
     * Asks the seeds in order, using the first usable answer.
     *
     * @param seeds the nodes to ask
     * @param connector opens the connections
     * @param callback told the outcome, exactly once
     */
    static void discover(List<ClusterNode> seeds, NodeConnector connector, Callback callback) {
        new Run(seeds, connector, callback).next(null);
    }

    /** One discovery: the seeds still to try and where to report. */
    private static final class Run {

        private final List<ClusterNode> seeds;
        private final NodeConnector connector;
        private final Callback callback;
        private int index;
        private Exception lastCause;
        private boolean done;

        Run(List<ClusterNode> seeds, NodeConnector connector, Callback callback) {
            this.seeds = seeds;
            this.connector = connector;
            this.callback = callback;
        }

        void next(Exception cause) {
            if (done) {
                return;
            }
            if (cause != null) {
                lastCause = cause;
            }
            if (index >= seeds.size()) {
                done = true;
                String reason = lastCause != null ? String.valueOf(lastCause.getMessage()) : "";
                callback.failed(new IOException(MessageFormat.format(
                        RedisClientProtocolHandler.L10N.getString("err.cluster_no_seeds"), reason),
                        lastCause));
                return;
            }
            ClusterNode seed = seeds.get(index++);
            connector.connect(seed, new Attempt(this, seed));
        }

        void succeed(SlotMap map, RedisClientProtocolHandler seedConnection) {
            if (done) {
                return;
            }
            done = true;
            callback.discovered(map, seedConnection);
        }
    }

    /** The question put to one seed. */
    private static final class Attempt implements NodeListener {

        private final Run run;
        private final ClusterNode seed;
        private RedisClientProtocolHandler connection;
        private boolean over;

        Attempt(Run run, ClusterNode seed) {
            this.run = run;
            this.seed = seed;
        }

        @Override
        public void ready(RedisClientProtocolHandler c) {
            if (over) {
                c.close();
                return;
            }
            connection = c;
            c.command(new ArrayResultHandler() {
                @Override
                public void handleResult(java.util.List<RespValue> slots, RedisSession s) {
                    answered(slots);
                }

                @Override
                public void handleNull(RedisSession s) {
                    giveUp(new IOException("CLUSTER SLOTS returned nothing"));
                }

                @Override
                public void handleError(String error, RedisSession s) {
                    giveUp(new IOException(error));
                }
            }, "CLUSTER", "SLOTS");
        }

        @Override
        public void failed(Exception cause) {
            giveUp(cause);
        }

        @Override
        public void closed() {
            giveUp(new IOException(RedisClientProtocolHandler.L10N.getString("err.not_connected")));
        }

        private void answered(java.util.List<RespValue> slots) {
            if (over) {
                return;
            }
            SlotMap map;
            try {
                map = SlotMap.parse(RespValue.array(slots), seed.getHost());
                if (map.nodes().isEmpty()) {
                    throw new IllegalArgumentException("CLUSTER SLOTS returned no slots");
                }
            } catch (IllegalArgumentException e) {
                giveUp(e);
                return;
            }
            over = true;
            run.succeed(map, connection);
        }

        private void giveUp(Exception cause) {
            if (over) {
                return;
            }
            over = true;
            if (connection != null) {
                connection.close();
            }
            run.next(cause);
        }
    }
}
