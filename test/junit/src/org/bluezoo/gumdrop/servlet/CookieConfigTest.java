/*
 * CookieConfigTest.java
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

package org.bluezoo.gumdrop.servlet;

import org.junit.Before;
import org.junit.Test;

import javax.servlet.SessionCookieConfig;

import static org.junit.Assert.*;

/**
 * Unit tests for CookieConfig.
 * 
 * Tests the SessionCookieConfig implementation including:
 * - Default values
 * - Getter/setter methods
 * - SameSite enum
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class CookieConfigTest {

    private CookieConfig config;

    @Before
    public void setUp() {
        config = new CookieConfig();
    }

    // ===== Default Value Tests =====

    @Test
    public void testDefaultName() {
        assertEquals("JSESSIONID", config.getName());
    }

    @Test
    public void testDefaultDomain() {
        assertNull(config.getDomain());
    }

    @Test
    public void testDefaultPath() {
        assertNull(config.getPath());
    }

    @Test
    public void testDefaultComment() {
        assertNull(config.getComment());
    }

    @Test
    public void testDefaultHttpOnly() {
        assertFalse(config.isHttpOnly());
    }

    @Test
    public void testDefaultSecure() {
        assertFalse(config.isSecure());
    }

    @Test
    public void testDefaultMaxAge() {
        assertEquals(-1, config.getMaxAge());
    }

    @Test
    public void testDefaultSameSite() {
        assertEquals(CookieConfig.SameSite.Lax, config.sameSite);
    }

    // ===== Name Tests =====

    @Test
    public void testSetName() {
        config.setName("CUSTOM_SESSION");
        assertEquals("CUSTOM_SESSION", config.getName());
    }

    @Test
    public void testSetNameNull() {
        config.setName(null);
        assertNull(config.getName());
    }

    @Test
    public void testSetNameEmpty() {
        config.setName("");
        assertEquals("", config.getName());
    }

    // ===== Domain Tests =====

    @Test
    public void testSetDomain() {
        config.setDomain("example.com");
        assertEquals("example.com", config.getDomain());
    }

    @Test
    public void testSetDomainWithLeadingDot() {
        config.setDomain(".example.com");
        assertEquals(".example.com", config.getDomain());
    }

    @Test
    public void testSetDomainNull() {
        config.setDomain("example.com");
        config.setDomain(null);
        assertNull(config.getDomain());
    }

    // ===== Path Tests =====

    @Test
    public void testSetPath() {
        config.setPath("/app");
        assertEquals("/app", config.getPath());
    }

    @Test
    public void testSetPathRoot() {
        config.setPath("/");
        assertEquals("/", config.getPath());
    }

    @Test
    public void testSetPathNull() {
        config.setPath("/app");
        config.setPath(null);
        assertNull(config.getPath());
    }

    // ===== Comment Tests =====

    @Test
    public void testSetComment() {
        config.setComment("Session cookie for authentication");
        assertEquals("Session cookie for authentication", config.getComment());
    }

    @Test
    public void testSetCommentNull() {
        config.setComment("test");
        config.setComment(null);
        assertNull(config.getComment());
    }

    // ===== HttpOnly Tests =====

    @Test
    public void testSetHttpOnlyTrue() {
        config.setHttpOnly(true);
        assertTrue(config.isHttpOnly());
    }

    @Test
    public void testSetHttpOnlyFalse() {
        config.setHttpOnly(true);
        config.setHttpOnly(false);
        assertFalse(config.isHttpOnly());
    }

    // ===== Secure Tests =====

    @Test
    public void testSetSecureTrue() {
        config.setSecure(true);
        assertTrue(config.isSecure());
    }

    @Test
    public void testSetSecureFalse() {
        config.setSecure(true);
        config.setSecure(false);
        assertFalse(config.isSecure());
    }

    // ===== MaxAge Tests =====

    @Test
    public void testSetMaxAgePositive() {
        config.setMaxAge(3600);
        assertEquals(3600, config.getMaxAge());
    }

    @Test
    public void testSetMaxAgeZero() {
        config.setMaxAge(0);
        assertEquals(0, config.getMaxAge());
    }

    @Test
    public void testSetMaxAgeNegative() {
        config.setMaxAge(-1);
        assertEquals(-1, config.getMaxAge());
    }

    @Test
    public void testSetMaxAgeLarge() {
        config.setMaxAge(Integer.MAX_VALUE);
        assertEquals(Integer.MAX_VALUE, config.getMaxAge());
    }

    // ===== SameSite Enum Tests =====

    @Test
    public void testSameSiteStrict() {
        config.sameSite = CookieConfig.SameSite.Strict;
        assertEquals(CookieConfig.SameSite.Strict, config.sameSite);
    }

    @Test
    public void testSameSiteLax() {
        config.sameSite = CookieConfig.SameSite.Lax;
        assertEquals(CookieConfig.SameSite.Lax, config.sameSite);
    }

    @Test
    public void testSameSiteNone() {
        config.sameSite = CookieConfig.SameSite.None;
        assertEquals(CookieConfig.SameSite.None, config.sameSite);
    }

    @Test
    public void testSameSiteEnumValues() {
        CookieConfig.SameSite[] values = CookieConfig.SameSite.values();
        assertEquals(3, values.length);
        assertEquals(CookieConfig.SameSite.Strict, values[0]);
        assertEquals(CookieConfig.SameSite.Lax, values[1]);
        assertEquals(CookieConfig.SameSite.None, values[2]);
    }

    @Test
    public void testSameSiteEnumValueOf() {
        assertEquals(CookieConfig.SameSite.Strict, CookieConfig.SameSite.valueOf("Strict"));
        assertEquals(CookieConfig.SameSite.Lax, CookieConfig.SameSite.valueOf("Lax"));
        assertEquals(CookieConfig.SameSite.None, CookieConfig.SameSite.valueOf("None"));
    }

    // ===== Interface Implementation Tests =====

    @Test
    public void testImplementsSessionCookieConfig() {
        assertTrue(config instanceof SessionCookieConfig);
    }

    // ===== Combined Configuration Tests =====

    @Test
    public void testFullConfiguration() {
        config.setName("APP_SESSION");
        config.setDomain(".example.com");
        config.setPath("/app");
        config.setComment("Application session");
        config.setHttpOnly(true);
        config.setSecure(true);
        config.setMaxAge(86400);
        config.sameSite = CookieConfig.SameSite.Strict;
        
        assertEquals("APP_SESSION", config.getName());
        assertEquals(".example.com", config.getDomain());
        assertEquals("/app", config.getPath());
        assertEquals("Application session", config.getComment());
        assertTrue(config.isHttpOnly());
        assertTrue(config.isSecure());
        assertEquals(86400, config.getMaxAge());
        assertEquals(CookieConfig.SameSite.Strict, config.sameSite);
    }

    @Test
    public void testProductionSecureConfiguration() {
        // Typical production configuration
        config.setName("JSESSIONID");
        config.setPath("/");
        config.setHttpOnly(true);
        config.setSecure(true);
        config.setMaxAge(-1); // Session cookie (browser closes)
        config.sameSite = CookieConfig.SameSite.Strict;
        
        assertTrue(config.isHttpOnly());
        assertTrue(config.isSecure());
        assertEquals(CookieConfig.SameSite.Strict, config.sameSite);
    }

    // ===== Direct Field Access Tests =====

    @Test
    public void testDirectFieldAccess() {
        config.name = "DIRECT_NAME";
        config.domain = "direct.example.com";
        config.path = "/direct";
        config.comment = "Direct comment";
        config.httpOnly = true;
        config.secure = true;
        config.maxAge = 1000;
        config.sameSite = CookieConfig.SameSite.None;
        
        assertEquals("DIRECT_NAME", config.name);
        assertEquals("direct.example.com", config.domain);
        assertEquals("/direct", config.path);
        assertEquals("Direct comment", config.comment);
        assertTrue(config.httpOnly);
        assertTrue(config.secure);
        assertEquals(1000, config.maxAge);
        assertEquals(CookieConfig.SameSite.None, config.sameSite);
    }

}

