/*
 * AnnotationStubs.java
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

package org.bluezoo.gumdrop.servlet.jndi;

import java.lang.annotation.Annotation;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;

/**
 * Builds annotation instances from value maps so that code taking an
 * annotation can be driven without annotated declarations.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class AnnotationStubs {

    private AnnotationStubs() {
    }

    /** Handler answering from a value map, else with a type default. */
    private static final class AnnotationHandler implements InvocationHandler {
        private final Class<?> type;
        private final Map<String, Object> values;

        AnnotationHandler(Class<?> type, Map<String, Object> values) {
            this.type = type;
            this.values = values;
        }

        public Object invoke(Object proxy, Method m, Object[] args) {
            String n = m.getName();
            if (n.equals("annotationType")) {
                return type;
            }
            if (values.containsKey(n)) {
                return values.get(n);
            }
            Class<?> rt = m.getReturnType();
            if (rt == String.class) {
                return "";
            }
            if (rt == boolean.class) {
                return Boolean.FALSE;
            }
            if (rt == int.class) {
                return Integer.valueOf(0);
            }
            if (rt == Class.class) {
                return Object.class;
            }
            if (rt.isEnum()) {
                return rt.getEnumConstants()[0];
            }
            if (rt.isArray()) {
                return Array.newInstance(rt.getComponentType(), 0);
            }
            return null;
        }
    }

    static <A extends Annotation> A annotation(Class<A> type, Map<String, Object> values) {
        ClassLoader loader = type.getClassLoader();
        AnnotationHandler handler = new AnnotationHandler(type, values);
        Object o = Proxy.newProxyInstance(loader, new Class<?>[] { type }, handler);
        return type.cast(o);
    }
}
