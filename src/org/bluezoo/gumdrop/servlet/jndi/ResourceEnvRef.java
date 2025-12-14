/*
 * ResourceEnvRef.java
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
 * A reference to an administered object associated with a resource in the
 * environment.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class ResourceEnvRef implements Injectable {

    String description;
    String name; // resource-env-ref-name
    String className; // resource-env-ref-type

    // Injectable
    String lookupName;
    InjectionTarget injectionTarget;
    String mappedName;

    // -- Setters and Getters --

    /**
     * Sets a description of this resource environment reference.
     * Corresponds to the {@code description} element within {@code resource-env-ref}
     * in the deployment descriptor.
     *
     * @param description a textual description of this reference
     * @see <a href="https://jakarta.ee/specifications/servlet/4.0/servlet-spec-4.0.html#resource-env-ref">
     *      Servlet 4.0 Specification, Section 14.4.4: Resource Environment References</a>
     */
    public void setDescription(String description) {
        this.description = description;
    }

    /**
     * Sets the name of this resource environment reference.
     * Corresponds to the {@code resource-env-ref-name} element in the deployment descriptor.
     * <p>
     * The name is relative to the {@code java:comp/env} context.
     *
     * @param name the resource environment reference name
     * @see <a href="https://jakarta.ee/specifications/servlet/4.0/servlet-spec-4.0.html#resource-env-ref">
     *      Servlet 4.0 Specification, Section 14.4.4: Resource Environment References</a>
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * Sets the expected Java type of the referenced administered object.
     * Corresponds to the {@code resource-env-ref-type} element in the deployment descriptor.
     * <p>
     * This must be a fully-qualified class name of an administered object
     * associated with a resource in the component's environment.
     *
     * @param className the fully-qualified class name of the administered object type
     * @see <a href="https://jakarta.ee/specifications/servlet/4.0/servlet-spec-4.0.html#resource-env-ref">
     *      Servlet 4.0 Specification, Section 14.4.4: Resource Environment References</a>
     */
    public void setClassName(String className) {
        this.className = className;
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

