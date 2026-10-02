/*
 * WebDAVRequestParserSplitTest.java
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

package org.bluezoo.gumdrop.webdav;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * Request bodies delivered in arbitrary chunks (as TCP segments or HTTP/2
 * DATA frames would) must parse identically to a single-buffer delivery,
 * including when the split falls inside the XML declaration, after a
 * byte order mark, or inside a multi-byte character. The callers' buffers
 * are only valid during the call, so the parser must retain the bytes the
 * underlying XML parser could not yet consume.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class WebDAVRequestParserSplitTest {

    private static final String DECL = "<?xml version=\"1.0\" encoding=\"utf-8\"?>";
    private static final String LOCK = "<D:lockinfo xmlns:D=\"DAV:\"><D:lockscope><D:exclusive/></D:lockscope>"
            + "<D:locktype><D:write/></D:locktype><D:owner>café €</D:owner></D:lockinfo>";
    private static final String PROPFIND = "<D:propfind xmlns:D=\"DAV:\"><D:prop><D:getetag/></D:prop></D:propfind>";

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] withBom(byte[] body) {
        byte[] out = new byte[body.length + 3];
        out[0] = (byte) 0xEF;
        out[1] = (byte) 0xBB;
        out[2] = (byte) 0xBF;
        System.arraycopy(body, 0, out, 3, body.length);
        return out;
    }

    /** Feeds the pieces through buffers that the parser must not assume survive the call. */
    private static WebDAVRequestParser feed(byte[] body, int[] cuts) throws IOException {
        WebDAVRequestParser p = new WebDAVRequestParser();
        int start = 0;
        for (int i = 0; i <= cuts.length; i++) {
            int end = i < cuts.length ? cuts[i] : body.length;
            byte[] piece = new byte[end - start];
            System.arraycopy(body, start, piece, 0, piece.length);
            ByteBuffer buf = ByteBuffer.wrap(piece);
            p.receive(buf);
            // Simulate the caller recycling the buffer after the callback.
            java.util.Arrays.fill(piece, (byte) 0);
            start = end;
        }
        p.close();
        return p;
    }

    private static void checkLock(WebDAVRequestParser p, String what) {
        assertNotNull(what, p.getLockRequest());
        assertEquals(what, "café €", p.getLockRequest().owner);
    }

    private static void checkPropfind(WebDAVRequestParser p, String what) {
        assertNotNull(what, p.getPropfindRequest());
        assertEquals(what, 1, p.getPropfindRequest().properties.size());
    }

    private void everySplit(byte[] body, boolean lock) throws IOException {
        for (int cut = 0; cut <= body.length; cut++) {
            WebDAVRequestParser p = feed(body, new int[] { cut });
            String what = "split at " + cut;
            if (lock) {
                checkLock(p, what);
            } else {
                checkPropfind(p, what);
            }
        }
        int[] all = new int[body.length - 1];
        for (int i = 0; i < all.length; i++) {
            all[i] = i + 1;
        }
        WebDAVRequestParser p = feed(body, all);
        if (lock) {
            checkLock(p, "1-byte chunks");
        } else {
            checkPropfind(p, "1-byte chunks");
        }
    }

    @Test
    public void declarationSplitAtEveryOffsetPropfind() throws IOException {
        everySplit(utf8(DECL + PROPFIND), false);
    }

    @Test
    public void declarationSplitAtEveryOffsetLockWithMultibyteText() throws IOException {
        everySplit(utf8(DECL + LOCK), true);
    }

    @Test
    public void noDeclarationSplitAtEveryOffset() throws IOException {
        everySplit(utf8(LOCK), true);
    }

    @Test
    public void byteOrderMarkSplitAtEveryOffset() throws IOException {
        everySplit(withBom(utf8(DECL + LOCK)), true);
        everySplit(withBom(utf8(PROPFIND)), false);
    }

    @Test
    public void parserIsReusableAfterReset() throws IOException {
        WebDAVRequestParser p = new WebDAVRequestParser();
        byte[] body = utf8(DECL + PROPFIND);
        p.receive(ByteBuffer.wrap(java.util.Arrays.copyOfRange(body, 0, 1)));
        p.reset();
        p.receive(ByteBuffer.wrap(body));
        p.close();
        checkPropfind(p, "after reset");
    }
}
