/*
 * ByteStreamLexerTest.java
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

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link ByteStreamLexer}, exercising the scaffold's own
 * mechanics (windowing, cap enforcement, text-mode chunking, raw escapes,
 * and replay safety across arbitrarily sliced {@code feed()} calls) using
 * a minimal toy protocol, independent of any real protocol handler.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ByteStreamLexerTest {

    /** Toy token vocabulary: WORD, single-char SP/COLON, CRLF, TEXT. */
    enum Tok { WORD, SP, COLON, CRLF, TEXT }

    /**
     * A minimal toy lexer: WORD characters accumulate until a separator
     * (space, colon) or CRLF; separators are emitted as single-byte
     * tokens. Deliberately uses {@link #regionStart()} rather than a
     * subclass-local absolute position field, per the replay-safety
     * guidance in {@link ByteStreamLexer}'s class Javadoc.
     */
    static class ToyLexer extends ByteStreamLexer<Tok> {

        private boolean lastWasCR;

        ToyLexer(Handler<Tok> handler, int maxTokenLength) {
            super(handler, maxTokenLength, Tok.CRLF, Tok.TEXT);
        }

        @Override
        protected boolean consume(byte b) {
            int pos = currentPosition();
            if (b == '\n' && lastWasCR) {
                int crlfStart = pos - 2;
                if (crlfStart > regionStart()) {
                    emit(Tok.WORD, regionStart(), crlfStart);
                }
                emit(Tok.CRLF, crlfStart, pos);
                lastWasCR = false;
                return true;
            }
            if (b == '\r') {
                lastWasCR = true;
                return true;
            }
            lastWasCR = false;
            if (b == ' ') {
                int start = regionStart();
                if (pos - 1 > start) {
                    emit(Tok.WORD, start, pos - 1);
                }
                emit(Tok.SP, pos - 1, pos);
                return true;
            }
            if (b == ':') {
                int start = regionStart();
                if (pos - 1 > start) {
                    emit(Tok.WORD, start, pos - 1);
                }
                emit(Tok.COLON, pos - 1, pos);
                return true;
            }
            return true;
        }

        void triggerRaw(long n) {
            enterRaw(n);
        }

        void triggerRawUntil(byte[] delim) {
            enterRawUntil(delim);
        }
    }

    /** Records every callback invocation for assertion. */
    static class RecordingHandler implements ByteStreamLexer.Handler<Tok> {

        static class Event {
            final Tok type;
            final String text;
            Event(Tok type, String text) {
                this.type = type;
                this.text = text;
            }
            @Override
            public String toString() {
                return type + "(" + text + ")";
            }
        }

        final List<Event> events = new ArrayList<Event>();
        final ByteArrayOutputStream rawBytes = new ByteArrayOutputStream();
        int tokenTooLongCount;
        boolean latchTextOnColon;
        Runnable onCrlf;

        @Override
        public boolean token(Tok type, ByteBuffer window) {
            byte[] copy = new byte[window.remaining()];
            window.get(copy);
            events.add(new Event(type, new String(copy, StandardCharsets.US_ASCII)));
            if (type == Tok.CRLF && onCrlf != null) {
                onCrlf.run();
            }
            return type == Tok.COLON && latchTextOnColon;
        }

        @Override
        public void rawBytes(ByteBuffer slice) {
            byte[] copy = new byte[slice.remaining()];
            slice.get(copy);
            rawBytes.write(copy, 0, copy.length);
        }

        @Override
        public void tokenTooLong() {
            tokenTooLongCount++;
        }

        String eventsAsString() {
            StringBuilder sb = new StringBuilder();
            for (Event e : events) {
                sb.append(e.toString());
                sb.append(' ');
            }
            return sb.toString().trim();
        }
    }

    private static ByteBuffer bytesOf(String s) {
        return ByteBuffer.wrap(s.getBytes(StandardCharsets.US_ASCII));
    }

    // ── Basic token boundary recognition ──────────────────────────────

    @Test
    public void testBasicTokenSequence() {
        RecordingHandler handler = new RecordingHandler();
        ToyLexer lexer = new ToyLexer(handler, 1024);
        lexer.feed(bytesOf("EHLO mail.sender.com\r\n"));
        assertEquals("WORD(EHLO) SP( ) WORD(mail.sender.com) CRLF(\r\n)",
                handler.eventsAsString());
    }

    @Test
    public void testMultipleLinesInOneFeedCall() {
        RecordingHandler handler = new RecordingHandler();
        ToyLexer lexer = new ToyLexer(handler, 1024);
        lexer.feed(bytesOf("AB CD\r\nEF GH\r\n"));
        assertEquals("WORD(AB) SP( ) WORD(CD) CRLF(\r\n) "
                + "WORD(EF) SP( ) WORD(GH) CRLF(\r\n)",
                handler.eventsAsString());
    }

    // ── Window validity / zero-copy contract ──────────────────────────

    @Test
    public void testBufferRestoredAfterFeed() {
        RecordingHandler handler = new RecordingHandler();
        ToyLexer lexer = new ToyLexer(handler, 1024);
        ByteBuffer buf = bytesOf("AB CD\r\n");
        int originalCapacity = buf.capacity();
        lexer.feed(buf);
        // Fully consumed: position should sit at the end (limit).
        assertEquals(originalCapacity, buf.limit());
        assertEquals(buf.limit(), buf.position());
    }

    // ── Underflow rewind: same stream, sliced at every possible offset ──

    @Test
    public void testUnderflowRewindByteAtATime() {
        String line = "MAIL FROM:<alice@example.com>\r\n";
        RecordingHandler handler = new RecordingHandler();
        ToyLexer lexer = new ToyLexer(handler, 1024);

        // Simulate the transport contract: a persistent growable buffer,
        // fed one byte at a time, compacted between feeds exactly as
        // TCPEndpoint.processInbound() does.
        ByteBuffer netIn = ByteBuffer.allocate(256);
        byte[] wire = line.getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < wire.length; i++) {
            netIn.put(wire[i]);
            netIn.flip();
            lexer.feed(netIn);
            netIn.compact();
        }
        assertEquals("WORD(MAIL) SP( ) WORD(FROM) COLON(:) "
                + "WORD(<alice@example.com>) CRLF(\r\n)",
                handler.eventsAsString());
    }

    @Test
    public void testUnderflowRewindAtEveryChunkSize() {
        String line = "RCPT TO:<bob@example.com>\r\n";
        byte[] wire = line.getBytes(StandardCharsets.US_ASCII);
        String expected = "WORD(RCPT) SP( ) WORD(TO) COLON(:) "
                + "WORD(<bob@example.com>) CRLF(\r\n)";

        for (int chunkSize = 1; chunkSize <= wire.length; chunkSize++) {
            RecordingHandler handler = new RecordingHandler();
            ToyLexer lexer = new ToyLexer(handler, 1024);
            ByteBuffer netIn = ByteBuffer.allocate(256);
            int offset = 0;
            while (offset < wire.length) {
                int len = Math.min(chunkSize, wire.length - offset);
                netIn.put(wire, offset, len);
                offset += len;
                netIn.flip();
                lexer.feed(netIn);
                netIn.compact();
            }
            assertEquals("chunk size " + chunkSize, expected, handler.eventsAsString());
        }
    }

    // ── Cap enforcement (SEC-032 hard cap) ─────────────────────────────

    @Test
    public void testTokenTooLongFiresBeforeAssemblingOverlongToken() {
        RecordingHandler handler = new RecordingHandler();
        ToyLexer lexer = new ToyLexer(handler, 5);
        lexer.feed(bytesOf("TOOLONGWORD\r\n"));
        assertEquals(1, handler.tokenTooLongCount);
        // No WORD token should have been emitted for the over-long run.
        for (RecordingHandler.Event e : handler.events) {
            assertNotEquals(Tok.WORD, e.type);
        }
    }

    @Test
    public void testTokenAtExactlyCapSucceeds() {
        RecordingHandler handler = new RecordingHandler();
        ToyLexer lexer = new ToyLexer(handler, 5);
        lexer.feed(bytesOf("FIVEC \r\n"));
        assertEquals(0, handler.tokenTooLongCount);
        assertEquals("WORD(FIVEC) SP( ) CRLF(\r\n)", handler.eventsAsString());
    }

    @Test
    public void testCapDoesNotApplyToTextMode() {
        RecordingHandler handler = new RecordingHandler();
        handler.latchTextOnColon = true;
        ToyLexer lexer = new ToyLexer(handler, 5);
        String longText = "this is a much longer piece of free-form text";
        lexer.feed(bytesOf("K:" + longText + "\r\n"));
        assertEquals(0, handler.tokenTooLongCount);
        StringBuilder reconstructed = new StringBuilder();
        for (RecordingHandler.Event e : handler.events) {
            if (e.type == Tok.TEXT) {
                reconstructed.append(e.text);
            }
        }
        assertEquals(longText, reconstructed.toString());
    }

    // ── checkTokenCap() — transport-buffer contract (§3.5) ─────────────

    @Test
    public void testCheckTokenCapAcceptsCapWithinNetInSize() {
        ByteStreamLexer.checkTokenCap(8192, 16384);
        ByteStreamLexer.checkTokenCap(8192, 8192);
    }

    @Test
    public void testCheckTokenCapRejectsCapExceedingNetInSize() {
        try {
            ByteStreamLexer.checkTokenCap(16385, 16384);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    // ── Text mode: chunked emission, never buffered by the lexer ───────

    @Test
    public void testTextModeLatchedByColonReturnTrue() {
        RecordingHandler handler = new RecordingHandler();
        handler.latchTextOnColon = true;
        ToyLexer lexer = new ToyLexer(handler, 1024);
        lexer.feed(bytesOf("Subject:hello world\r\n"));
        assertEquals("WORD(Subject) COLON(:) TEXT(hello world) CRLF(\r\n)",
                handler.eventsAsString());
    }

    @Test
    public void testTextModeNotLatchedWhenHandlerReturnsFalse() {
        RecordingHandler handler = new RecordingHandler();
        handler.latchTextOnColon = false;
        ToyLexer lexer = new ToyLexer(handler, 1024);
        lexer.feed(bytesOf("A:B C\r\n"));
        // Without latching, ':' is just an ordinary separator and "B", "C"
        // are lexed as ordinary WORD tokens, not TEXT.
        assertEquals("WORD(A) COLON(:) WORD(B) SP( ) WORD(C) CRLF(\r\n)",
                handler.eventsAsString());
    }

    @Test
    public void testTextChunksAcrossMultipleFeedCalls() {
        RecordingHandler handler = new RecordingHandler();
        handler.latchTextOnColon = true;
        ToyLexer lexer = new ToyLexer(handler, 1024);

        ByteBuffer netIn = ByteBuffer.allocate(256);
        String[] chunks = {"K:hello ", "wonderful ", "world", "\r\n"};
        for (String chunk : chunks) {
            netIn.put(chunk.getBytes(StandardCharsets.US_ASCII));
            netIn.flip();
            lexer.feed(netIn);
            netIn.compact();
        }

        StringBuilder reconstructed = new StringBuilder();
        int textChunkCount = 0;
        for (RecordingHandler.Event e : handler.events) {
            if (e.type == Tok.TEXT) {
                reconstructed.append(e.text);
                textChunkCount++;
            }
        }
        assertEquals("hello wonderful world", reconstructed.toString());
        // The point of chunking: more than one TEXT token was emitted
        // because the value spanned multiple feed() calls.
        assertTrue("expected multiple TEXT chunks, got " + textChunkCount,
                textChunkCount > 1);
        assertEquals(Tok.CRLF, handler.events.get(handler.events.size() - 1).type);
    }

    @Test
    public void testTextModeCRLFSplitAcrossFeedCalls() {
        RecordingHandler handler = new RecordingHandler();
        handler.latchTextOnColon = true;
        ToyLexer lexer = new ToyLexer(handler, 1024);

        ByteBuffer netIn = ByteBuffer.allocate(256);
        String[] chunks = {"K:abc\r", "\n"};
        for (String chunk : chunks) {
            netIn.put(chunk.getBytes(StandardCharsets.US_ASCII));
            netIn.flip();
            lexer.feed(netIn);
            netIn.compact();
        }

        StringBuilder reconstructed = new StringBuilder();
        for (RecordingHandler.Event e : handler.events) {
            if (e.type == Tok.TEXT) {
                reconstructed.append(e.text);
            }
        }
        assertEquals("abc", reconstructed.toString());
        assertEquals(Tok.CRLF, handler.events.get(handler.events.size() - 1).type);
        assertEquals("\r\n", handler.events.get(handler.events.size() - 1).text);
    }

    // ── RAW(n) escape: entry, resume within one feed() call, and split ──

    @Test
    public void testRawFixedEntryAndResumeWithinOneFeedCall() {
        RecordingHandler handler = new RecordingHandler();
        final ToyLexer[] lexerHolder = new ToyLexer[1];
        handler.onCrlf = new Runnable() {
            @Override
            public void run() {
                lexerHolder[0].triggerRaw(5);
            }
        };
        ToyLexer lexer = new ToyLexer(handler, 1024);
        lexerHolder[0] = lexer;

        // "CMD\r\n" triggers raw mode for 5 bytes ("HELLO"), then "X Y\r\n"
        // should resume as ordinary structured tokens, all in one feed().
        lexer.feed(bytesOf("CMD\r\nHELLOX Y\r\n"));

        assertEquals("HELLO", handler.rawBytes.toString(StandardCharsets.US_ASCII));
        // Structured scanning resumed correctly after the raw escape.
        boolean sawResumedTokens = false;
        for (RecordingHandler.Event e : handler.events) {
            if ("X".equals(e.text)) {
                sawResumedTokens = true;
            }
        }
        assertTrue(sawResumedTokens);
    }

    @Test
    public void testRawFixedSplitAcrossFeedCalls() {
        RecordingHandler handler = new RecordingHandler();
        final ToyLexer[] lexerHolder = new ToyLexer[1];
        handler.onCrlf = new Runnable() {
            @Override
            public void run() {
                lexerHolder[0].triggerRaw(10);
            }
        };
        ToyLexer lexer = new ToyLexer(handler, 1024);
        lexerHolder[0] = lexer;

        ByteBuffer netIn = ByteBuffer.allocate(256);
        String[] chunks = {"CMD\r\n", "HEL", "LOWORL", "D\r\n"};
        for (String chunk : chunks) {
            netIn.put(chunk.getBytes(StandardCharsets.US_ASCII));
            netIn.flip();
            lexer.feed(netIn);
            netIn.compact();
        }

        assertEquals("HELLOWORLD", handler.rawBytes.toString(StandardCharsets.US_ASCII));
        // The trailing "\r\n" after the 10 raw bytes should have been lexed
        // as a structured CRLF token, proving resume worked.
        assertEquals(Tok.CRLF, handler.events.get(handler.events.size() - 1).type);
    }

    // ── RAW_UNTIL(delimiter) escape ────────────────────────────────────

    @Test
    public void testRawUntilFindsDelimiterInOneFeedCall() {
        RecordingHandler handler = new RecordingHandler();
        final ToyLexer[] lexerHolder = new ToyLexer[1];
        handler.onCrlf = new Runnable() {
            @Override
            public void run() {
                if (handler.rawBytes.size() == 0) {
                    lexerHolder[0].triggerRawUntil(
                            "\r\n.\r\n".getBytes(StandardCharsets.US_ASCII));
                }
            }
        };
        ToyLexer lexer = new ToyLexer(handler, 1024);
        lexerHolder[0] = lexer;

        lexer.feed(bytesOf("DATA\r\nline one\r\nline two\r\n.\r\nOK\r\n"));

        // The delimiter is the literal 5 bytes "\r\n.\r\n", including the
        // CRLF that immediately precedes the dot; that CRLF is excluded
        // from the delivered content, not treated as the content's own
        // trailing line terminator (see the class Javadoc discussion of
        // enterRawUntil()).
        assertEquals("line one\r\nline two",
                handler.rawBytes.toString(StandardCharsets.US_ASCII));
        // Structured scanning must have resumed to see the trailing "OK".
        boolean sawOk = false;
        for (RecordingHandler.Event e : handler.events) {
            if ("OK".equals(e.text)) {
                sawOk = true;
            }
        }
        assertTrue(sawOk);
    }

    @Test
    public void testRawUntilDelimiterSplitAcrossFeedCalls() {
        RecordingHandler handler = new RecordingHandler();
        final ToyLexer[] lexerHolder = new ToyLexer[1];
        handler.onCrlf = new Runnable() {
            @Override
            public void run() {
                if (handler.rawBytes.size() == 0) {
                    lexerHolder[0].triggerRawUntil(
                            "\r\n.\r\n".getBytes(StandardCharsets.US_ASCII));
                }
            }
        };
        ToyLexer lexer = new ToyLexer(handler, 1024);
        lexerHolder[0] = lexer;

        ByteBuffer netIn = ByteBuffer.allocate(256);
        // Split exactly inside the delimiter, across three separate reads.
        String[] chunks = {"DATA\r\nbody\r", "\n.", "\r\nOK\r\n"};
        for (String chunk : chunks) {
            netIn.put(chunk.getBytes(StandardCharsets.US_ASCII));
            netIn.flip();
            lexer.feed(netIn);
            netIn.compact();
        }

        assertEquals("body", handler.rawBytes.toString(StandardCharsets.US_ASCII));
        boolean sawOk = false;
        for (RecordingHandler.Event e : handler.events) {
            if ("OK".equals(e.text)) {
                sawOk = true;
            }
        }
        assertTrue(sawOk);
    }

    @Test
    public void testRawUntilFalseMatchIsRecoveredAsRawContent() {
        RecordingHandler handler = new RecordingHandler();
        final ToyLexer[] lexerHolder = new ToyLexer[1];
        handler.onCrlf = new Runnable() {
            @Override
            public void run() {
                if (handler.rawBytes.size() == 0) {
                    lexerHolder[0].triggerRawUntil(
                            "\r\n.\r\n".getBytes(StandardCharsets.US_ASCII));
                }
            }
        };
        ToyLexer lexer = new ToyLexer(handler, 1024);
        lexerHolder[0] = lexer;

        // "\r\n." looks like the start of the delimiter but is followed by
        // "X" (not "\r\n"), so it must be recovered as literal raw content
        // — this is dot-stuffing-style content ("\r\n..\r\n" in real SMTP,
        // simplified here to just prove false-start recovery).
        lexer.feed(bytesOf("DATA\r\nfoo\r\n.Xbar\r\n.\r\nOK\r\n"));

        assertEquals("foo\r\n.Xbar", handler.rawBytes.toString(StandardCharsets.US_ASCII));
    }

    @Test
    public void testRawUntilFalseMatchSplitExactlyAtFeedBoundary() {
        RecordingHandler handler = new RecordingHandler();
        final ToyLexer[] lexerHolder = new ToyLexer[1];
        handler.onCrlf = new Runnable() {
            @Override
            public void run() {
                if (handler.rawBytes.size() == 0) {
                    lexerHolder[0].triggerRawUntil(
                            "\r\n.\r\n".getBytes(StandardCharsets.US_ASCII));
                }
            }
        };
        ToyLexer lexer = new ToyLexer(handler, 1024);
        lexerHolder[0] = lexer;

        ByteBuffer netIn = ByteBuffer.allocate(256);
        // The tentative match "\r\n." ends exactly at a feed() boundary,
        // and the NEXT call supplies a byte that breaks the match.
        String[] chunks = {"DATA\r\nfoo\r\n.", "Xbar\r\n.\r\nOK\r\n"};
        for (String chunk : chunks) {
            netIn.put(chunk.getBytes(StandardCharsets.US_ASCII));
            netIn.flip();
            lexer.feed(netIn);
            netIn.compact();
        }

        assertEquals("foo\r\n.Xbar", handler.rawBytes.toString(StandardCharsets.US_ASCII));
    }

    // ── consume() abort escape hatch ───────────────────────────────────

    static class AbortingLexer extends ByteStreamLexer<Tok> {
        boolean abortOnNul;
        AbortingLexer(Handler<Tok> handler, int maxTokenLength) {
            super(handler, maxTokenLength, Tok.CRLF, Tok.TEXT);
        }
        @Override
        protected boolean consume(byte b) {
            if (b == 0) {
                return false;
            }
            return true;
        }
    }

    @Test
    public void testConsumeAbortStopsFeedImmediately() {
        RecordingHandler handler = new RecordingHandler();
        AbortingLexer lexer = new AbortingLexer(handler, 1024);
        ByteBuffer buf = ByteBuffer.wrap(new byte[] {'A', 'B', 0, 'C', 'D'});
        lexer.feed(buf);
        // Stopped at the NUL byte; nothing after it should have been seen.
        assertEquals(3, buf.position());
        assertTrue(handler.events.isEmpty());
    }

    // ── Constructor validation ──────────────────────────────────────────

    @Test
    public void testConstructorRejectsNullHandler() {
        try {
            new ToyLexer(null, 1024);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void testConstructorRejectsNonPositiveMaxTokenLength() {
        RecordingHandler handler = new RecordingHandler();
        try {
            new ToyLexer(handler, 0);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // expected
        }
        try {
            new ToyLexer(handler, -1);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void testEnterRawUntilRejectsEmptyDelimiter() {
        RecordingHandler handler = new RecordingHandler();
        ToyLexer lexer = new ToyLexer(handler, 1024);
        try {
            lexer.triggerRawUntil(new byte[0]);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void testEnterRawRejectsNegativeLength() {
        RecordingHandler handler = new RecordingHandler();
        ToyLexer lexer = new ToyLexer(handler, 1024);
        try {
            lexer.triggerRaw(-1);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}
