/*
 * GrpcCompressionTest.java
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

package org.bluezoo.gumdrop.grpc;

import java.util.Arrays;

import org.bluezoo.gumdrop.http.ContentEncoding.Coding;
import org.bluezoo.gumdrop.http.ContentEncoding.ContentEncodingException;
import org.bluezoo.gumdrop.http.ContentEncoding.SizeLimitException;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link GrpcCompression}: tokens, header values and failures.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class GrpcCompressionTest {

    @Test
    public void tokensNameTheAlgorithms() {
        assertEquals(Coding.GZIP, GrpcCompression.coding("gzip"));
        assertEquals(Coding.DEFLATE, GrpcCompression.coding(" Deflate "));
        assertEquals(Coding.BR, GrpcCompression.coding("br"));
        assertNull(GrpcCompression.coding("identity"));
        assertNull(GrpcCompression.coding("snappy"));
        assertNull(GrpcCompression.coding(null));
    }

    @Test
    public void identityIsNoCompression() {
        assertTrue(GrpcCompression.isIdentity(null));
        assertTrue(GrpcCompression.isIdentity(""));
        assertTrue(GrpcCompression.isIdentity("identity"));
        assertTrue(GrpcCompression.isIdentity("Identity"));
        assertFalse(GrpcCompression.isIdentity("gzip"));
        assertFalse(GrpcCompression.isIdentity("snappy"));
    }

    @Test
    public void acceptEncodingListsIdentityFirst() {
        assertEquals("identity", GrpcCompression.acceptEncoding());
        assertEquals("identity,gzip,deflate",
                GrpcCompression.acceptEncoding(GrpcCompression.DEFAULT_ENCODINGS));
        assertEquals("identity,br", GrpcCompression.acceptEncoding(Coding.BR));
    }

    @Test
    public void anAcceptEncodingValueIsParsedKeepingWhatIsKnown() {
        assertEquals(Arrays.asList(Coding.GZIP, Coding.BR),
                GrpcCompression.parseAcceptEncoding("identity, gzip,snappy,br,gzip"));
        assertTrue(GrpcCompression.parseAcceptEncoding(null).isEmpty());
        assertTrue(GrpcCompression.parseAcceptEncoding("").isEmpty());
    }

    @Test
    public void failuresAreGrpcStatuses() {
        assertEquals(GrpcStatus.RESOURCE_EXHAUSTED,
                GrpcCompression.failure(new SizeLimitException("big")).getStatus());
        assertEquals(GrpcStatus.INTERNAL,
                GrpcCompression.failure(new ContentEncodingException("bad")).getStatus());
    }
}
