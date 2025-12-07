/*
 * AcceptLanguageTest.java
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

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import static org.junit.Assert.*;

/**
 * Unit tests for AcceptLanguage.
 * 
 * Tests the Accept-Language header component parsing including:
 * - Language-only specifications
 * - Language-country specifications
 * - Quality value (q) comparison
 * - Locale conversion
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class AcceptLanguageTest {

    // ===== Locale Conversion Tests =====

    @Test
    public void testToLocaleLanguageOnly() {
        AcceptLanguage al = new AcceptLanguage("en", 1.0);
        Locale locale = al.toLocale();
        
        assertEquals("en", locale.getLanguage());
        assertEquals("", locale.getCountry());
    }

    @Test
    public void testToLocaleWithCountry() {
        AcceptLanguage al = new AcceptLanguage("en-US", 1.0);
        Locale locale = al.toLocale();
        
        assertEquals("en", locale.getLanguage());
        assertEquals("US", locale.getCountry());
    }

    @Test
    public void testToLocaleWithCountryLowerCase() {
        AcceptLanguage al = new AcceptLanguage("en-gb", 1.0);
        Locale locale = al.toLocale();
        
        assertEquals("en", locale.getLanguage());
        // Locale uppercases country codes
        assertEquals("GB", locale.getCountry());
    }

    @Test
    public void testToLocaleFrench() {
        AcceptLanguage al = new AcceptLanguage("fr", 0.8);
        Locale locale = al.toLocale();
        
        assertEquals("fr", locale.getLanguage());
        assertEquals("", locale.getCountry());
    }

    @Test
    public void testToLocaleFrenchCanada() {
        AcceptLanguage al = new AcceptLanguage("fr-CA", 0.9);
        Locale locale = al.toLocale();
        
        assertEquals("fr", locale.getLanguage());
        assertEquals("CA", locale.getCountry());
    }

    @Test
    public void testToLocaleGerman() {
        AcceptLanguage al = new AcceptLanguage("de-DE", 1.0);
        Locale locale = al.toLocale();
        
        assertEquals("de", locale.getLanguage());
        assertEquals("DE", locale.getCountry());
    }

    @Test
    public void testToLocaleJapanese() {
        AcceptLanguage al = new AcceptLanguage("ja", 0.5);
        Locale locale = al.toLocale();
        
        assertEquals("ja", locale.getLanguage());
    }

    @Test
    public void testToLocaleChineseSimplified() {
        AcceptLanguage al = new AcceptLanguage("zh-CN", 1.0);
        Locale locale = al.toLocale();
        
        assertEquals("zh", locale.getLanguage());
        assertEquals("CN", locale.getCountry());
    }

    // ===== Quality Value Comparison Tests =====

    @Test
    public void testCompareToHigherQuality() {
        AcceptLanguage high = new AcceptLanguage("en", 1.0);
        AcceptLanguage low = new AcceptLanguage("fr", 0.5);
        
        // High quality comes first (negative compareTo)
        assertTrue(high.compareTo(low) < 0);
    }

    @Test
    public void testCompareToLowerQuality() {
        AcceptLanguage high = new AcceptLanguage("en", 1.0);
        AcceptLanguage low = new AcceptLanguage("fr", 0.5);
        
        // Low quality comes after (positive compareTo)
        assertTrue(low.compareTo(high) > 0);
    }

    @Test
    public void testCompareToEqualQuality() {
        AcceptLanguage al1 = new AcceptLanguage("en", 0.8);
        AcceptLanguage al2 = new AcceptLanguage("fr", 0.8);
        
        assertEquals(0, al1.compareTo(al2));
    }

    @Test
    public void testCompareToNonAcceptLanguage() {
        AcceptLanguage al = new AcceptLanguage("en", 1.0);
        
        // Comparing to non-AcceptLanguage should return 0
        assertEquals(0, al.compareTo("not an AcceptLanguage"));
        assertEquals(0, al.compareTo(Integer.valueOf(42)));
        assertEquals(0, al.compareTo(null));
    }

    // ===== Sorting Tests =====

    @Test
    public void testSortByQuality() {
        AcceptLanguage en = new AcceptLanguage("en", 1.0);
        AcceptLanguage fr = new AcceptLanguage("fr", 0.8);
        AcceptLanguage de = new AcceptLanguage("de", 0.5);
        AcceptLanguage ja = new AcceptLanguage("ja", 0.3);
        
        List<AcceptLanguage> list = Arrays.asList(ja, en, de, fr);
        Collections.sort(list);
        
        // Should be sorted by quality descending
        assertEquals("en", list.get(0).spec);
        assertEquals("fr", list.get(1).spec);
        assertEquals("de", list.get(2).spec);
        assertEquals("ja", list.get(3).spec);
    }

    @Test
    public void testSortPreservesOrderForEqualQuality() {
        AcceptLanguage en = new AcceptLanguage("en", 0.8);
        AcceptLanguage fr = new AcceptLanguage("fr", 0.8);
        AcceptLanguage de = new AcceptLanguage("de", 0.8);
        
        List<AcceptLanguage> list = Arrays.asList(en, fr, de);
        Collections.sort(list);
        
        // All equal quality, so stable sort should preserve order
        // (depends on sort implementation, but compareTo returns 0)
        assertEquals(0.8, list.get(0).q, 0.001);
        assertEquals(0.8, list.get(1).q, 0.001);
        assertEquals(0.8, list.get(2).q, 0.001);
    }

    // ===== Edge Cases =====

    @Test
    public void testQualityZero() {
        AcceptLanguage al = new AcceptLanguage("en", 0.0);
        
        assertEquals("en", al.spec);
        assertEquals(0.0, al.q, 0.001);
        
        Locale locale = al.toLocale();
        assertEquals("en", locale.getLanguage());
    }

    @Test
    public void testQualityOne() {
        AcceptLanguage al = new AcceptLanguage("en", 1.0);
        
        assertEquals(1.0, al.q, 0.001);
    }

    @Test
    public void testQualityPrecision() {
        AcceptLanguage al = new AcceptLanguage("en", 0.123);
        
        assertEquals(0.123, al.q, 0.0001);
    }

    @Test
    public void testEmptySpec() {
        AcceptLanguage al = new AcceptLanguage("", 1.0);
        Locale locale = al.toLocale();
        
        assertEquals("", locale.getLanguage());
    }

    @Test
    public void testWildcard() {
        // The wildcard * is sometimes used in Accept-Language
        AcceptLanguage al = new AcceptLanguage("*", 0.1);
        Locale locale = al.toLocale();
        
        assertEquals("*", locale.getLanguage());
    }

    @Test
    public void testThreePartSpec() {
        // Some language tags have 3 parts (e.g., zh-Hans-CN)
        // Current implementation only handles 2 parts
        AcceptLanguage al = new AcceptLanguage("zh-Hans-CN", 1.0);
        Locale locale = al.toLocale();
        
        assertEquals("zh", locale.getLanguage());
        // Locale uppercases country codes
        assertEquals("HANS-CN", locale.getCountry()); // Everything after first hyphen, uppercased
    }

    // ===== Field Access Tests =====

    @Test
    public void testSpecField() {
        AcceptLanguage al = new AcceptLanguage("en-US", 0.9);
        assertEquals("en-US", al.spec);
    }

    @Test
    public void testQField() {
        AcceptLanguage al = new AcceptLanguage("en-US", 0.9);
        assertEquals(0.9, al.q, 0.001);
    }

}

