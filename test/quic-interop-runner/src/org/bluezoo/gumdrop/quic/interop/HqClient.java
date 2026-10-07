/*
 * HqClient.java
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

package org.bluezoo.gumdrop.quic.interop;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.quic.QuicConnection;
import org.bluezoo.gumdrop.quic.QuicEngine;
import org.bluezoo.gumdrop.quic.QuicTransportFactory;
import org.bluezoo.gumdrop.quic.SessionTicketCache;

/**
 * Fetches a batch of files over one {@code hq-interop} QUIC connection,
 * one bidirectional stream per file, all requested as soon as the
 * connection (or, for 0-RTT, its early-data keys) is ready. Streams
 * beyond the peer's stream limit wait inside the connection for
 * MAX_STREAMS credit, so a batch of thousands needs no pacing here.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class HqClient {

    private static final Logger LOGGER = Logger.getLogger(HqClient.class.getName());

    /** How long to wait for the NEW_SESSION_TICKET a later connection will resume with. */
    private static final long TICKET_TIMEOUT_MILLIS = 5000;

    /**
     * How long a handshake may take before the attempt is abandoned. The
     * transport reports a failed attempt only to a first-stream handler,
     * not to a connection handler, so an unreachable server would
     * otherwise be noticed only by the overall download timeout.
     */
    private static final long CONNECT_TIMEOUT_MILLIS = 30_000;

    private final SelectorLoop loop;
    private final QuicTransportFactory factory;
    private final Path downloads;

    HqClient(SelectorLoop loop, QuicTransportFactory factory, Path downloads) {
        this.loop = loop;
        this.factory = factory;
        this.downloads = downloads;
    }

    /**
     * Opens one connection to the host of the first URL and downloads
     * every URL over it, then closes the connection.
     *
     * @param awaitSessionTicket keep the connection open until the server
     *        has issued a session ticket, so that the next call can resume
     * @param zeroRtt send the requests in 0-RTT packets if a cached session
     *        ticket allows it
     * @return true if every file was received completely
     */
    boolean download(List<RequestUrl> urls, boolean awaitSessionTicket, boolean zeroRtt,
            long timeoutMillis) throws IOException, InterruptedException {
        if (urls.isEmpty()) {
            return true;
        }
        RequestUrl first = urls.get(0);
        InetAddress address = InetAddress.getByName(first.host);
        final DownloadTracker tracker = new DownloadTracker(urls.size());
        final CountDownLatch ticket = new CountDownLatch(1);
        if (awaitSessionTicket) {
            SessionTicketCache.putObserver = new Runnable() {
                @Override
                public void run() {
                    ticket.countDown();
                }
            };
        }
        final RequestIssuer issuer = new RequestIssuer(urls, tracker);
        QuicEngine.ConnectionAcceptedHandler accepted = new QuicEngine.ConnectionAcceptedHandler() {
            @Override
            public void connectionAccepted(QuicConnection connection) {
                SecurityInfo security = connection.getSecurityInfo();
                LOGGER.info("connected to " + connection.getRemoteAddress()
                        + " (QUIC " + connection.getVersion()
                        + ", " + security.getCipherSuite()
                        + ", resumed=" + security.isSessionResumed()
                        + ", earlyDataAccepted=" + security.isEarlyDataAccepted() + ")");
                issuer.issue(connection);
            }
        };
        LOGGER.info("connecting to " + first.host + " [" + address.getHostAddress() + "]:" + first.port
                + " for " + urls.size() + " file(s)" + (zeroRtt ? " with 0-RTT" : ""));
        QuicEngine engine;
        if (zeroRtt) {
            engine = factory.connect(address, first.port, accepted, new QuicEngine.EarlyDataHandler() {
                @Override
                public void earlyDataReady(QuicConnection connection) {
                    LOGGER.info("0-RTT keys ready, sending requests early");
                    issuer.issue(connection);
                }
            }, loop, first.host);
        } else {
            engine = factory.connect(address, first.port, accepted, loop, first.host);
        }
        if (!issuer.awaitIssued(CONNECT_TIMEOUT_MILLIS)) {
            LOGGER.warning("no connection to " + first.host + ":" + first.port + " within "
                    + CONNECT_TIMEOUT_MILLIS + " ms");
            SessionTicketCache.putObserver = null;
            close(engine);
            return false;
        }
        boolean complete = tracker.await(timeoutMillis);
        if (!complete) {
            LOGGER.warning("downloads did not finish within " + timeoutMillis + " ms");
        } else if (tracker.failures() > 0) {
            LOGGER.warning(tracker.failures() + " download(s) failed");
        }
        if (awaitSessionTicket) {
            if (complete && !ticket.await(TICKET_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                LOGGER.warning("no session ticket arrived within " + TICKET_TIMEOUT_MILLIS + " ms");
            }
            SessionTicketCache.putObserver = null;
        }
        close(engine);
        return complete && tracker.failures() == 0;
    }

    /**
     * Closes the connection on its own loop and waits for the
     * CONNECTION_CLOSE to go out.
     */
    private void close(final QuicEngine engine) throws InterruptedException {
        final CountDownLatch closed = new CountDownLatch(1);
        loop.invokeLater(new Runnable() {
            @Override
            public void run() {
                try {
                    engine.close();
                } finally {
                    closed.countDown();
                }
            }
        });
        closed.await(5, TimeUnit.SECONDS);
    }

    /**
     * Opens the request streams exactly once per connection, whichever of
     * the early-data and handshake-complete callbacks runs first.
     */
    private final class RequestIssuer {

        private final List<RequestUrl> urls;
        private final DownloadTracker tracker;
        private final CountDownLatch issued = new CountDownLatch(1);

        RequestIssuer(List<RequestUrl> urls, DownloadTracker tracker) {
            this.urls = urls;
            this.tracker = tracker;
        }

        /** Runs on the connection's loop. */
        void issue(QuicConnection connection) {
            if (issued.getCount() == 0) {
                return;
            }
            issued.countDown();
            for (int i = 0; i < urls.size(); i++) {
                RequestUrl url = urls.get(i);
                connection.openStream(new HqDownload(url, downloads.resolve(url.fileName), tracker));
            }
        }

        boolean awaitIssued(long timeoutMillis) throws InterruptedException {
            return issued.await(timeoutMillis, TimeUnit.MILLISECONDS);
        }

    }

}
