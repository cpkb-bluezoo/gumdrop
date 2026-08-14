/*
 * RetryPacketTest.java
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

import java.net.InetAddress;
import java.security.SecureRandom;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit coverage for the Retry packet mechanism (RFC 9000 sections 8.1.2,
 * 17.2.5), independent of any real network I/O: wire-format round trip
 * ({@link LongHeaderCodec#buildRetryWithoutTag}/{@link LongHeaderCodec#parseRetry}),
 * the RFC 9001 section 5.8 fixed-key integrity tag ({@link RetryIntegrityTag}),
 * and gumdrop's own AEAD-sealed token scheme ({@link RetryToken}).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class RetryPacketTest {

    private static final SecureRandom RANDOM = new SecureRandom();

    private static byte[] randomConnectionId() {
        byte[] id = new byte[20];
        RANDOM.nextBytes(id);
        return id;
    }

    @Test
    public void testRetryPacketWireFormatRoundTrip() {
        byte[] dcid = randomConnectionId();
        byte[] scid = randomConnectionId();
        byte[] token = "opaque-retry-token".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

        byte[] withoutTag = LongHeaderCodec.buildRetryWithoutTag(dcid, scid, token);
        byte[] tag = RetryIntegrityTag.compute(dcid, withoutTag);
        byte[] packet = new byte[withoutTag.length + tag.length];
        System.arraycopy(withoutTag, 0, packet, 0, withoutTag.length);
        System.arraycopy(tag, 0, packet, withoutTag.length, tag.length);

        RetryPacket parsed = LongHeaderCodec.parseRetry(packet);
        assertArrayEquals(dcid, parsed.getDestinationConnectionId());
        assertArrayEquals(scid, parsed.getSourceConnectionId());
        assertArrayEquals(token, parsed.getRetryToken());
        assertArrayEquals(tag, parsed.getTag());
        assertArrayEquals(withoutTag, parsed.getPacketWithoutTag());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testParseRetryRejectsPacketTooShortForTag() {
        LongHeaderCodec.parseRetry(new byte[10]);
    }

    @Test
    public void testRetryIntegrityTagVerifiesCorrectPacket() {
        byte[] originalDcid = randomConnectionId();
        byte[] withoutTag = LongHeaderCodec.buildRetryWithoutTag(randomConnectionId(), randomConnectionId(),
                "token".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        byte[] tag = RetryIntegrityTag.compute(originalDcid, withoutTag);

        assertTrue(RetryIntegrityTag.verify(originalDcid, withoutTag, tag));
    }

    @Test
    public void testRetryIntegrityTagRejectsWrongOriginalDcid() {
        byte[] withoutTag = LongHeaderCodec.buildRetryWithoutTag(randomConnectionId(), randomConnectionId(),
                "token".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        byte[] tag = RetryIntegrityTag.compute(randomConnectionId(), withoutTag);

        assertFalse(RetryIntegrityTag.verify(randomConnectionId(), withoutTag, tag));
    }

    @Test
    public void testRetryIntegrityTagRejectsTamperedPacket() {
        byte[] originalDcid = randomConnectionId();
        byte[] withoutTag = LongHeaderCodec.buildRetryWithoutTag(randomConnectionId(), randomConnectionId(),
                "token".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        byte[] tag = RetryIntegrityTag.compute(originalDcid, withoutTag);
        withoutTag[withoutTag.length - 1] ^= 0x01;

        assertFalse(RetryIntegrityTag.verify(originalDcid, withoutTag, tag));
    }

    @Test
    public void testRetryTokenSealUnsealRoundTrip() throws Exception {
        byte[] key = new byte[32];
        RANDOM.nextBytes(key);
        byte[] originalDcid = randomConnectionId();
        InetAddress clientAddress = InetAddress.getLoopbackAddress();

        byte[] token = RetryToken.seal(key, originalDcid, clientAddress, System.currentTimeMillis());
        byte[] recovered = RetryToken.unseal(key, token, clientAddress, 30_000);

        assertNotNull(recovered);
        assertArrayEquals(originalDcid, recovered);
    }

    @Test
    public void testRetryTokenRejectsWrongKey() throws Exception {
        byte[] key = new byte[32];
        RANDOM.nextBytes(key);
        byte[] wrongKey = new byte[32];
        RANDOM.nextBytes(wrongKey);
        InetAddress clientAddress = InetAddress.getLoopbackAddress();

        byte[] token = RetryToken.seal(key, randomConnectionId(), clientAddress, System.currentTimeMillis());

        assertNull(RetryToken.unseal(wrongKey, token, clientAddress, 30_000));
    }

    @Test
    public void testRetryTokenRejectsMismatchedAddress() throws Exception {
        byte[] key = new byte[32];
        RANDOM.nextBytes(key);
        InetAddress sealedFor = InetAddress.getByName("127.0.0.1");
        InetAddress differentAddress = InetAddress.getByName("127.0.0.2");

        byte[] token = RetryToken.seal(key, randomConnectionId(), sealedFor, System.currentTimeMillis());

        assertNull(RetryToken.unseal(key, token, differentAddress, 30_000));
    }

    @Test
    public void testRetryTokenRejectsExpiredToken() throws Exception {
        byte[] key = new byte[32];
        RANDOM.nextBytes(key);
        InetAddress clientAddress = InetAddress.getLoopbackAddress();
        long issuedLongAgo = System.currentTimeMillis() - 60_000;

        byte[] token = RetryToken.seal(key, randomConnectionId(), clientAddress, issuedLongAgo);

        assertNull(RetryToken.unseal(key, token, clientAddress, 30_000));
    }

    @Test
    public void testRetryTokenRejectsGarbageBytes() throws Exception {
        byte[] key = new byte[32];
        RANDOM.nextBytes(key);
        InetAddress clientAddress = InetAddress.getLoopbackAddress();

        assertNull(RetryToken.unseal(key, "not a real token".getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                clientAddress, 30_000));
        assertNull(RetryToken.unseal(key, new byte[0], clientAddress, 30_000));
    }
}
