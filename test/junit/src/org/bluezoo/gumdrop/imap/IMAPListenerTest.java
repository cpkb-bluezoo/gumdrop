/*
 * IMAPListenerTest.java
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

package org.bluezoo.gumdrop.imap;

import java.util.HashMap;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link IMAPListener} capability and configuration.
 */
public class IMAPListenerTest {

    private IMAPListener listener;

    @Before
    public void setUp() {
        listener = new IMAPListener();
    }

    @Test
    public void testCapabilitiesIncludeLiteralMinus() {
        String caps = listener.getCapabilities(true, true);
        assertTrue("LITERAL- should be advertised",
                caps.contains("LITERAL-"));
    }

    @Test
    public void testCapabilitiesIncludeId() {
        String caps = listener.getCapabilities(true, true);
        assertTrue("ID should be advertised",
                caps.contains(" ID"));
    }

    @Test
    public void testCapabilitiesIncludeQuota() {
        String caps = listener.getCapabilities(true, true);
        assertTrue("QUOTA should be advertised when enabled",
                caps.contains("QUOTA"));
    }

    @Test
    public void testCapabilitiesExcludeQuotaWhenDisabled() {
        listener.setEnableQUOTA(false);
        String caps = listener.getCapabilities(true, true);
        assertFalse("QUOTA should not be advertised when disabled",
                caps.contains("QUOTA"));
    }

    @Test
    public void testServerIdFieldsDefault() {
        assertNull("Default serverIdFields should be null",
                listener.getServerIdFields());
    }

    @Test
    public void testServerIdFieldsCustom() {
        Map<String, String> fields = new HashMap<>();
        fields.put("name", "TestServer");
        fields.put("version", "2.0");
        fields.put("vendor", "Test Corp");
        listener.setServerIdFields(fields);

        Map<String, String> result = listener.getServerIdFields();
        assertNotNull(result);
        assertEquals("TestServer", result.get("name"));
        assertEquals("2.0", result.get("version"));
        assertEquals("Test Corp", result.get("vendor"));
    }

    @Test
    public void testUnauthenticatedCapabilitiesExcludeIdle() {
        String caps = listener.getCapabilities(false, true);
        assertFalse("IDLE should not appear when unauthenticated",
                caps.contains("IDLE"));
    }

    @Test
    public void testAuthenticatedCapabilitiesIncludeIdle() {
        String caps = listener.getCapabilities(true, true);
        assertTrue("IDLE should appear when authenticated",
                caps.contains("IDLE"));
    }

    @Test
    public void testCapabilitiesIncludeNamespace() {
        String caps = listener.getCapabilities(true, true);
        assertTrue("NAMESPACE should be advertised",
                caps.contains("NAMESPACE"));
    }

    @Test
    public void testCapabilitiesExcludeNamespaceWhenDisabled() {
        listener.setEnableNAMESPACE(false);
        String caps = listener.getCapabilities(true, true);
        assertFalse("NAMESPACE should not appear when disabled",
                caps.contains("NAMESPACE"));
    }
}
