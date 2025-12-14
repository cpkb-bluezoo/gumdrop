/*
 * PersistenceContextRef.java
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

import javax.persistence.PersistenceContext;
import javax.persistence.PersistenceContextType;

/**
 * A reference to a container-managed <code>EntityManager</code>.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class PersistenceContextRef implements Injectable {

    String description;
    String name; // persistence-context-ref-name
    PersistenceContextType type; // persistence-context-type
    String unitName; // persistence-unit-name

    // Injectable
    String lookupName;
    InjectionTarget injectionTarget;
    String mappedName;

    // -- Setters and Getters --

    /**
     * Sets a description of this persistence context reference.
     * Corresponds to the {@code description} element within {@code persistence-context-ref}
     * in the deployment descriptor.
     *
     * @param description a textual description of this reference
     * @see <a href="https://jakarta.ee/specifications/servlet/4.0/servlet-spec-4.0.html#persistence-context-ref">
     *      Servlet 4.0 Specification, Section 14.4.6: Persistence Context References</a>
     */
    public void setDescription(String description) {
        this.description = description;
    }

    /**
     * Sets the name of this persistence context reference.
     * Corresponds to the {@code persistence-context-ref-name} element in the deployment descriptor.
     * <p>
     * The name is relative to the {@code java:comp/env} context.
     *
     * @param name the persistence context reference name
     * @see <a href="https://jakarta.ee/specifications/servlet/4.0/servlet-spec-4.0.html#persistence-context-ref">
     *      Servlet 4.0 Specification, Section 14.4.6: Persistence Context References</a>
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * Returns the name of this persistence context reference.
     *
     * @return the persistence context reference name
     */
    public String getName() {
        return name;
    }

    /**
     * Sets the type of persistence context.
     * Corresponds to the {@code persistence-context-type} element in the deployment descriptor.
     * <p>
     * {@code TRANSACTION} (the default) means the persistence context is scoped
     * to the current transaction. {@code EXTENDED} means the persistence context
     * extends beyond a single transaction.
     *
     * @param type the persistence context type
     * @see javax.persistence.PersistenceContextType#TRANSACTION
     * @see javax.persistence.PersistenceContextType#EXTENDED
     * @see <a href="https://jakarta.ee/specifications/servlet/4.0/servlet-spec-4.0.html#persistence-context-ref">
     *      Servlet 4.0 Specification, Section 14.4.6: Persistence Context References</a>
     */
    public void setType(PersistenceContextType type) {
        this.type = type;
    }

    /**
     * Sets the name of the persistence unit.
     * Corresponds to the {@code persistence-unit-name} element in the deployment descriptor.
     * <p>
     * This identifies the persistence unit defined in {@code persistence.xml}
     * that this reference refers to.
     *
     * @param unitName the persistence unit name
     * @see <a href="https://jakarta.ee/specifications/servlet/4.0/servlet-spec-4.0.html#persistence-context-ref">
     *      Servlet 4.0 Specification, Section 14.4.6: Persistence Context References</a>
     */
    public void setUnitName(String unitName) {
        this.unitName = unitName;
    }

    public void init(PersistenceContext config) {
        name = config.name();
        type = config.type();
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

