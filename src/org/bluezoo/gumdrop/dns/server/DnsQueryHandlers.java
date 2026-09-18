/*
 * DnsQueryHandlers.java
 * Copyright (C) 2026 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 */

package org.bluezoo.gumdrop.dns.server;

import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.dns.DnsMessage;
import org.bluezoo.gumdrop.dns.DnsQueryCallback;

/**
 * Factory methods for {@link DnsQueryHandler} implementations.
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
