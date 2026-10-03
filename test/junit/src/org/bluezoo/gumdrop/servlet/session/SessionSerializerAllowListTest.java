/*
 * SessionSerializerAllowListTest.java
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


package org.bluezoo.gumdrop.servlet.session;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests the deserialization allow-list of {@link SessionSerializer} for each
 * class category: null, primitives, arrays, enums in permitted packages and
 * configured extras, listed JDK classes and JDK collection internals.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SessionSerializerAllowListTest {

    @Before
    @After
    public void clearConfiguredClasses() {
        SessionSerializer.configureAllowedClasses(null);
    }

    private static Class<?> named(String name) throws ClassNotFoundException {
        return Class.forName(name);
    }

    @Test
    public void testNullAndPrimitivesAndArraysAreAllowed() {
        assertTrue(SessionSerializer.isAllowedDeserializationClass(null));
        assertTrue(SessionSerializer.isAllowedDeserializationClass(int.class));
        assertTrue(SessionSerializer.isAllowedDeserializationClass(String[].class));
        assertTrue(SessionSerializer.isAllowedDeserializationClass(int[][].class));
        assertFalse(SessionSerializer.isAllowedDeserializationClass(Thread[].class));
    }

    @Test
    public void testEnumsInJdkValuePackagesAreAllowed() {
        assertTrue(SessionSerializer.isAllowedDeserializationClass(RoundingMode.class));
        assertTrue(SessionSerializer.isAllowedDeserializationClass(DayOfWeek.class));
        assertTrue(SessionSerializer.isAllowedDeserializationClass(Thread.State.class));
    }

    @Test
    public void testOtherEnumsNeedConfiguration() {
        assertFalse(SessionSerializer.isAllowedDeserializationClass(TimeUnit.class));
        Set<String> extra = new HashSet<String>();
        extra.add(TimeUnit.class.getName());
        SessionSerializer.configureAllowedClasses(extra);
        assertTrue(SessionSerializer.isAllowedDeserializationClass(TimeUnit.class));
        SessionSerializer.configureAllowedClasses(Collections.<String>emptySet());
        assertFalse(SessionSerializer.isAllowedDeserializationClass(TimeUnit.class));
    }

    @Test
    public void testConfiguredClassIsAllowedAndOthersAreNot() {
        assertFalse(SessionSerializer.isAllowedDeserializationClass(StringBuilder.class));
        Set<String> extra = new HashSet<String>();
        extra.add(StringBuilder.class.getName());
        SessionSerializer.configureAllowedClasses(extra);
        assertTrue(SessionSerializer.isAllowedDeserializationClass(StringBuilder.class));
        assertFalse(SessionSerializer.isAllowedDeserializationClass(StringBuffer.class));
    }

    @Test
    public void testJdkCollectionInternalsAreAllowed() throws Exception {
        String[] allowed = {
            "java.util.Collections$EmptyList",
            "java.util.ImmutableCollections$ListN",
            "java.util.Arrays$ArrayList",
            "java.util.HashMap$Node",
            "java.util.LinkedHashMap$Entry",
            "java.util.TreeMap$Entry",
            "java.util.ArrayList$SubList",
            "java.util.Vector$Itr",
            "java.util.Map$Entry",
        };
        for (int i = 0; i < allowed.length; i++) {
            Class<?> c = named(allowed[i]);
            boolean ok = SessionSerializer.isAllowedDeserializationClass(c);
            assertTrue(allowed[i], ok);
        }
    }

    @Test
    public void testOtherJdkInternalsAreRejected() throws Exception {
        String[] rejected = {
            "java.util.concurrent.ConcurrentHashMap$Node",
            "java.util.Hashtable$Entry",
            "java.lang.ProcessBuilder",
            "java.util.logging.Logger",
        };
        for (int i = 0; i < rejected.length; i++) {
            Class<?> c = named(rejected[i]);
            boolean ok = SessionSerializer.isAllowedDeserializationClass(c);
            assertFalse(rejected[i], ok);
        }
    }
}
