/*
 * SniCredentialsResolverTest.java
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

package org.bluezoo.gumdrop.util;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Tests for {@link SniCredentialsResolver}'s hostname-to-alias matching
 * (exact, then leftmost-label wildcard, then default, case-insensitive) --
 * ported verbatim from the former JSSE-specific {@code SNIKeyManager}, so
 * these cases mirror {@code SNIKeyManagerTest}'s coverage of that logic.
 * {@link SniCredentialsResolver#findAlias} is exercised directly since it
 * needs no real {@code KeyStore} to test the matching rules in isolation.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SniCredentialsResolverTest {

    @Test
    public void testExactHostnameMatch() {
        Map<String, String> mapping = new HashMap<>();
        mapping.put("example.com", "example-cert");
        mapping.put("other.com", "other-cert");

        SniCredentialsResolver resolver = new SniCredentialsResolver(null, null, mapping, "default");

        assertEquals("example-cert", resolver.findAlias("example.com"));
        assertEquals("other-cert", resolver.findAlias("other.com"));
    }

    @Test
    public void testWildcardMatch() {
        Map<String, String> mapping = new HashMap<>();
        mapping.put("*.example.com", "wildcard-cert");

        SniCredentialsResolver resolver = new SniCredentialsResolver(null, null, mapping, "default");

        assertEquals("wildcard-cert", resolver.findAlias("sub.example.com"));
    }

    @Test
    public void testExactMatchTakesPriorityOverWildcard() {
        Map<String, String> mapping = new HashMap<>();
        mapping.put("sub.example.com", "exact-cert");
        mapping.put("*.example.com", "wildcard-cert");

        SniCredentialsResolver resolver = new SniCredentialsResolver(null, null, mapping, "default");

        assertEquals("exact-cert", resolver.findAlias("sub.example.com"));
    }

    @Test
    public void testDefaultAliasUsedWhenNoMatch() {
        Map<String, String> mapping = new HashMap<>();
        mapping.put("example.com", "example-cert");

        SniCredentialsResolver resolver = new SniCredentialsResolver(null, null, mapping, "sni-default");

        assertEquals("sni-default", resolver.findAlias("unmatched.com"));
    }

    @Test
    public void testDefaultAliasUsedWhenNoSni() {
        Map<String, String> mapping = new HashMap<>();
        mapping.put("example.com", "example-cert");

        SniCredentialsResolver resolver = new SniCredentialsResolver(null, null, mapping, "sni-default");

        assertEquals("sni-default", resolver.findAlias(null));
    }

    @Test
    public void testNullAliasWhenNoMatchAndNoDefault() {
        Map<String, String> mapping = new HashMap<>();
        mapping.put("example.com", "example-cert");

        SniCredentialsResolver resolver = new SniCredentialsResolver(null, null, mapping, null);

        assertNull(resolver.findAlias("unmatched.com"));
    }

    @Test
    public void testMatchingIsCaseInsensitive() {
        Map<String, String> mapping = new HashMap<>();
        mapping.put("example.com", "example-cert");

        SniCredentialsResolver resolver = new SniCredentialsResolver(null, null, mapping, null);

        assertEquals("example-cert", resolver.findAlias("EXAMPLE.COM"));
    }
}
