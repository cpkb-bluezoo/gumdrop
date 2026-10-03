/*
 * TlsMessageSweepTest.java
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

package org.bluezoo.gumdrop.tls;

import java.io.ByteArrayOutputStream;
import java.util.List;

import org.bluezoo.gumdrop.testsupport.TruncationSweep;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Truncation and corruption sweeps over TLS/DTLS parsers that read network
 * bytes outside the checked {@code WireReader} path: the DTLS ClientHello
 * peek used by the stateless cookie exchange.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class TlsMessageSweepTest {

    private static void u(ByteArrayOutputStream out, int bytes, int value) {
        for (int i = bytes - 1; i >= 0; i--) {
            out.write(value >> (8 * i));
        }
    }

    /** An epoch-0 DTLS 1.2 ClientHello record with an empty cookie. */
    private static byte[] clientHelloDatagram() {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        u(body, 2, 0xfefd);
        for (int i = 0; i < 32; i++) {
            body.write(i);
        }
        body.write(0);
        body.write(0);
        u(body, 2, 2);
        u(body, 2, 0xc02b);
        body.write(1);
        body.write(0);
        byte[] b = body.toByteArray();
        ByteArrayOutputStream fragment = new ByteArrayOutputStream();
        fragment.write(1);
        u(fragment, 3, b.length);
        u(fragment, 2, 0);
        u(fragment, 3, 0);
        u(fragment, 3, b.length);
        fragment.write(b, 0, b.length);
        byte[] f = fragment.toByteArray();
        ByteArrayOutputStream record = new ByteArrayOutputStream();
        record.write(22);
        u(record, 2, 0xfefd);
        u(record, 2, 0);
        u(record, 6, 0);
        u(record, 2, f.length);
        record.write(f, 0, f.length);
        return record.toByteArray();
    }

    @Test
    public void dtlsClientHelloPeekNeverThrows() {
        byte[] valid = clientHelloDatagram();
        assertNotNull(Dtls12HelloVerify.parseClientHelloFields(valid));
        TruncationSweep.Target target = new TruncationSweep.Target() {
            @Override
            public void decode(byte[] input) {
                Dtls12HelloVerify.parseClientHelloFields(input);
            }
        };
        List<String> failures = TruncationSweep.allFailures(valid, target);
        assertTrue(failures.toString(), failures.isEmpty());
        // a long datagram whose record header declares a zero-length fragment
        byte[] empty = new byte[valid.length];
        System.arraycopy(valid, 0, empty, 0, 13);
        empty[11] = 0;
        empty[12] = 0;
        failures = TruncationSweep.inputFailures(target, empty, "empty fragment");
        assertTrue(failures.toString(), failures.isEmpty());
    }
}
