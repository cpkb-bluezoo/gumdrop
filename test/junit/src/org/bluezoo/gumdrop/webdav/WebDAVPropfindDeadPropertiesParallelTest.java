/*
 * WebDAVPropfindDeadPropertiesParallelTest.java
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

package org.bluezoo.gumdrop.webdav;

import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.StorageExecutor;
import org.bluezoo.gumdrop.http.HTTPResponseState;
import org.bluezoo.gumdrop.http.HTTPStatus;
import org.bluezoo.gumdrop.http.HTTPVersion;
import org.bluezoo.gumdrop.http.Headers;
import org.bluezoo.gumdrop.websocket.WebSocketEventHandler;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Principal;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Regression coverage for issue #306: PROPFIND previously loaded dead
 * properties one resource at a time, waiting for each {@link StorageExecutor}
 * round trip to finish before starting the next.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class WebDAVPropfindDeadPropertiesParallelTest {

    private static final int RESOURCE_COUNT = 24;
    private static final int STORAGE_DELAY_MS = 20;

    private Path tempRoot;
    private Gumdrop gumdrop;

    @Before
    public void setUp() throws Exception {
        tempRoot = Files.createTempDirectory("gumdrop-webdav-propfind-parallel");
        StorageExecutor.workThreadObserver = null;
        System.setProperty("gumdrop.storageThreads", "4");
        gumdrop = Gumdrop.getInstance();
        gumdrop.setDrainTimeoutMs(0);
        if (!gumdrop.isStarted()) {
            gumdrop.start();
        }
        assertNotNull(gumdrop.getStorageExecutor());
    }

    @After
    public void tearDown() throws Exception {
        StorageExecutor.workThreadObserver = null;
        if (gumdrop != null && gumdrop.isStarted()) {
            gumdrop.shutdown();
        }
        deleteRecursively(tempRoot);
    }

    @Test(timeout = 30000)
    public void propfindDeadPropertyLoadsFanOutAcrossStoragePool() throws Exception {
        Path tree = Files.createDirectory(tempRoot.resolve("tree"));
        for (int i = 0; i < RESOURCE_COUNT; i++) {
            Path file = tree.resolve("file" + i + ".txt");
            Files.write(file, ("body" + i).getBytes(StandardCharsets.UTF_8));
            writeSidecar(file, "tag-" + i);
        }

        DeadPropertyStore store = new DeadPropertyStore();
        store.setMode(DeadPropertyStore.Mode.SIDECAR);
        FileHandler handler = newHandler(tempRoot, store);

        StorageExecutor.workThreadObserver = new StorageExecutor.WorkThreadObserver() {
            @Override
            public void observed(Thread worker) {
                try {
                    Thread.sleep(STORAGE_DELAY_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };

        RecordingState state = new RecordingState();
        Headers req = new Headers();
        req.add(":method", "PROPFIND");
        req.add(":path", "/tree");
        req.add(DAVConstants.HEADER_DEPTH, "infinity");

        long start = System.nanoTime();
        handler.headers(state, req);
        assertTrue("PROPFIND did not complete: " + state.status(),
                state.await(20, TimeUnit.SECONDS));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertEquals(HTTPStatus.MULTI_STATUS.code, state.status());
        String xml = new String(state.body(), StandardCharsets.UTF_8);
        for (int i = 0; i < RESOURCE_COUNT; i++) {
            assertTrue("response must include file" + i,
                    xml.contains("file" + i + ".txt"));
        }

        // Strict serialisation: one storage submission completes before the
        // next begins, so (tree walk + N resources) * delay is a floor.
        long serialBudgetMs = (1L + RESOURCE_COUNT) * STORAGE_DELAY_MS;
        assertTrue("PROPFIND over " + RESOURCE_COUNT + " dead-property reads "
                + "took " + elapsedMs + "ms -- strict serialisation with "
                + STORAGE_DELAY_MS + "ms per storage op would need at least "
                + serialBudgetMs + "ms",
                elapsedMs < serialBudgetMs - 75);
    }

    private static FileHandler newHandler(Path root, DeadPropertyStore store) {
        Map<String, String> types = new HashMap<String, String>();
        types.put("txt", "text/plain");
        return new FileHandler(root, true, true,
                "GET, HEAD, PUT, DELETE, OPTIONS, PROPFIND, MKCOL, COPY, MOVE",
                new String[]{"index.html"}, types,
                new WebDAVLockManager(), store);
    }

    private static void writeSidecar(Path resource, String value)
            throws Exception {
        Path sidecar = DeadPropertyStore.sidecarPath(resource, false);
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<properties xmlns=\"" + DeadPropertyStore.PROPS_NAMESPACE
                + "\">\n"
                + "  <property ns=\"http://example.com/ns\" name=\"tag\" "
                + "xml=\"false\">\n"
                + value + "\n"
                + "  </property>\n"
                + "</properties>\n";
        Files.write(sidecar, xml.getBytes(StandardCharsets.UTF_8));
    }

    private static void deleteRecursively(Path p) throws Exception {
        if (p == null || !Files.exists(p)) {
            return;
        }
        if (Files.isDirectory(p)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(p)) {
                for (Path child : ds) {
                    deleteRecursively(child);
                }
            }
        }
        Files.deleteIfExists(p);
    }

    private static final class RecordingState implements HTTPResponseState {
        private final Object lock = new Object();
        private final ByteArrayOutputStream bodyOut = new ByteArrayOutputStream();
        private final CountDownLatch done = new CountDownLatch(1);
        private Headers responseHeaders;
        private int statusCode = -1;

        boolean await(long t, TimeUnit u) throws InterruptedException {
            return done.await(t, u);
        }

        int status() {
            synchronized (lock) {
                return statusCode;
            }
        }

        byte[] body() {
            synchronized (lock) {
                return bodyOut.toByteArray();
            }
        }

        @Override
        public void headers(Headers headers) {
            synchronized (lock) {
                this.responseHeaders = headers;
                String s = headers.getValue(":status");
                if (s != null) {
                    try {
                        statusCode = Integer.parseInt(s);
                    } catch (NumberFormatException ignore) {
                    }
                }
            }
        }

        @Override
        public void startResponseBody() {
        }

        @Override
        public void responseBodyContent(ByteBuffer data) {
            synchronized (lock) {
                byte[] b = new byte[data.remaining()];
                data.get(b);
                bodyOut.write(b, 0, b.length);
            }
        }

        @Override
        public void endResponseBody() {
        }

        @Override
        public void complete() {
            done.countDown();
        }

        @Override
        public void execute(Runnable task) {
            task.run();
        }

        @Override
        public void onWritable(Runnable callback) {
            if (callback != null) {
                callback.run();
            }
        }

        @Override
        public void pauseRequestBody() {
        }

        @Override
        public void resumeRequestBody() {
        }

        @Override
        public boolean pushPromise(Headers headers) {
            return false;
        }

        @Override
        public void upgradeToWebSocket(String subprotocol,
                WebSocketEventHandler handler) {
        }

        @Override
        public void cancel() {
            done.countDown();
        }

        @Override
        public SocketAddress getRemoteAddress() {
            return null;
        }

        @Override
        public SocketAddress getLocalAddress() {
            return null;
        }

        @Override
        public boolean isSecure() {
            return false;
        }

        @Override
        public SecurityInfo getSecurityInfo() {
            return null;
        }

        @Override
        public HTTPVersion getVersion() {
            return HTTPVersion.HTTP_1_1;
        }

        @Override
        public String getScheme() {
            return "http";
        }

        @Override
        public SelectorLoop getSelectorLoop() {
            return null;
        }

        @Override
        public Principal getPrincipal() {
            return null;
        }
    }
}
