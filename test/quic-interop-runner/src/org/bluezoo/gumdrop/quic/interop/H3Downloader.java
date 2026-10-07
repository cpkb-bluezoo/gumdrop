/*
 * H3Downloader.java
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
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.GumdropConfig;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.http.HttpClient;
import org.bluezoo.gumdrop.http.HttpError;
import org.bluezoo.gumdrop.http.client.HttpClientHandler;
import org.bluezoo.gumdrop.http.client.HttpRequest;
import org.bluezoo.gumdrop.http.client.HttpResponseHandler;
import org.bluezoo.gumdrop.quic.tls.PemCredentials;

/**
 * The {@code http3} test case on the client: one HTTP/3 connection,
 * every file requested at once on its own request stream, bodies stored
 * under {@code /downloads}. The server certificate is verified against
 * the runner's CA.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class H3Downloader {

    private static final Logger LOGGER = Logger.getLogger(H3Downloader.class.getName());

    boolean download(List<RequestUrl> urls, Path downloads, Path caFile, long timeoutMillis)
            throws Exception {
        if (urls.isEmpty()) {
            return true;
        }
        RequestUrl first = urls.get(0);
        Gumdrop gumdrop = Gumdrop.boot(GumdropConfig.create().workerThreads(1));
        try {
            SelectorLoop loop = gumdrop.nextWorkerLoop();
            final HttpClient client = new HttpClient(loop, first.host, first.port);
            client.setSecure(true);
            client.setH3Enabled(true);
            client.setAltSvcEnabled(false);
            client.setDnsHttpsRecordEnabled(false);
            client.setBlockPrivateAddresses(false);
            client.setSendAcceptEncodingHeader(false);
            client.setTrustManager(PemCredentials.loadTrustManager(caFile));

            final CountDownLatch connected = new CountDownLatch(1);
            final AtomicReference<Exception> connectError = new AtomicReference<Exception>();
            LOGGER.info("connecting to " + first.host + ":" + first.port + " over HTTP/3 for "
                    + urls.size() + " file(s)");
            client.connect(gumdrop, new HttpClientHandler() {
                @Override
                public void onConnected(Endpoint endpoint) {
                    connected.countDown();
                }

                @Override
                public void onSecurityEstablished(SecurityInfo info) {
                    connected.countDown();
                }

                @Override
                public void onError(Exception cause) {
                    connectError.set(cause);
                    connected.countDown();
                }

                @Override
                public void onDisconnected() {
                }
            });
            if (!connected.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
                LOGGER.warning("HTTP/3 connection did not complete within " + timeoutMillis + " ms");
                return false;
            }
            if (connectError.get() != null) {
                LOGGER.log(Level.WARNING, "HTTP/3 connection failed", connectError.get());
                return false;
            }
            DownloadTracker tracker = new DownloadTracker(urls.size());
            for (int i = 0; i < urls.size(); i++) {
                RequestUrl url = urls.get(i);
                HttpRequest request = client.get(url.path,
                        new H3Download(url, downloads.resolve(url.fileName), tracker));
                request.endMessage();
            }
            boolean complete = tracker.await(timeoutMillis);
            if (!complete) {
                LOGGER.warning("downloads did not finish within " + timeoutMillis + " ms");
            } else if (tracker.failures() > 0) {
                LOGGER.warning(tracker.failures() + " download(s) failed");
            }
            client.close();
            return complete && tracker.failures() == 0;
        } finally {
            gumdrop.shutdown();
            gumdrop.join();
        }
    }

    /**
     * Stores one response body; anything but a 200 is a failure.
     */
    private static final class H3Download implements HttpResponseHandler {

        private final RequestUrl url;
        private final Path target;
        private final DownloadTracker tracker;
        private FileChannel channel;
        private boolean ok = true;
        private boolean finished;

        H3Download(RequestUrl url, Path target, DownloadTracker tracker) {
            this.url = url;
            this.target = target;
            this.tracker = tracker;
        }

        @Override
        public void status(int code) {
            if (code != 200) {
                LOGGER.warning(url.path + ": status " + code);
                ok = false;
            }
        }

        @Override
        public void bodyContent(ByteBuffer data) {
            if (finished || !ok) {
                return;
            }
            try {
                if (channel == null) {
                    channel = FileChannel.open(target, StandardOpenOption.CREATE,
                            StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
                }
                while (data.hasRemaining()) {
                    channel.write(data);
                }
            } catch (IOException e) {
                LOGGER.log(Level.SEVERE, "cannot write " + target, e);
                ok = false;
            }
        }

        @Override
        public void endMessage() {
            finish();
        }

        @Override
        public void error(HttpError error, String detail) {
            if (finished) {
                return;
            }
            LOGGER.warning(url.path + ": " + error + " " + detail);
            ok = false;
            finish();
        }

        @Override
        public void failed(Exception cause) {
            // Closing the connection after a completed response reports
            // the close here too; only a failure before the end counts.
            if (finished) {
                return;
            }
            LOGGER.log(Level.WARNING, url.path + " failed", cause);
            ok = false;
            finish();
        }

        private void finish() {
            if (finished) {
                return;
            }
            finished = true;
            try {
                if (channel == null && ok) {
                    // An empty body still has to produce the file.
                    channel = FileChannel.open(target, StandardOpenOption.CREATE,
                            StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
                }
                if (channel != null) {
                    channel.close();
                }
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "cannot close " + target, e);
                ok = false;
            }
            tracker.finished(ok);
        }

    }

}
