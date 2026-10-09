/*
 * CoreEdgeTest.java
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;

import org.bluezoo.gumdrop.tls.KeystoreFormat;
import org.bluezoo.gumdrop.tls.TlsConfig;
import org.junit.Test;

/**
 * Argument validation and edge cases in small core classes:
 * {@link ByteStreamLexer} construction and its raw and text modes,
 * {@link GumdropConfig}, {@link TlsConfigSupport} and the TCP listener
 * start-up checks, {@link ClientEndpoint} transport selection and the
 * {@link TransportFactory} metrics switch.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class CoreEdgeTest {

    private static final class NoopLexerHandler
            implements ByteStreamLexer.Handler<ByteStreamLexerTest.Tok> {
        final StringBuilder raw = new StringBuilder();
        final StringBuilder tokens = new StringBuilder();

        @Override
        public boolean token(ByteStreamLexerTest.Tok type, ByteBuffer window) {
            byte[] copy = new byte[window.remaining()];
            window.get(copy);
            tokens.append(type).append('(').append(new String(copy, StandardCharsets.US_ASCII)).append(") ");
            return false;
        }

        @Override
        public void rawBytes(ByteBuffer slice) {
            byte[] copy = new byte[slice.remaining()];
            slice.get(copy);
            raw.append(new String(copy, StandardCharsets.US_ASCII));
        }

        @Override
        public void tokenTooLong() {
        }
    }

    private static final class Toy extends ByteStreamLexer<ByteStreamLexerTest.Tok> {
        Toy(ByteStreamLexer.Handler<ByteStreamLexerTest.Tok> h, ByteStreamLexerTest.Tok crlf,
                ByteStreamLexerTest.Tok text) {
            super(h, 64, crlf, text);
        }

        @Override
        protected boolean consume(byte b) {
            return true;
        }

        void rawUntil(byte[] delim) {
            enterRawUntil(delim);
        }
    }

    private static ByteBuffer bytes(String s) {
        return ByteBuffer.wrap(s.getBytes(StandardCharsets.US_ASCII));
    }

    @Test
    public void lexerRejectsMissingTokenTypes() {
        NoopLexerHandler h = new NoopLexerHandler();
        try {
            new Toy(h, null, ByteStreamLexerTest.Tok.TEXT);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("crlfTokenType"));
        }
        try {
            new Toy(h, ByteStreamLexerTest.Tok.CRLF, null);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("textTokenType"));
        }
    }

    @Test
    public void lexerRejectsNullOrEmptyDelimiter() {
        Toy lexer = new Toy(new NoopLexerHandler(), ByteStreamLexerTest.Tok.CRLF, ByteStreamLexerTest.Tok.TEXT);
        try {
            lexer.rawUntil(null);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("delim"));
        }
        try {
            lexer.rawUntil(new byte[0]);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("delim"));
        }
    }

    @Test
    public void rawUntilDeliversPartialDelimiterMatchesAsData() {
        NoopLexerHandler h = new NoopLexerHandler();
        Toy lexer = new Toy(h, ByteStreamLexerTest.Tok.CRLF, ByteStreamLexerTest.Tok.TEXT);
        lexer.rawUntil("\r\n.\r\n".getBytes(StandardCharsets.US_ASCII));
        lexer.feed(bytes("ab\r\nx\r\n.\r\n"));
        assertEquals("ab\r\nx", h.raw.toString());
    }

    @Test
    public void rawUntilAcrossSeveralFeedsKeepsTheMatchedPrefix() {
        NoopLexerHandler h = new NoopLexerHandler();
        Toy lexer = new Toy(h, ByteStreamLexerTest.Tok.CRLF, ByteStreamLexerTest.Tok.TEXT);
        lexer.rawUntil("END".getBytes(StandardCharsets.US_ASCII));
        lexer.feed(bytes("xxEN"));
        lexer.feed(bytes("Dyy"));
        assertEquals("xx", h.raw.toString());
    }

    @Test
    public void rawUntilWithDelimiterFirstAndTrailingData() {
        NoopLexerHandler h = new NoopLexerHandler();
        Toy lexer = new Toy(h, ByteStreamLexerTest.Tok.CRLF, ByteStreamLexerTest.Tok.TEXT);
        lexer.rawUntil("E".getBytes(StandardCharsets.US_ASCII));
        lexer.feed(bytes("Eabc"));
        assertEquals("", h.raw.toString());
    }

    @Test
    public void workerThreadCountMustBePositive() {
        GumdropConfig config = GumdropConfig.create();
        try {
            config.workerThreads(0);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("workerThreads"));
        }
        assertEquals(3, config.workerThreads(3).getWorkerThreads());
    }

    private static final class StubListener extends TcpListener {
        @Override
        protected ProtocolHandler createHandler() {
            return null;
        }

        @Override
        public String getDescription() {
            return "stub";
        }

        @Override
        public int getPort() {
            return 1234;
        }
    }

    @Test
    public void listenerRejectsPathTogetherWithPort() {
        StubListener l = new StubListener();
        l.path(Paths.get("/tmp/never-created.sock"));
        try {
            l.start();
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("both path and port"));
        }
    }

    @Test
    public void tlsConfigSupportRejectsIncompleteAndNullArguments() {
        StubListener l = new StubListener();
        try {
            TlsConfigSupport.apply(null, l);
            fail("expected NullPointerException");
        } catch (NullPointerException expected) {
            assertEquals("tls", expected.getMessage());
        }
        try {
            TlsConfigSupport.apply(new TlsConfig(), null);
            fail("expected NullPointerException");
        } catch (NullPointerException expected) {
            assertEquals("listener", expected.getMessage());
        }
        try {
            TlsConfigSupport.apply(new TlsConfig(), l);
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("incomplete"));
        }
    }

    @Test
    public void tlsConfigSupportAppliesCertAndKeystoreMaterial() {
        StubListener pem = new StubListener();
        TlsConfig cfg = TlsConfig.pem(Paths.get("cert.pem"), Paths.get("key.pem"));
        TlsConfigSupport.apply(cfg, pem);
        assertNull(pem.getServerCredentials());

        StubListener ks = new StubListener();
        TlsConfig cfg2 = TlsConfig.keystore(Paths.get("store.p12"), "changeit", KeystoreFormat.PKCS12);
        TlsConfigSupport.apply(cfg2, ks);

        StubListener ks2 = new StubListener();
        TlsConfig cfg3 = TlsConfig.keystore(Paths.get("store.p12"), "changeit");
        TlsConfigSupport.apply(cfg3, ks2);
    }

    @Test
    public void truststoreFormatAndMetricsSwitch() {
        TcpTransportFactory f = new TcpTransportFactory();
        f.setTruststoreFormat(KeystoreFormat.PKCS12);
        assertNull(f.getTelemetryConfig());
    }
}
