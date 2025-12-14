/*
 * InjectionTarget.java
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

/**
 * An injection target for the value of a JNDI resource.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class InjectionTarget {

    String name; // injection-target-name
    String className; // injection-target-class

    // -- Setters and Getters --

    /**
     * Sets the name of the injection target.
     * Corresponds to the {@code injection-target-name} element in the deployment descriptor.
     * <p>
     * For field injection, this is the field name. For method injection, this is
     * the JavaBeans property name corresponding to the setter method (the method
     * name without the "set" prefix and with the first character lowercased).
     *
     * @param name the injection target name (field name or property name)
     * @see <a href="https://jakarta.ee/specifications/servlet/4.0/servlet-spec-4.0.html#injection-target">
     *      Servlet 4.0 Specification, Section 14.5.1: Injection Target</a>
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * Returns the name of the injection target.
     *
     * @return the injection target name
     */
    public String getName() {
        return name;
    }

    /**
     * Sets the fully-qualified class name of the target class.
     * Corresponds to the {@code injection-target-class} element in the deployment descriptor.
     * <p>
     * This is the class that contains the field or setter method where the
     * resource will be injected.
     *
     * @param className the fully-qualified class name of the injection target class
     * @see <a href="https://jakarta.ee/specifications/servlet/4.0/servlet-spec-4.0.html#injection-target">
     *      Servlet 4.0 Specification, Section 14.5.1: Injection Target</a>
     */
    public void setClassName(String className) {
        this.className = className;
    }

    /**
     * Returns the fully-qualified class name of the target class.
     *
     * @return the injection target class name
     */
    public String getClassName() {
        return className;
    }

}

