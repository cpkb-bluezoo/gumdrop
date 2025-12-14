/*
 * SearchRequestTest.java
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

import org.junit.Test;
import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.List;

/**
 * Unit tests for SearchRequest.
 */
public class SearchRequestTest {

    // Test default values

    @Test
    public void testDefaultValues() {
        SearchRequest request = new SearchRequest();
        
        assertEquals("", request.getBaseDN());
        assertEquals(SearchScope.SUBTREE, request.getScope());
        assertEquals(DerefAliases.NEVER, request.getDerefAliases());
        assertEquals(0, request.getSizeLimit());
        assertEquals(0, request.getTimeLimit());
        assertFalse(request.isTypesOnly());
        assertEquals("(objectClass=*)", request.getFilter());
        assertTrue(request.getAttributes().isEmpty());
    }

    // Test setBaseDN

    @Test
    public void testSetBaseDN() {
        SearchRequest request = new SearchRequest();
        request.setBaseDN("dc=example,dc=com");
        assertEquals("dc=example,dc=com", request.getBaseDN());
    }

    @Test
    public void testSetBaseDNNull() {
        SearchRequest request = new SearchRequest();
        request.setBaseDN("dc=example,dc=com");
        request.setBaseDN(null);
        assertEquals("", request.getBaseDN());
    }

    // Test setScope

    @Test
    public void testSetScope() {
        SearchRequest request = new SearchRequest();
        
        request.setScope(SearchScope.BASE);
        assertEquals(SearchScope.BASE, request.getScope());
        
        request.setScope(SearchScope.ONE);
        assertEquals(SearchScope.ONE, request.getScope());
        
        request.setScope(SearchScope.SUBTREE);
        assertEquals(SearchScope.SUBTREE, request.getScope());
    }

    @Test
    public void testSetScopeNull() {
        SearchRequest request = new SearchRequest();
        request.setScope(SearchScope.BASE);
        request.setScope(null);
        assertEquals(SearchScope.SUBTREE, request.getScope());
    }

    // Test setDerefAliases

    @Test
    public void testSetDerefAliases() {
        SearchRequest request = new SearchRequest();
        
        request.setDerefAliases(DerefAliases.IN_SEARCHING);
        assertEquals(DerefAliases.IN_SEARCHING, request.getDerefAliases());
        
        request.setDerefAliases(DerefAliases.FINDING_BASE_OBJ);
        assertEquals(DerefAliases.FINDING_BASE_OBJ, request.getDerefAliases());
        
        request.setDerefAliases(DerefAliases.ALWAYS);
        assertEquals(DerefAliases.ALWAYS, request.getDerefAliases());
    }

    @Test
    public void testSetDerefAliasesNull() {
        SearchRequest request = new SearchRequest();
        request.setDerefAliases(DerefAliases.ALWAYS);
        request.setDerefAliases(null);
        assertEquals(DerefAliases.NEVER, request.getDerefAliases());
    }

    // Test setSizeLimit

    @Test
    public void testSetSizeLimit() {
        SearchRequest request = new SearchRequest();
        request.setSizeLimit(100);
        assertEquals(100, request.getSizeLimit());
    }

    @Test
    public void testSetSizeLimitZero() {
        SearchRequest request = new SearchRequest();
        request.setSizeLimit(0);
        assertEquals(0, request.getSizeLimit());
    }

    @Test
    public void testSetSizeLimitNegative() {
        SearchRequest request = new SearchRequest();
        request.setSizeLimit(-10);
        assertEquals(0, request.getSizeLimit());
    }

    // Test setTimeLimit

    @Test
    public void testSetTimeLimit() {
        SearchRequest request = new SearchRequest();
        request.setTimeLimit(30);
        assertEquals(30, request.getTimeLimit());
    }

    @Test
    public void testSetTimeLimitZero() {
        SearchRequest request = new SearchRequest();
        request.setTimeLimit(0);
        assertEquals(0, request.getTimeLimit());
    }

    @Test
    public void testSetTimeLimitNegative() {
        SearchRequest request = new SearchRequest();
        request.setTimeLimit(-5);
        assertEquals(0, request.getTimeLimit());
    }

    // Test setTypesOnly

    @Test
    public void testSetTypesOnly() {
        SearchRequest request = new SearchRequest();
        assertFalse(request.isTypesOnly());
        
        request.setTypesOnly(true);
        assertTrue(request.isTypesOnly());
        
        request.setTypesOnly(false);
        assertFalse(request.isTypesOnly());
    }

    // Test setFilter

