/*
 * ServerXmlLoaderChunksTest.java
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


package org.bluezoo.gumdrop.servlet.container;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.channels.CompletionHandler;
import java.nio.charset.StandardCharsets;

import org.bluezoo.gumdrop.http.HttpServer;
import org.junit.Test;

/**
 * Tests the chunked asynchronous reading logic of {@link ServerXmlLoader}
 * against a hand-written in-memory chunk source, so no real file or
 * asynchronous channel is involved. The real-file entry point is covered by
 * {@code ServerXmlLoaderFileIntegrationTest}. The mock completes each read
 * synchronously on the calling thread, so the tests are deterministic.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ServerXmlLoaderChunksTest {

    /** Serves a byte array in chunks of at most chunkSize bytes. */
    private static final class FakeSource implements ServerXmlLoader.ChunkSource {

        private final byte[] data;
        private final int chunkSize;
        private final boolean failReads;
        int reads;
        boolean closed;

        FakeSource(String content, int chunkSize, boolean failReads) {
            this.data = content.getBytes(StandardCharsets.UTF_8);
            this.chunkSize = chunkSize;
            this.failReads = failReads;
        }

        @Override
        public void read(ByteBuffer dst, long position,
                CompletionHandler<Integer, Void> handler) {
            reads++;
            if (failReads) {
                handler.failed(new java.io.IOException("disk gone"), null);
                return;
            }
            if (position >= data.length) {
                handler.completed(Integer.valueOf(-1), null);
                return;
            }
            int n = Math.min(chunkSize, data.length - (int) position);
            n = Math.min(n, dst.remaining());
            dst.put(data, (int) position, n);
            handler.completed(Integer.valueOf(n), null);
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private HttpServer server;
    private String error;

    private FakeSource load(String xml, int chunkSize, boolean failReads) {
        FakeSource source = new FakeSource(xml, chunkSize, failReads);
        ServerXmlLoader.loadFrom(source, new File("/base"), "file:///base/server.xml",
                new ServerXmlLoader.Callback() {
                    @Override
                    public void onServer(HttpServer s) {
                        server = s;
                    }

                    @Override
                    public void onError(String e) {
                        error = e;
                    }
                });
        return source;
    }

    @Test
    public void loadsServerFromChunks() {
        FakeSource source = load("<server><context path='/app' root='webapp' distributable='true'/>"
                + "<listener port='8080'/></server>", 7, false);
        assertNull(error, error);
        assertNotNull(server);
        assertTrue(source.closed);
        assertTrue("many small chunks were read", source.reads > 5);
    }

    @Test
    public void tokensSplitAcrossChunksAreCarriedForward() {
        StringBuilder sb = new StringBuilder();
        sb.append("<server><!--");
        for (int i = 0; i < 3000; i++) {
            sb.append("padding padding ");
        }
        sb.append("--><listener port='8080'/></server>");
        load(sb.toString(), 701, false);
        assertNull(error, error);
        assertNotNull(server);
    }

    @Test
    public void malformedInputReportsParseError() {
        FakeSource source = load("<server><listener port='8080'></server>", 5, false);
        assertNull(server);
        assertNotNull(error);
        assertTrue(error, error.indexOf("parse error") >= 0);
        assertTrue(source.closed);
    }

    @Test
    public void emptyInputReportsError() {
        FakeSource source = load("", 5, false);
        assertNull(server);
        assertNotNull(error);
        assertTrue(source.closed);
    }

    @Test
    public void readFailureIsReported() {
        FakeSource source = load("<server/>", 5, true);
        assertNull(server);
        assertEquals("Error reading server.xml: disk gone", error);
        assertTrue(source.closed);
    }
}
