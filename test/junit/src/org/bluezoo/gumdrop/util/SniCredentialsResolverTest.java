/*
 * SniCredentialsResolverTest.java
 * Copyright (C) 2026 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
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
