/*
 * PersistenceUnitRef.java
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

import javax.persistence.PersistenceUnit;

/**
 * A reference to a container-managed <code>EntityManagerFactory</code>.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class PersistenceUnitRef implements Injectable {

    String description;
    String name; // persistence-unit-ref-name
    String unitName; // persistence-unit-name

    // Injectable
    String lookupName;
    InjectionTarget injectionTarget;
    String mappedName;

    // -- Setters and Getters --

    /**
     * Sets a description of this persistence unit reference.
     * Corresponds to the {@code description} element within {@code persistence-unit-ref}
     * in the deployment descriptor.
     *
     * @param description a textual description of this reference
     * @see <a href="https://jakarta.ee/specifications/servlet/4.0/servlet-spec-4.0.html#persistence-unit-ref">
     *      Servlet 4.0 Specification, Section 14.4.7: Persistence Unit References</a>
     */
    public void setDescription(String description) {
        this.description = description;
    }

    /**
     * Sets the name of this persistence unit reference.
     * Corresponds to the {@code persistence-unit-ref-name} element in the deployment descriptor.
     * <p>
     * The name is relative to the {@code java:comp/env} context.
     *
     * @param name the persistence unit reference name
     * @see <a href="https://jakarta.ee/specifications/servlet/4.0/servlet-spec-4.0.html#persistence-unit-ref">
     *      Servlet 4.0 Specification, Section 14.4.7: Persistence Unit References</a>
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * Returns the name of this persistence unit reference.
     *
     * @return the persistence unit reference name
     */
    public String getName() {
        return name;
    }

    /**
     * Sets the name of the persistence unit.
     * Corresponds to the {@code persistence-unit-name} element in the deployment descriptor.
     * <p>
     * This identifies the persistence unit defined in {@code persistence.xml}
     * that this reference refers to. An {@code EntityManagerFactory} for this
     * persistence unit will be injected.
     *
     * @param unitName the persistence unit name
     * @see <a href="https://jakarta.ee/specifications/servlet/4.0/servlet-spec-4.0.html#persistence-unit-ref">
     *      Servlet 4.0 Specification, Section 14.4.7: Persistence Unit References</a>
     */
    public void setUnitName(String unitName) {
        this.unitName = unitName;
    }

    public void init(PersistenceUnit config) {
        name = config.name();
        unitName = config.unitName();
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

    @Override public String getDefaultName() {
        return String.format("java:comp/env/%s", unitName);
    }

    @Override public InjectionTarget getInjectionTarget() {
        return injectionTarget;
    }

    @Override public void setInjectionTarget(InjectionTarget injectionTarget) {
        this.injectionTarget = injectionTarget;
    }

}

