/*
 * EnvEntry.java
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
 * An environment entry in a web application.
 * This is a simple named value that provides configuration information to
 * the web application. It is bound to a JNDI naming tree.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class EnvEntry implements Injectable {

    String description;
    String name; // env-entry-name
    String className; // env-entry-type
    String value; // env-entry-value

    // Injectable
    String lookupName;
    InjectionTarget injectionTarget;
    String mappedName;

    // -- Setters and Getters --

    /**
     * Sets a description of this environment entry.
     * Corresponds to the {@code description} element within {@code env-entry}
     * in the deployment descriptor.
     *
     * @param description a textual description of this environment entry
     * @see <a href="https://jakarta.ee/specifications/servlet/4.0/servlet-spec-4.0.html#env-entry">
     *      Servlet 4.0 Specification, Section 14.4.1: Environment Entries</a>
     */
    public void setDescription(String description) {
        this.description = description;
    }

    /**
     * Returns the description of this environment entry.
     *
     * @return the description, or {@code null} if not set
     */
    public String getDescription() {
        return description;
    }

    /**
     * Sets the name of this environment entry.
     * Corresponds to the {@code env-entry-name} element in the deployment descriptor.
     * <p>
     * The name is relative to the {@code java:comp/env} context. For example,
     * a name of {@code myConfig} would be accessible as {@code java:comp/env/myConfig}.
     *
     * @param name the environment entry name
     * @see <a href="https://jakarta.ee/specifications/servlet/4.0/servlet-spec-4.0.html#env-entry">
     *      Servlet 4.0 Specification, Section 14.4.1: Environment Entries</a>
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * Returns the name of this environment entry.
     *
     * @return the environment entry name
     */
    public String getName() {
        return name;
    }

    /**
     * Sets the fully-qualified Java type of this environment entry's value.
     * Corresponds to the {@code env-entry-type} element in the deployment descriptor.
     * <p>
     * The following types are supported:
     * {@code java.lang.Boolean}, {@code java.lang.Byte}, {@code java.lang.Character},
     * {@code java.lang.String}, {@code java.lang.Short}, {@code java.lang.Integer},
     * {@code java.lang.Long}, {@code java.lang.Float}, {@code java.lang.Double},
     * and {@code java.lang.Class}.
     *
     * @param className the fully-qualified class name of the entry type
     * @see <a href="https://jakarta.ee/specifications/servlet/4.0/servlet-spec-4.0.html#env-entry">
     *      Servlet 4.0 Specification, Section 14.4.1: Environment Entries</a>
     */
    public void setClassName(String className) {
        this.className = className;
    }

    /**
     * Returns the fully-qualified Java type of this environment entry's value.
     *
     * @return the class name of the entry type
     */
    public String getClassName() {
        return className;
    }

    /**
     * Sets the value of this environment entry.
     * Corresponds to the {@code env-entry-value} element in the deployment descriptor.
     * <p>
     * The value is specified as a string and will be converted to the type
     * specified by {@link #setClassName(String)}.
     *
     * @param value the environment entry value as a string
     * @see <a href="https://jakarta.ee/specifications/servlet/4.0/servlet-spec-4.0.html#env-entry">
     *      Servlet 4.0 Specification, Section 14.4.1: Environment Entries</a>
     */
    public void setValue(String value) {
        this.value = value;
    }

    /**
     * Returns the value of this environment entry.
     *
     * @return the environment entry value as a string
     */
    public String getValue() {
        return value;
    }

    // -- Injectable --

    @Override public String getLookupName() {
        return lookupName;
    }

    @Override public void setLookupName(String lookupName) {
        this.lookupName = lookupName;
    }

    @Override public String getMappedName() {
        return mappedName;
    }

    @Override public void setMappedName(String mappedName) {
        this.mappedName = mappedName;
    }

    @Override public InjectionTarget getInjectionTarget() {
        return injectionTarget;
    }

    @Override public void setInjectionTarget(InjectionTarget injectionTarget) {
        this.injectionTarget = injectionTarget;
    }

}

