/*
 * EjbRef.java
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

import javax.ejb.EJB;
import javax.naming.NamingException;

/**
 * A reference to an enterprise bean.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class EjbRef implements Injectable {

    String description;
    String name; // ejb-ref-name
    String className; // ejb-ref-type
    String home; // home or local-home
    String remoteOrLocal; // remote or local
    String ejbLink;
    boolean remote; // if this is a reference to a remote EJB

    // Injectable
    String lookupName;
    InjectionTarget injectionTarget;
    String mappedName;

    public EjbRef(boolean remote) {
        this.remote = remote;
    }

    // -- Setters and Getters --

    /**
     * Sets a description of this EJB reference.
     * Corresponds to the {@code description} element within {@code ejb-ref}
     * or {@code ejb-local-ref} in the deployment descriptor.
     *
     * @param description a textual description of this EJB reference
     * @see <a href="https://jakarta.ee/specifications/servlet/4.0/servlet-spec-4.0.html#ejb-ref">
     *      Servlet 4.0 Specification, Section 14.4.2: EJB References</a>
     */
    public void setDescription(String description) {
        this.description = description;
    }

    /**
     * Sets the name of this EJB reference.
     * Corresponds to the {@code ejb-ref-name} element in the deployment descriptor.
     * <p>
     * The name is relative to the {@code java:comp/env} context. For example,
     * a name of {@code ejb/MyBean} would be accessible as {@code java:comp/env/ejb/MyBean}.
     *
     * @param name the EJB reference name
     * @see <a href="https://jakarta.ee/specifications/servlet/4.0/servlet-spec-4.0.html#ejb-ref">
     *      Servlet 4.0 Specification, Section 14.4.2: EJB References</a>
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * Returns the name of this EJB reference.
     *
     * @return the EJB reference name
     */
    public String getName() {
        return name;
    }

    /**
     * Sets the expected type of the referenced EJB.
     * Corresponds to the {@code ejb-ref-type} element in the deployment descriptor.
     * <p>
     * Valid values are {@code Entity} or {@code Session}.
     *
     * @param className the EJB type (Entity or Session)
     * @see <a href="https://jakarta.ee/specifications/servlet/4.0/servlet-spec-4.0.html#ejb-ref">
     *      Servlet 4.0 Specification, Section 14.4.2: EJB References</a>
     */
    public void setClassName(String className) {
        this.className = className;
    }

    /**
     * Sets the home interface class name.
     * Corresponds to the {@code home} element (for remote EJBs) or
     * {@code local-home} element (for local EJBs) in the deployment descriptor.
     * <p>
     * This specifies the fully-qualified class name of the enterprise bean's
     * home interface.
     *
     * @param home the home interface class name
     * @see <a href="https://jakarta.ee/specifications/servlet/4.0/servlet-spec-4.0.html#ejb-ref">
     *      Servlet 4.0 Specification, Section 14.4.2: EJB References</a>
     */
    public void setHome(String home) {
        this.home = home;
    }

    /**
     * Sets the remote or local interface class name.
     * Corresponds to the {@code remote} element (for remote EJBs) or
     * {@code local} element (for local EJBs) in the deployment descriptor.
     * <p>
     * This specifies the fully-qualified class name of the enterprise bean's
     * remote or local interface.
     *
     * @param remoteOrLocal the remote or local interface class name
     * @see <a href="https://jakarta.ee/specifications/servlet/4.0/servlet-spec-4.0.html#ejb-ref">
     *      Servlet 4.0 Specification, Section 14.4.2: EJB References</a>
     */
    public void setRemoteOrLocal(String remoteOrLocal) {
        this.remoteOrLocal = remoteOrLocal;
    }

    /**
     * Sets the name used to link this reference to an EJB.
     * Corresponds to the {@code ejb-link} element in the deployment descriptor.
     * <p>
     * This is used by the Deployer to link an EJB reference that is declared
     * in the web application to the target enterprise bean.
     *
     * @param ejbLink the name of the target enterprise bean
     * @see <a href="https://jakarta.ee/specifications/servlet/4.0/servlet-spec-4.0.html#ejb-ref">
     *      Servlet 4.0 Specification, Section 14.4.2: EJB References</a>
     */
    public void setEjbLink(String ejbLink) {
        this.ejbLink = ejbLink;
    }

    public void init(EJB config) {
        description = config.description();
        name = config.name();
        className = config.beanInterface().getName();
        ejbLink = config.beanName();
        lookupName = config.lookup();
        mappedName = config.mappedName();
    }

    // -- Injectable --

    @Override public String getLookupName() {
        return lookupName;
    }

    @Override public void setLookupName(String lookupName) {
        this.lookupName = lookupName;
    }

    @Override public InjectionTarget getInjectionTarget() {
        return injectionTarget;
    }

    @Override public void setInjectionTarget(InjectionTarget injectionTarget) {
        this.injectionTarget = injectionTarget;
    }

    @Override public String getMappedName() {
        return mappedName;
    }

    @Override public void setMappedName(String mappedName) {
        this.mappedName = mappedName;
    }

    @Override public String getDefaultName() {
        return ejbLink;
    }

    @Override public Object resolve(javax.naming.Context ctx) throws NamingException {
        // EJB 3.x lookup resolution order (EJB spec section 16.2.2):
        // 1. lookup attribute (if specified)
        // 2. ejb-link (direct reference to bean)
        // 3. mapped-name (vendor-specific JNDI name)
        // 4. Default JNDI names based on interface
        
        Object resolved = null;
        
        // 1. Try explicit lookup name
        if (lookupName != null && !lookupName.isEmpty()) {
            resolved = safeLookup(ctx, lookupName);
            if (resolved != null) {
                return resolved;
            }
        }
        
        // 2. Try ejb-link
        if (ejbLink != null && !ejbLink.isEmpty()) {
            resolved = safeLookup(ctx, ejbLink);
            if (resolved != null) {
                return resolved;
            }
            // Also try with java:comp/env prefix
            resolved = safeLookup(ctx, "java:comp/env/ejb/" + ejbLink);
            if (resolved != null) {
                return resolved;
            }
        }
        
        // 3. Try mapped-name
        if (mappedName != null && !mappedName.isEmpty()) {
            resolved = safeLookup(ctx, mappedName);
            if (resolved != null) {
                return resolved;
            }
            resolved = safeLookup(ctx, "java:comp/env/" + mappedName);
            if (resolved != null) {
                return resolved;
            }
        }
        
        // 4. Try default JNDI names based on interface
        if (remoteOrLocal != null && !remoteOrLocal.isEmpty()) {
            // Try java:global/<app>/<module>/<bean>!<interface>
            // Since we don't have app/module info, try simpler patterns
            resolved = safeLookup(ctx, "java:global/" + remoteOrLocal);
            if (resolved != null) {
                return resolved;
            }
            // Try java:app/<module>/<bean>
            resolved = safeLookup(ctx, "java:app/" + remoteOrLocal);
            if (resolved != null) {
                return resolved;
            }
        }
        
        // 5. Try using the reference name
        if (name != null && !name.isEmpty()) {
            resolved = safeLookup(ctx, "java:comp/env/" + name);
            if (resolved != null) {
                return resolved;
            }
        }
        
        return null;
    }
    
    /**
     * Attempts a JNDI lookup, returning null instead of throwing NamingException
     * if the name is not found.
     */
    private Object safeLookup(javax.naming.Context ctx, String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        try {
            return ctx.lookup(name);
        } catch (NamingException e) {
            // Name not found, try next
            return null;
        }
    }

}

