/*
 * QuicDecoderSweepTest.java
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

package org.bluezoo.gumdrop.quic.packet;

import java.nio.ByteBuffer;
import java.util.List;

import org.bluezoo.gumdrop.quic.frame.QuicFrameHandler;
import org.bluezoo.gumdrop.quic.frame.QuicFrameParser;
import org.bluezoo.gumdrop.testsupport.TruncationSweep;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * Truncation and corruption sweeps over the QUIC packet header decoders
 * (long header invariants and prefix, Version Negotiation, Retry, VarInt)
 * and the frame parser. The header decoders may only throw
 * {@link IllegalArgumentException}; the frame parser reports every problem
 * through {@code frameError} and must never throw.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class QuicDecoderSweepTest {

    private static final int PARSE_INVARIANTS = 0;
    private static final int PARSE_PREFIX = 1;
    private static final int PARSE_VN = 2;
    private static final int PARSE_RETRY = 3;
    private static final int PARSE_FRAMES = 4;
    private static final int VARINT = 5;

    /** Ignores every frame. */
    private static final class NullFrames implements QuicFrameHandler {
        public void paddingFrameReceived(int length) { }
        public void pingFrameReceived() { }
        public void ackFrameReceived(long largestAcknowledged, long ackDelay, long[][] ranges) { }
        public void resetStreamFrameReceived(long streamId, long code, long finalSize) { }
        public void stopSendingFrameReceived(long streamId, long code) { }
        public void cryptoFrameReceived(long offset, ByteBuffer data) { }
        public void newTokenFrameReceived(ByteBuffer token) { }
        public void streamFrameReceived(long streamId, long offset, boolean fin, ByteBuffer data) { }
        public void maxDataFrameReceived(long maximumData) { }
        public void maxStreamDataFrameReceived(long streamId, long maximumStreamData) { }
        public void maxStreamsFrameReceived(boolean bidirectional, long maximumStreams) { }
        public void dataBlockedFrameReceived(long maximumData) { }
        public void streamDataBlockedFrameReceived(long streamId, long maximumStreamData) { }
        public void streamsBlockedFrameReceived(boolean bidirectional, long maximumStreams) { }
        public void newConnectionIdFrameReceived(long sequenceNumber, long retirePriorTo,
                ByteBuffer connectionId, ByteBuffer statelessResetToken) { }
        public void retireConnectionIdFrameReceived(long sequenceNumber) { }
        public void pathChallengeFrameReceived(ByteBuffer data) { }
        public void pathResponseFrameReceived(ByteBuffer data) { }
        public void connectionCloseFrameReceived(boolean applicationError, long errorCode,
                long frameType, String reason) { }
        public void handshakeDoneFrameReceived() { }
        public void datagramFrameReceived(ByteBuffer data, int encodedLength) { }
        public void frameError(String message) { }
    }

    private static byte[] b(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }

    private static byte[] cat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) {
            n += p.length;
        }
        byte[] out = new byte[n];
        int o = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, o, p.length);
            o += p.length;
        }
        return out;
    }

    private static void run(int which, byte[] input) {
        switch (which) {
            case PARSE_INVARIANTS:
                LongHeaderCodec.parseInvariants(input);
                break;
            case PARSE_PREFIX:
                LongHeaderCodec.parsePrefix(input);
                break;
            case PARSE_VN:
                VersionNegotiationPacket.parse(input);
                break;
            case PARSE_RETRY:
                LongHeaderCodec.parseRetry(input);
                break;
            case PARSE_FRAMES:
                new QuicFrameParser(new NullFrames()).receive(ByteBuffer.wrap(input));
                break;
            case VARINT:
                ByteBuffer buf = ByteBuffer.wrap(input);
                while (buf.hasRemaining()) {
                    VarInt.decode(buf);
                }
                break;
            default:
                break;
        }
    }

    private static void sweep(final int which, byte[] valid, Class<?>... allowed) {
        TruncationSweep.Target target = new TruncationSweep.Target() {
            @Override
            public void decode(byte[] input) {
                run(which, input);
            }
        };
        List<String> failures = TruncationSweep.allFailures(valid, target, allowed);
        assertTrue(failures.toString(), failures.isEmpty());
    }

    @Test
    public void longHeaderPrefixOnlyThrowsIllegalArgument() {
        byte[] initial = cat(b(0xc0, 0, 0, 0, 1, 4, 1, 2, 3, 4, 3, 5, 6, 7),
                b(0x02, 9, 9), b(0x40, 0x10), new byte[16]);
        sweep(PARSE_INVARIANTS, initial, IllegalArgumentException.class);
        sweep(PARSE_PREFIX, initial, IllegalArgumentException.class);
        byte[] hostileToken = b(0xc0, 0, 0, 0, 1, 0, 0, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff);
        sweep(PARSE_PREFIX, hostileToken, IllegalArgumentException.class);
    }

    @Test
    public void versionNegotiationOnlyThrowsIllegalArgument() {
        byte[] vn = b(0x80, 0, 0, 0, 0, 2, 1, 2, 2, 3, 4, 0, 0, 0, 1, 0x6b, 0x33, 0x43, 0xcf);
        sweep(PARSE_VN, vn, IllegalArgumentException.class);
    }

    @Test
    public void retryOnlyThrowsRuntimeExceptions() {
        byte[] retry = cat(b(0xf0, 0, 0, 0, 1, 2, 1, 2, 2, 3, 4), new byte[5], new byte[16]);
        sweep(PARSE_RETRY, retry, IllegalArgumentException.class);
    }

    @Test
    public void frameParserNeverThrows() {
        byte[] frames = cat(
                b(0x02, 5, 0, 1, 0, 1, 0),
                b(0x03, 5, 0, 0, 0, 1, 2, 3),
                b(0x04, 4, 1, 5),
                b(0x05, 4, 1),
                b(0x06, 0, 3, 'a', 'b', 'c'),
                b(0x07, 3, 'a', 'b', 'c'),
                b(0x0f, 4, 5, 3, 'a', 'b', 'c'),
                b(0x10, 5), b(0x11, 4, 5), b(0x12, 5), b(0x13, 5),
                b(0x14, 5), b(0x15, 4, 5), b(0x16, 5), b(0x17, 5),
                b(0x18, 1, 0, 4, 1, 2, 3, 4), new byte[16],
                b(0x19, 1),
                b(0x1a), new byte[8], b(0x1b), new byte[8],
                b(0x1c, 10, 0, 2, 'h', 'i'),
                b(0x1d, 10, 2, 'h', 'i'),
                b(0x1e),
                b(0x31, 3, 'a', 'b', 'c'),
                b(0x01));
        sweep(PARSE_FRAMES, frames);
    }

    @Test
    public void hostileFrameLengthsAreReportedNotThrown() {
        byte[][] hostile = {
            b(0x06, 0, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff),
            b(0x0e, 0, 0, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff),
            b(0x02, 0x3f, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0),
            b(0x02, 5, 0, 0x3f, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0),
            b(0x07, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff),
            b(0x30 | 1, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff),
            b(0x1c, 0, 0, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff),
            b(0xc0), b(0xff), b(0x80),
        };
        for (int i = 0; i < hostile.length; i++) {
            final byte[] input = hostile[i];
            TruncationSweep.Target target = new TruncationSweep.Target() {
                @Override
                public void decode(byte[] in) {
                    run(PARSE_FRAMES, in);
                }
            };
            List<String> failures = TruncationSweep.inputFailures(target, input, "hostile " + i);
            assertTrue(failures.toString(), failures.isEmpty());
        }
    }
}
