/*
 * DecoderSecurityTest.java
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

package org.bluezoo.gumdrop.http.hpack;

import org.bluezoo.gumdrop.http.Header;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.net.ProtocolException;

import static org.junit.Assert.fail;

/**
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DecoderSecurityTest {

    @Test(expected = ProtocolException.class)
    public void testOversizedLiteralRejected() throws Exception {
        Decoder decoder = new Decoder(4096, 8192);
        // Literal without indexing, new name, length prefix claims huge string
        ByteBuffer buf = ByteBuffer.wrap(new byte[] {
                0x00, (byte) 0x7f, (byte) 0xff, (byte) 0xff, (byte) 0x03
        });
        decoder.decode(buf, new HeaderHandler() {
            @Override
            public void header(Header header) {
            }
        });
    }
}