    @Test
    public void testSetFilter() {
        SearchRequest request = new SearchRequest();
        request.setFilter("(uid=jdoe)");
        assertEquals("(uid=jdoe)", request.getFilter());
    }

    @Test
    public void testSetFilterComplex() {
        SearchRequest request = new SearchRequest();
        request.setFilter("(&(objectClass=person)(|(uid=jdoe)(uid=jsmith)))");
        assertEquals("(&(objectClass=person)(|(uid=jdoe)(uid=jsmith)))", request.getFilter());
    }

    @Test
    public void testSetFilterNull() {
        SearchRequest request = new SearchRequest();
        request.setFilter("(uid=jdoe)");
        request.setFilter(null);
        assertEquals("(objectClass=*)", request.getFilter());
    }

    // Test setAttributes (varargs)

    @Test
    public void testSetAttributesVarargs() {
        SearchRequest request = new SearchRequest();
        request.setAttributes("cn", "mail", "memberOf");
        
        List<String> attrs = request.getAttributes();
        assertEquals(3, attrs.size());
        assertTrue(attrs.contains("cn"));
        assertTrue(attrs.contains("mail"));
        assertTrue(attrs.contains("memberOf"));
    }

    @Test
    public void testSetAttributesVarargsEmpty() {
        SearchRequest request = new SearchRequest();
        request.setAttributes("cn");
        request.setAttributes();
        assertTrue(request.getAttributes().isEmpty());
    }

    @Test
    public void testSetAttributesVarargsNull() {
        SearchRequest request = new SearchRequest();
        request.setAttributes("cn");
        request.setAttributes((String[]) null);
        assertTrue(request.getAttributes().isEmpty());
    }

    // Test setAttributes (List)

    @Test
    public void testSetAttributesList() {
        SearchRequest request = new SearchRequest();
        request.setAttributes(Arrays.asList("cn", "sn", "givenName"));
        
        List<String> attrs = request.getAttributes();
        assertEquals(3, attrs.size());
        assertTrue(attrs.contains("sn"));
    }

    @Test
    public void testSetAttributesListEmpty() {
        SearchRequest request = new SearchRequest();
        request.setAttributes("cn");
        request.setAttributes(Arrays.asList());
        assertTrue(request.getAttributes().isEmpty());
    }

    @Test
    public void testSetAttributesListNull() {
        SearchRequest request = new SearchRequest();
        request.setAttributes("cn");
        request.setAttributes((List<String>) null);
        assertTrue(request.getAttributes().isEmpty());
    }

    // Test attributes list immutability

    @Test(expected = UnsupportedOperationException.class)
    public void testAttributesListImmutable() {
        SearchRequest request = new SearchRequest();
        request.setAttributes("cn", "mail");
        request.getAttributes().add("sn");
    }

    // Test toString

    @Test
    public void testToStringBasic() {
        SearchRequest request = new SearchRequest();
        request.setBaseDN("dc=example,dc=com");
        request.setFilter("(uid=jdoe)");
        
        String str = request.toString();
        assertTrue(str.contains("dc=example,dc=com"));
        assertTrue(str.contains("(uid=jdoe)"));
        assertTrue(str.contains("SUBTREE"));
    }

    @Test
    public void testToStringWithAttributes() {
        SearchRequest request = new SearchRequest();
        request.setAttributes("cn", "mail");
        
        String str = request.toString();
        assertTrue(str.contains("cn"));
        assertTrue(str.contains("mail"));
    }

    @Test
    public void testToStringWithLimits() {
        SearchRequest request = new SearchRequest();
        request.setSizeLimit(100);
        request.setTimeLimit(30);
        
        String str = request.toString();
        assertTrue(str.contains("sizeLimit"));
        assertTrue(str.contains("100"));
        assertTrue(str.contains("timeLimit"));
        assertTrue(str.contains("30"));
    }

    // Test typical use case

    @Test
    public void testTypicalUserSearch() {
        SearchRequest request = new SearchRequest();
        request.setBaseDN("ou=users,dc=example,dc=com");
        request.setScope(SearchScope.SUBTREE);
        request.setFilter("(&(objectClass=person)(uid=jdoe))");
        request.setAttributes("cn", "mail", "memberOf", "telephoneNumber");
        request.setSizeLimit(10);
        request.setTimeLimit(30);
        
        assertEquals("ou=users,dc=example,dc=com", request.getBaseDN());
        assertEquals(SearchScope.SUBTREE, request.getScope());
        assertEquals("(&(objectClass=person)(uid=jdoe))", request.getFilter());
        assertEquals(4, request.getAttributes().size());
        assertEquals(10, request.getSizeLimit());
        assertEquals(30, request.getTimeLimit());
    }
}

