/*
 * ContextHandlesTypesTest.java
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

package org.bluezoo.gumdrop.servlet;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Which scanned classes an initializer is given for a type it names in
 * {@code @HandlesTypes} (Servlet 6.1 section 8.2.4): those that extend or
 * implement the type, and for an annotation type those that carry it on
 * the class or on one of its fields or methods.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ContextHandlesTypesTest {

    /** Annotation an initializer handles. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ ElementType.TYPE, ElementType.FIELD, ElementType.METHOD })
    public @interface Mark {
    }

    /** Another annotation, that it does not. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ ElementType.TYPE, ElementType.FIELD, ElementType.METHOD })
    public @interface Other {
    }

    /** Type an initializer handles. */
    public interface Marker {
    }

    public static class Implementation implements Marker {
    }

    public static class Extension extends Implementation {
    }

    @Mark
    public static class OnClass {
    }

    public static class OnField {
        @Mark
        private Object field;
    }

    public static class OnMethod {
        @Mark
        private void method() {
        }
    }

    @Other
    public static class Unmarked {
        @Other
        Object field;

        @Other
        void method() {
        }
    }

    @Test
    public void testSubtypesAreHandled() {
        assertTrue(Context.isHandled(Marker.class, Implementation.class));
        assertTrue(Context.isHandled(Marker.class, Extension.class));
        assertTrue(Context.isHandled(Implementation.class, Extension.class));
        assertFalse(Context.isHandled(Marker.class, Unmarked.class));
        assertFalse(Context.isHandled(Extension.class, Implementation.class));
    }

    @Test
    public void testAnnotatedClassesAreHandled() {
        assertTrue(Context.isHandled(Mark.class, OnClass.class));
        assertTrue(Context.isHandled(Mark.class, OnField.class));
        assertTrue(Context.isHandled(Mark.class, OnMethod.class));
        assertFalse(Context.isHandled(Mark.class, Unmarked.class));
        assertFalse(Context.isHandled(Mark.class, Implementation.class));
    }

    /**
     * A library that declares the handled type is scanned as well, but the
     * type is not a class the application wrote against it.
     */
    @Test
    public void testTheTypeItselfIsNotHandled() {
        assertFalse(Context.isHandled(Marker.class, Marker.class));
        assertFalse(Context.isHandled(Mark.class, Mark.class));
        assertFalse(Context.isHandled(Implementation.class, Implementation.class));
    }
}
