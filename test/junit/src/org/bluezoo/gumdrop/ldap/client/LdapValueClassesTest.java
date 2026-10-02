/*
 * LdapValueClassesTest.java
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

package org.bluezoo.gumdrop.ldap.client;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests for the value classes {@link Modification}, {@link SearchResultEntry}
 * and {@link LdapResult}.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class LdapValueClassesTest {

    private static String str(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }

    // ── Modification ──

    @Test
    public void modificationOperationValues() {
        assertEquals(0, Modification.Operation.ADD.getValue());
        assertEquals(1, Modification.Operation.DELETE.getValue());
        assertEquals(2, Modification.Operation.REPLACE.getValue());
        assertEquals(3, Modification.Operation.values().length);
        assertEquals(Modification.Operation.ADD,
                Modification.Operation.valueOf("ADD"));
    }

    @Test
    public void modificationBasicConstructors() {
        Modification m = new Modification(Modification.Operation.DELETE, "cn");
        assertEquals(Modification.Operation.DELETE, m.getOperation());
        assertEquals("cn", m.getAttributeName());
        assertTrue(m.getValues().isEmpty());

        List<byte[]> vals = new ArrayList<byte[]>();
        vals.add(new byte[] {1, 2});
        Modification m2 = new Modification(Modification.Operation.ADD, "x", vals);
        vals.clear();
        assertEquals(1, m2.getValues().size());
    }

    @Test
    public void modificationFactories() {
        Modification a = Modification.add("mail", "a@example.org");
        assertEquals(Modification.Operation.ADD, a.getOperation());
        assertEquals("a@example.org", str(a.getValues().get(0)));

        Modification d1 = Modification.delete("mail");
        assertEquals(Modification.Operation.DELETE, d1.getOperation());
        assertTrue(d1.getValues().isEmpty());

        Modification d2 = Modification.delete("mail", "b@example.org");
        assertEquals(1, d2.getValues().size());

        Modification r1 = Modification.replace("sn", "Smith");
        assertEquals(Modification.Operation.REPLACE, r1.getOperation());
        assertEquals("Smith", str(r1.getValues().get(0)));

        List<String> many = new ArrayList<String>();
        many.add("one");
        many.add("two");
        Modification r2 = Modification.replace("cn", many);
        assertEquals(2, r2.getValues().size());
        assertEquals("two", str(r2.getValues().get(1)));
    }

    // ── SearchResultEntry ──

    private SearchResultEntry entry() {
        Map<String, List<byte[]>> attrs = new LinkedHashMap<String, List<byte[]>>();
        List<byte[]> cn = new ArrayList<byte[]>();
        cn.add("Alice".getBytes(StandardCharsets.UTF_8));
        cn.add("Al".getBytes(StandardCharsets.UTF_8));
        attrs.put("CN", cn);
        List<byte[]> bin = new ArrayList<byte[]>();
        bin.add(new byte[] {0, 1, 2});
        attrs.put("jpegPhoto", bin);
        return new SearchResultEntry("cn=Alice,dc=example,dc=org", attrs);
    }

    @Test
    public void entryAttributeAccessIsCaseInsensitive() {
        SearchResultEntry e = entry();
        assertEquals("cn=Alice,dc=example,dc=org", e.getDN());
        assertTrue(e.hasAttribute("cn"));
        assertTrue(e.hasAttribute("CN"));
        assertFalse(e.hasAttribute("mail"));
        assertEquals(2, e.getAttributeNames().size());
        assertEquals(2, e.getAttributeValues("Cn").size());
        assertTrue(e.getAttributeValues("mail").isEmpty());
    }

    @Test
    public void entryStringValueAccessors() {
        SearchResultEntry e = entry();
        List<String> cn = e.getAttributeStringValues("cn");
        assertEquals(2, cn.size());
        assertEquals("Alice", cn.get(0));
        assertTrue(e.getAttributeStringValues("nope").isEmpty());
        assertEquals("Alice", e.getAttributeStringValue("cn"));
        assertNull(e.getAttributeStringValue("nope"));
        assertNull(e.getAttributeValue("nope"));
        assertEquals(3, e.getAttributeValue("jpegphoto").length);
    }

    @Test
    public void entryControls() {
        SearchResultEntry e = entry();
        assertFalse(e.hasControls());
        assertTrue(e.getControls().isEmpty());
        List<Control> controls = new ArrayList<Control>();
        controls.add(new Control("1.2.3", false, null));
        e.setControls(controls);
        assertTrue(e.hasControls());
        assertEquals(1, e.getControls().size());
        e.setControls(null);
        assertFalse(e.hasControls());
        e.setControls(new ArrayList<Control>());
        assertFalse(e.hasControls());
    }

    @Test
    public void entryToStringShowsPrintableAndBinaryValues() {
        String s = entry().toString();
        assertTrue(s.startsWith("dn: cn=Alice"));
        assertTrue(s.contains("cn: Alice"));
        assertTrue(s.contains("jpegphoto: [3 bytes]"));
    }

    // ── LdapResult ──

    @Test
    public void resultDefaultsAndAccessors() {
        LdapResult r = new LdapResult(LdapResultCode.SUCCESS, null, null);
        assertTrue(r.isSuccess());
        assertEquals(LdapResultCode.SUCCESS, r.getResultCode());
        assertEquals("", r.getMatchedDN());
        assertEquals("", r.getDiagnosticMessage());
        assertFalse(r.hasReferrals());
        assertTrue(r.getReferrals().isEmpty());
        assertFalse(r.hasControls());
        assertTrue(r.getControls().isEmpty());
        assertEquals("LdapResult[" + LdapResultCode.SUCCESS + "]", r.toString());
    }

    @Test
    public void resultWithReferralsAndControls() {
        List<String> refs = new ArrayList<String>();
        refs.add("ldap://other.example.org/");
        LdapResult r = new LdapResult(LdapResultCode.REFERRAL,
                "dc=example", "see elsewhere", refs);
        assertFalse(r.isSuccess());
        assertTrue(r.hasReferrals());
        assertEquals(1, r.getReferrals().size());
        List<Control> controls = new ArrayList<Control>();
        controls.add(new Control("1.2.3", true, new byte[] {1}));
        r.setControls(controls);
        assertTrue(r.hasControls());
        assertEquals(1, r.getControls().size());
        r.setControls(null);
        assertFalse(r.hasControls());
        String s = r.toString();
        assertTrue(s.contains("matchedDN=dc=example"));
        assertTrue(s.contains("message=see elsewhere"));
        assertTrue(s.contains("referrals="));
    }
}
