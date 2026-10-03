/*
 * DnsWireSweepTest.java
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

package org.bluezoo.gumdrop.dns;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.bluezoo.gumdrop.testsupport.TruncationSweep;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * Truncation and corruption sweeps over the DNS wire decoders: the message
 * parser (header, questions, compression pointers) and every record type's
 * RDATA accessors. A malformed or hostile peer must only ever provoke
 * {@link DnsFormatException} from the message parser and
 * {@link IllegalStateException} from the RDATA accessors.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DnsWireSweepTest {

    private static void assertNoFailures(List<String> failures) {
        assertTrue(failures.toString(), failures.isEmpty());
    }

    private static byte[] u16(int... values) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int v : values) {
            out.write(v >> 8);
            out.write(v);
        }
        return out.toByteArray();
    }

    private static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            out.write(p, 0, p.length);
        }
        return out.toByteArray();
    }

    private static byte[] name(String n) {
        return DnsMessage.encodeName(n);
    }

    private static byte[] bytes(int... values) {
        byte[] b = new byte[values.length];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) values[i];
        }
        return b;
    }

    @Test
    public void messageParserOnlyThrowsFormatException() throws Exception {
        List<DnsResourceRecord> answers = new ArrayList<DnsResourceRecord>();
        answers.add(DnsResourceRecord.a("a.example.com", 60,
                InetAddress.getByAddress(bytes(1, 2, 3, 4))));
        answers.add(DnsResourceRecord.mx("example.com", 60, 10, "mail.example.com"));
        answers.add(DnsResourceRecord.txt("example.com", 60, "hello"));
        answers.add(DnsResourceRecord.opt(1232));
        List<DnsQuestion> questions = new ArrayList<DnsQuestion>();
        questions.add(new DnsQuestion("example.com", DnsType.MX, DnsClass.IN));
        DnsMessage message = new DnsMessage(7, 0x8180, questions, answers,
                Collections.<DnsResourceRecord>emptyList(),
                Collections.<DnsResourceRecord>emptyList());
        ByteBuffer wire = message.serialize();
        byte[] valid = new byte[wire.remaining()];
        wire.get(valid);
        TruncationSweep.Target target = new TruncationSweep.Target() {
            @Override
            public void decode(byte[] input) throws Exception {
                DnsMessage.parse(ByteBuffer.wrap(input));
            }
        };
        assertNoFailures(TruncationSweep.allFailures(valid, target,
                DnsFormatException.class));
    }

    @Test
    public void selfReferencingCompressionPointerIsRejected() {
        byte[] header = u16(1, 0, 1, 0, 0, 0);
        byte[] question = cat(bytes(0xC0, 12), u16(1, 1));
        final byte[] msg = cat(header, question);
        TruncationSweep.Target target = new TruncationSweep.Target() {
            @Override
            public void decode(byte[] input) throws Exception {
                DnsMessage.parse(ByteBuffer.wrap(input));
            }
        };
        assertNoFailures(TruncationSweep.inputFailures(target, msg, "loop",
                DnsFormatException.class));
    }

    @Test
    public void maximalCountsWithNoBodyAreRejected() {
        final byte[] msg = u16(1, 0, 0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF);
        TruncationSweep.Target target = new TruncationSweep.Target() {
            @Override
            public void decode(byte[] input) throws Exception {
                DnsMessage.parse(ByteBuffer.wrap(input));
            }
        };
        assertNoFailures(TruncationSweep.inputFailures(target, msg, "counts",
                DnsFormatException.class));
    }

    /** Calls every public no-argument accessor of the record. */
    private static void invokeAccessors(DnsResourceRecord rr) throws Exception {
        for (Method m : DnsResourceRecord.class.getMethods()) {
            if (Modifier.isStatic(m.getModifiers()) || m.getParameterTypes().length != 0
                    || m.getDeclaringClass() != DnsResourceRecord.class) {
                continue;
            }
            try {
                m.invoke(rr);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                if (cause instanceof IllegalStateException) {
                    // documented: wrong record type or malformed RDATA
                    continue;
                }
                if (cause instanceof Exception) {
                    throw (Exception) cause;
                }
                throw (Error) cause;
            }
        }
    }

    private static void sweepRdata(final DnsType type, byte[] validRdata) {
        TruncationSweep.Target target = new TruncationSweep.Target() {
            @Override
            public void decode(byte[] input) throws Exception {
                DnsResourceRecord rr = new DnsResourceRecord("x.example", type,
                        DnsClass.IN, 0, input);
                invokeAccessors(rr);
            }
        };
        assertNoFailures(TruncationSweep.allFailures(validRdata, target,
                IllegalStateException.class));
        assertNoFailures(TruncationSweep.inputFailures(target, new byte[0],
                "empty", IllegalStateException.class));
    }

    @Test
    public void mxRdata() {
        sweepRdata(DnsType.MX, cat(u16(10), name("mail.example.com")));
    }

    @Test
    public void srvRdata() {
        sweepRdata(DnsType.SRV, cat(u16(1, 2, 443), name("t.example.com")));
    }

    @Test
    public void txtRdata() {
        sweepRdata(DnsType.TXT, bytes(3, 'a', 'b', 'c', 2, 'd', 'e'));
    }

    @Test
    public void addressAndNameRdata() {
        sweepRdata(DnsType.A, bytes(1, 2, 3, 4));
        sweepRdata(DnsType.AAAA, new byte[16]);
        sweepRdata(DnsType.CNAME, name("c.example.com"));
        sweepRdata(DnsType.NS, name("ns.example.com"));
        sweepRdata(DnsType.PTR, name("p.example.com"));
    }

    @Test
    public void soaRdata() {
        sweepRdata(DnsType.SOA, cat(name("ns.example.com"), name("host.example.com"),
                new byte[20]));
    }

    @Test
    public void svcbRdata() {
        byte[] params = cat(u16(1, 6), bytes(2, 'h', '3', 2, 'h', '2'),
                u16(3, 2, 443), u16(5, 3), bytes(1, 2, 3));
        byte[] rdata = cat(u16(1), name("svc.example.com"), params);
        sweepRdata(DnsType.SVCB, rdata);
        sweepRdata(DnsType.HTTPS, rdata);
    }

    @Test
    public void dnssecRdata() {
        byte[] rrsig = cat(u16(1), bytes(8, 2), new byte[12], u16(4660),
                name("example.com"), new byte[8]);
        sweepRdata(DnsType.RRSIG, rrsig);
        sweepRdata(DnsType.DNSKEY, cat(u16(257), bytes(3, 8), new byte[8]));
        sweepRdata(DnsType.DS, cat(u16(4660), bytes(8, 2), new byte[8]));
        sweepRdata(DnsType.NSEC, cat(name("next.example.com"),
                bytes(0, 2, 0x40, 0x01)));
    }

    @Test
    public void nsec3Rdata() {
        byte[] nsec3 = cat(bytes(1, 0), u16(10), bytes(2, 0xAA, 0xBB),
                bytes(4, 1, 2, 3, 4), bytes(0, 1, 0x40));
        sweepRdata(DnsType.NSEC3, nsec3);
        sweepRdata(DnsType.NSEC3PARAM, cat(bytes(1, 0), u16(10), bytes(2, 0xAA, 0xBB)));
    }

    @Test
    public void tlsaAndOptRdata() {
        sweepRdata(DnsType.TLSA, cat(bytes(3, 1, 1), new byte[8]));
        sweepRdata(DnsType.OPT, cat(u16(10, 8), new byte[8], u16(65001, 2), bytes(1, 2)));
    }
}
