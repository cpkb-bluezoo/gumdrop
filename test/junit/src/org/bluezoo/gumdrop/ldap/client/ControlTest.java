/*
 * ControlTest.java
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

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Unit tests for Control (RFC 4511 section 4.1.11).
 */
public class ControlTest {

    @Test
    public void testControlWithoutValue() {
        Control ctrl = new Control("1.2.3.4", false);
        assertEquals("1.2.3.4", ctrl.getOID());
        assertFalse(ctrl.isCritical());
        assertNull(ctrl.getValue());
        assertFalse(ctrl.hasValue());
    }

    @Test
    public void testControlWithValue() {
        byte[] value = new byte[]{0x01, 0x02, 0x03};
        Control ctrl = new Control("1.2.3.4", true, value);
        assertEquals("1.2.3.4", ctrl.getOID());
        assertTrue(ctrl.isCritical());
        assertNotNull(ctrl.getValue());
        assertTrue(ctrl.hasValue());
        assertArrayEquals(new byte[]{0x01, 0x02, 0x03}, ctrl.getValue());
    }

    @Test
    public void testValueIsDefensivelyCopied() {
        byte[] value = new byte[]{0x0A};
        Control ctrl = new Control("1.2.3.4", false, value);
        value[0] = 0x0B;
        assertEquals(0x0A, ctrl.getValue()[0]);
    }

    @Test
    public void testGetValueReturnsCopy() {
        Control ctrl = new Control("1.2.3.4", false, new byte[]{0x0A});
        byte[] v1 = ctrl.getValue();
        v1[0] = 0x0B;
        assertEquals(0x0A, ctrl.getValue()[0]);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testNullOidThrows() {
        new Control(null, false);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testEmptyOidThrows() {
        new Control("", false);
    }

    @Test
    public void testWellKnownOIDs() {
        assertNotNull(Control.OID_PAGED_RESULTS);
        assertNotNull(Control.OID_SORT_REQUEST);
        assertNotNull(Control.OID_SORT_RESPONSE);
        assertNotNull(Control.OID_MANAGED_DSA_IT);
    }

    @Test
    public void testToString() {
        Control ctrl = new Control("1.2.3.4", true, new byte[5]);
        String s = ctrl.toString();
        assertTrue(s.contains("1.2.3.4"));
        assertTrue(s.contains("critical"));
        assertTrue(s.contains("5 bytes"));
    }

    @Test
    public void testToStringNonCriticalNoValue() {
        Control ctrl = new Control("1.2.3.4", false);
        String s = ctrl.toString();
        assertTrue(s.contains("1.2.3.4"));
        assertFalse(s.contains("critical"));
        assertFalse(s.contains("bytes"));
    }
}
