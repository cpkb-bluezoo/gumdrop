/*
 * DoQStreamHandlerTest.java
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

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link DoQStreamHandler} DoQ error codes.
 * RFC 9250 section 4.3.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DoQStreamHandlerTest {

    @Test
    public void testDoQErrorCodeValues() {
        assertEquals(0x0, DoQStreamHandler.DOQ_NO_ERROR);
        assertEquals(0x1, DoQStreamHandler.DOQ_INTERNAL_ERROR);
        assertEquals(0x2, DoQStreamHandler.DOQ_PROTOCOL_ERROR);
        assertEquals(0x3, DoQStreamHandler.DOQ_REQUEST_CANCELLED);
        assertEquals(0x4, DoQStreamHandler.DOQ_EXCESSIVE_LOAD);
    }

    @Test
    public void testDoQErrorCodesAreDistinct() {
        long[] codes = {
            DoQStreamHandler.DOQ_NO_ERROR,
            DoQStreamHandler.DOQ_INTERNAL_ERROR,
            DoQStreamHandler.DOQ_PROTOCOL_ERROR,
            DoQStreamHandler.DOQ_REQUEST_CANCELLED,
            DoQStreamHandler.DOQ_EXCESSIVE_LOAD
        };
        for (int i = 0; i < codes.length; i++) {
            for (int j = i + 1; j < codes.length; j++) {
                assertNotEquals("Error codes " + i + " and " + j
                        + " must be distinct", codes[i], codes[j]);
            }
        }
    }
}
