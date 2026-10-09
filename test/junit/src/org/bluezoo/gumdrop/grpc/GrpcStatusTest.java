/*
 * GrpcStatusTest.java
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

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * The gRPC status codes and the exception that carries one.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class GrpcStatusTest {

    @Test
    public void codesMatchTheGrpcSpecification() {
        assertEquals(0, GrpcStatus.OK);
        assertEquals(1, GrpcStatus.CANCELLED);
        assertEquals(2, GrpcStatus.UNKNOWN);
        assertEquals(3, GrpcStatus.INVALID_ARGUMENT);
        assertEquals(4, GrpcStatus.DEADLINE_EXCEEDED);
        assertEquals(5, GrpcStatus.NOT_FOUND);
        assertEquals(6, GrpcStatus.ALREADY_EXISTS);
        assertEquals(7, GrpcStatus.PERMISSION_DENIED);
        assertEquals(8, GrpcStatus.RESOURCE_EXHAUSTED);
        assertEquals(9, GrpcStatus.FAILED_PRECONDITION);
        assertEquals(10, GrpcStatus.ABORTED);
        assertEquals(11, GrpcStatus.OUT_OF_RANGE);
        assertEquals(12, GrpcStatus.UNIMPLEMENTED);
        assertEquals(13, GrpcStatus.INTERNAL);
        assertEquals(14, GrpcStatus.UNAVAILABLE);
        assertEquals(15, GrpcStatus.DATA_LOSS);
        assertEquals(16, GrpcStatus.UNAUTHENTICATED);
    }

    @Test
    public void namesAreTheSpecificationNames() {
        assertEquals("OK", GrpcStatus.name(0));
        assertEquals("UNIMPLEMENTED", GrpcStatus.name(12));
        assertEquals("UNAUTHENTICATED", GrpcStatus.name(16));
        assertNull(GrpcStatus.name(17));
        assertNull(GrpcStatus.name(-1));
    }

    @Test
    public void exceptionCarriesItsStatus() {
        GrpcException e = new GrpcException(GrpcStatus.NOT_FOUND, "no such user");
        assertEquals(GrpcStatus.NOT_FOUND, e.getStatus());
        assertTrue(e.getMessage().contains("no such user"));
        assertTrue(e.getMessage().contains("NOT_FOUND"));
    }

    @Test
    public void exceptionKeepsItsCause() {
        Exception cause = new java.io.IOException("reset");
        GrpcException e = new GrpcException(GrpcStatus.UNAVAILABLE, "transport failed", cause);
        assertEquals(GrpcStatus.UNAVAILABLE, e.getStatus());
        assertSame(cause, e.getCause());
    }
}
