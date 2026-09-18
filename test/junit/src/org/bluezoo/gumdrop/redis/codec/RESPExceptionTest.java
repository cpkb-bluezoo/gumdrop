/*
 * RESPExceptionTest.java
 * Copyright (C) 2025 Chris Burdess
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

package org.bluezoo.gumdrop.redis.codec;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Unit tests for {@link RespException}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class RESPExceptionTest {

    @Test
    public void testExceptionMessage() {
        RespException ex = new RespException("Test error message");
        assertEquals("Test error message", ex.getMessage());
    }

    @Test
    public void testExceptionWithCause() {
        IllegalArgumentException cause = new IllegalArgumentException("Original error");
        RespException ex = new RespException("Wrapper message", cause);

        assertEquals("Wrapper message", ex.getMessage());
        assertSame(cause, ex.getCause());
    }

    @Test
    public void testExceptionIsCheckedException() {
        // RespException should extend Exception (not RuntimeException)
        RespException ex = new RespException("Test");
        assertTrue(ex instanceof Exception);
        // Verify it's not a RuntimeException by checking its superclass
        assertFalse(RuntimeException.class.isAssignableFrom(RespException.class));
    }

    @Test
    public void testExceptionHasSerialVersionUID() {
        // Just verify it can be created without issues
        RespException ex = new RespException("Test");
        assertNotNull(ex);
    }

}

