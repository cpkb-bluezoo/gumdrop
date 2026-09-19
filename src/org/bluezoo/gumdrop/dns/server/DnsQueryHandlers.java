/*
 * DnsQueryHandlers.java
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

package org.bluezoo.gumdrop.dns.server;

import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.dns.DnsMessage;
import org.bluezoo.gumdrop.dns.DnsQueryCallback;

/**
 * Factory methods for {@link DnsQueryHandler} implementations.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class DnsQueryHandlers {

    private DnsQueryHandlers() {
    }

    /**
     * Returns the default handler: {@code NOERROR} with zero answer records.
     */
    public static DnsQueryHandler empty() {
        return EmptyDnsQueryHandler.INSTANCE;
    }

    /**
     * Adapts a synchronous resolver function for composition and tests.
     *
     * <p>When the function returns {@code null}, an empty {@code NOERROR}
     * response is sent (not upstream relay).
     */
    public static DnsQueryHandler fromFunction(final DnsResolveFunction function) {
        if (function == null) {
            throw new NullPointerException("function");
        }
        return new DnsQueryHandler() {
            @Override
            public void handleQuery(DnsMessage query, SelectorLoop loop,
                                    DnsQueryCallback callback) {
                DnsMessage response = function.resolve(query);
                if (response != null) {
                    callback.onResponse(response);
                } else {
                    EmptyDnsQueryHandler.INSTANCE.handleQuery(query, loop, callback);
                }
            }
        };
    }

    /**
     * Chains handlers: tries each in order until one produces a non-empty
     * answer or a non-{@code NOERROR} rcode.
     */
    public static DnsQueryHandler chain(final DnsQueryHandler first,
                                        final DnsQueryHandler second) {
        if (first == null || second == null) {
            throw new NullPointerException("handler");
        }
        return new ChainedDnsQueryHandler(first, second);
    }

    /**
     * Synchronous resolver hook for {@link #fromFunction}.
     */
    public interface DnsResolveFunction {
        DnsMessage resolve(DnsMessage query);
    }

    private static final class ChainedDnsQueryHandler implements DnsQueryHandler {

        private final DnsQueryHandler first;
        private final DnsQueryHandler second;

        ChainedDnsQueryHandler(DnsQueryHandler first, DnsQueryHandler second) {
            this.first = first;
            this.second = second;
        }

        @Override
        public void start(org.bluezoo.gumdrop.Gumdrop gumdrop) {
            first.start(gumdrop);
            second.start(gumdrop);
        }

        @Override
        public void start() {
            first.start();
            second.start();
        }

        @Override
        public void stop() {
            second.stop();
            first.stop();
        }

        @Override
        public boolean handleNonQueryOpcode(final DnsMessage query,
                                            final SelectorLoop loop,
                                            final DnsQueryCallback callback) {
            if (first.handleNonQueryOpcode(query, loop, callback)) {
                return true;
            }
            return second.handleNonQueryOpcode(query, loop, callback);
        }

        @Override
        public void handleQuery(final DnsMessage query, final SelectorLoop loop,
                                final DnsQueryCallback callback) {
            first.handleQuery(query, loop, new DnsQueryCallback() {
                @Override
                public void onResponse(DnsMessage response) {
                    if (shouldDelegate(response)) {
                        second.handleQuery(query, loop, callback);
                    } else {
                        callback.onResponse(response);
                    }
                }

                @Override
                public void onError(String error) {
                    second.handleQuery(query, loop, callback);
                }
            });
        }

        private static boolean shouldDelegate(DnsMessage response) {
            if (response == null) {
                return true;
            }
            if (response.getRcode() != DnsMessage.RCODE_NOERROR) {
                return false;
            }
            return response.getAnswers().isEmpty();
        }
    }

}
