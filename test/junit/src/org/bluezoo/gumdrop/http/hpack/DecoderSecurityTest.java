/*
 * DecoderSecurityTest.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.http.hpack;

import org.bluezoo.gumdrop.http.Header;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.net.ProtocolException;

import static org.junit.Assert.fail;

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
