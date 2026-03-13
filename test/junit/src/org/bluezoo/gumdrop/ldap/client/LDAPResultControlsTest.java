/*
 * LDAPResultControlsTest.java
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

import java.util.Arrays;
import java.util.List;

/**
 * Unit tests for LDAPResult response controls (RFC 4511 section 4.1.11).
 */
public class LDAPResultControlsTest {

    @Test
    public void testDefaultNoControls() {
        LDAPResult result = new LDAPResult(LDAPResultCode.SUCCESS, "", "");
        assertFalse(result.hasControls());
        assertTrue(result.getControls().isEmpty());
    }

    @Test
    public void testSetControls() {
        LDAPResult result = new LDAPResult(LDAPResultCode.SUCCESS, "", "");
        List<Control> controls = Arrays.asList(
                new Control("1.2.3.4", false),
                new Control("5.6.7.8", true, new byte[]{0x01}));
        result.setControls(controls);

        assertTrue(result.hasControls());
        assertEquals(2, result.getControls().size());
        assertEquals("1.2.3.4", result.getControls().get(0).getOID());
        assertEquals("5.6.7.8", result.getControls().get(1).getOID());
        assertTrue(result.getControls().get(1).isCritical());
    }

    @Test
    public void testSetNullControlsClearsControls() {
        LDAPResult result = new LDAPResult(LDAPResultCode.SUCCESS, "", "");
        result.setControls(Arrays.asList(new Control("1.2.3.4", false)));
        assertTrue(result.hasControls());

        result.setControls(null);
        assertFalse(result.hasControls());
        assertTrue(result.getControls().isEmpty());
    }

    @Test(expected = UnsupportedOperationException.class)
    public void testControlsListIsUnmodifiable() {
        LDAPResult result = new LDAPResult(LDAPResultCode.SUCCESS, "", "");
        result.setControls(Arrays.asList(new Control("1.2.3.4", false)));
        result.getControls().add(new Control("9.9.9.9", false));
    }
}
