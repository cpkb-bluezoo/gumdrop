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
 * Unit tests for {@link ImapListener} capability and configuration.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class IMAPListenerTest {

    private ImapListener listener;

    @Before
    public void setUp() {
        listener = new ImapListener();
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

    @Test
    public void testCapabilitiesIncludeStatusSize() {
        String caps = listener.getCapabilities(true, true);
        assertTrue("STATUS=SIZE should be advertised (RFC 8438)",
                caps.contains("STATUS=SIZE"));
    }

    @Test
    public void testAuthenticatedCapabilitiesIncludeCompressDeflate() {
        String caps = listener.getCapabilities(true, true);
        assertTrue("COMPRESS=DEFLATE should appear when authenticated",
                caps.contains("COMPRESS=DEFLATE"));
    }

    @Test
    public void testUnauthenticatedCapabilitiesExcludeCompressDeflate() {
        String caps = listener.getCapabilities(false, true);
        assertFalse("COMPRESS=DEFLATE must not appear before auth",
                caps.contains("COMPRESS=DEFLATE"));
    }

    @Test
    public void testCapabilitiesExcludeCompressWhenActive() {
        String caps = listener.getCapabilities(true, true, true);
        assertFalse("COMPRESS=DEFLATE must not appear when compression active",
                caps.contains("COMPRESS=DEFLATE"));
    }

    @Test
    public void testCapabilitiesExcludeCompressWhenDisabled() {
        listener.setEnableCOMPRESS(false);
        String caps = listener.getCapabilities(true, true);
        assertFalse("COMPRESS=DEFLATE should not appear when disabled",
                caps.contains("COMPRESS=DEFLATE"));
    }

    @Test
    public void testAuthenticatedCapabilitiesIncludeUtf8Accept() {
        String caps = listener.getCapabilities(true, true);
        assertTrue("UTF8=ACCEPT should appear when authenticated",
                caps.contains("UTF8=ACCEPT"));
    }

    @Test
    public void testCapabilitiesExcludeUtf8AcceptWhenDisabled() {
        listener.setEnableUTF8ACCEPT(false);
        String caps = listener.getCapabilities(true, true);
        assertFalse("UTF8=ACCEPT should not appear when disabled",
                caps.contains("UTF8=ACCEPT"));
    }

    @Test
    public void testAuthenticatedCapabilitiesIncludeSortAndI18n() {
        String caps = listener.getCapabilities(true, true);
        assertTrue("SORT should appear when authenticated",
                caps.contains(" SORT"));
        assertTrue("I18NLEVEL=1 should appear with SORT",
                caps.contains("I18NLEVEL=1"));
    }

    @Test
    public void testCapabilitiesExcludeSortWhenDisabled() {
        listener.setEnableSORT(false);
        String caps = listener.getCapabilities(true, true);
        assertFalse("SORT should not appear when disabled",
                caps.contains(" SORT"));
        assertFalse("I18NLEVEL=1 should not appear when SORT disabled",
                caps.contains("I18NLEVEL=1"));
    }
}
