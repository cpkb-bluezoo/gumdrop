/*
 * LdapDecoderSweepTest.java
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

package org.bluezoo.gumdrop.ldap.client;

import java.nio.ByteBuffer;
import java.util.List;

import org.bluezoo.gumdrop.ldap.asn1.Asn1Exception;
import org.bluezoo.gumdrop.ldap.asn1.BerDecoder;
import org.bluezoo.gumdrop.ldap.asn1.BerEncoder;
import org.bluezoo.gumdrop.testsupport.TruncationSweep;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * Truncation, corruption and nesting-bomb sweeps over the LDAP wire
 * decoding: the BER decoder ({@link Asn1Exception} only) and the client
 * protocol handler's response parsing (never throws: a malformed message
 * is reported through the handler and closes the connection).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class LdapDecoderSweepTest {

    private static byte[] bytes(BerEncoder e) {
        ByteBuffer b = e.toByteBuffer();
        byte[] out = new byte[b.remaining()];
        b.get(out);
        return out;
    }

    private static void assertNone(List<String> failures) {
        assertTrue(failures.toString(), failures.isEmpty());
    }

    private static void ldapResult(BerEncoder e, int code) {
        e.writeEnumerated(code);
        e.writeOctetString("dc=example");
        e.writeOctetString("diag");
        e.beginContext(3, true);
        e.writeOctetString("ldap://other/");
        e.endContext();
    }

    private static void controls(BerEncoder e) {
        e.beginContext(0, true);
        e.beginSequence();
        e.writeOctetString("1.2.3");
        e.writeBoolean(true);
        e.writeOctetString(new byte[] {9});
        e.endSequence();
        e.endContext();
    }

    private static byte[] resultMessage(int id, int appTag) {
        BerEncoder e = new BerEncoder();
        e.beginSequence();
        e.writeInteger(id);
        e.beginApplication(appTag, true);
        ldapResult(e, 0);
        e.writeContext(7, new byte[] {1, 2});
        e.endApplication();
        controls(e);
        e.endSequence();
        return bytes(e);
    }

    private static byte[] entryMessage() {
        BerEncoder e = new BerEncoder();
        e.beginSequence();
        e.writeInteger(1);
        e.beginApplication(4, true);
        e.writeOctetString("cn=x,dc=example");
        e.beginSequence();
        e.beginSequence();
        e.writeOctetString("cn");
        e.beginSet();
        e.writeOctetString("x");
        e.writeOctetString("y");
        e.endSet();
        e.endSequence();
        e.endSequence();
        e.endApplication();
        e.endSequence();
        return bytes(e);
    }

    private static byte[] referenceMessage() {
        BerEncoder e = new BerEncoder();
        e.beginSequence();
        e.writeInteger(1);
        e.beginApplication(19, true);
        e.writeOctetString("ldap://r1/");
        e.writeOctetString("ldap://r2/");
        e.endApplication();
        e.endSequence();
        return bytes(e);
    }

    private static byte[] extendedMessage(int id) {
        BerEncoder e = new BerEncoder();
        e.beginSequence();
        e.writeInteger(id);
        e.beginApplication(24, true);
        ldapResult(e, 0);
        e.writeContext(10, new byte[] {'1', '.', '2'});
        e.writeContext(11, new byte[] {1, 2, 3});
        e.endApplication();
        e.endSequence();
        return bytes(e);
    }

    private static byte[] intermediateMessage() {
        BerEncoder e = new BerEncoder();
        e.beginSequence();
        e.writeInteger(1);
        e.beginApplication(25, true);
        e.writeContext(0, new byte[] {'1', '.', '2'});
        e.writeContext(1, new byte[] {1, 2, 3});
        e.endApplication();
        e.endSequence();
        return bytes(e);
    }

    private static TruncationSweep.Target berTarget() {
        return new TruncationSweep.Target() {
            @Override
            public void decode(byte[] input) throws Exception {
                BerDecoder decoder = new BerDecoder(64);
                decoder.receive(ByteBuffer.wrap(input));
                while (decoder.next() != null) {
                    // drain
                }
            }
        };
    }

    private static TruncationSweep.Target clientTarget() {
        return new TruncationSweep.Target() {
            @Override
            public void decode(byte[] input) {
                LdapClientProtocolHandler protocol = new LdapClientProtocolHandler(
                        new LDAPClientProtocolHandlerTest.RecordingHandler(), false);
                protocol.connected(new LDAPClientProtocolHandlerTest.StubEndpoint());
                protocol.receive(ByteBuffer.wrap(input));
            }
        };
    }

    @Test
    public void berDecoderOnlyThrowsAsn1Exception() {
        assertNone(TruncationSweep.allFailures(entryMessage(), berTarget(),
                Asn1Exception.class));
        assertNone(TruncationSweep.allFailures(resultMessage(1, 1), berTarget(),
                Asn1Exception.class));
    }

    @Test
    public void berDecoderRejectsNestingBombs() {
        byte[] body = new byte[0];
        for (int i = 0; i < 100000; i++) {
            ByteBuffer b = ByteBuffer.allocate(body.length + 5);
            b.put((byte) 0x30).put((byte) 0x83);
            b.put((byte) (body.length >> 16)).put((byte) (body.length >> 8)).put((byte) body.length);
            b.put(body);
            body = b.array();
            if (body.length > 1000000) {
                break;
            }
        }
        assertNone(TruncationSweep.inputFailures(berTarget(), body, "nest",
                Asn1Exception.class));
    }

    @Test
    public void clientHandlerNeverThrowsOnMalformedResponses() {
        assertNone(TruncationSweep.allFailures(resultMessage(1, 1), clientTarget()));
        assertNone(TruncationSweep.allFailures(resultMessage(1, 5), clientTarget()));
        assertNone(TruncationSweep.allFailures(entryMessage(), clientTarget()));
        assertNone(TruncationSweep.allFailures(referenceMessage(), clientTarget()));
        assertNone(TruncationSweep.allFailures(extendedMessage(1), clientTarget()));
        assertNone(TruncationSweep.allFailures(extendedMessage(0), clientTarget()));
        assertNone(TruncationSweep.allFailures(intermediateMessage(), clientTarget()));
    }
}
