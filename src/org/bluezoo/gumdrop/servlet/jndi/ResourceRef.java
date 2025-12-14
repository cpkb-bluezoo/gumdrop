/*
 * ResourceRef.java
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

import javax.annotation.Resource;

/**
 * A reference to an external resource.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class ResourceRef implements Injectable {

    String description;
    String name; // res-ref-name
    String className; // res-type
    Resource.AuthenticationType resAuth;
    String resSharingScope;

    // Injectable
    String lookupName;
    InjectionTarget injectionTarget;
    String mappedName;

    // -- Setters and Getters --

    /**
     * Sets a description of this resource reference.
     * Corresponds to the {@code description} element within {@code resource-ref}
     * in the deployment descriptor.
     *
     * @param description a textual description of this resource reference
     * @see <a href="https://jakarta.ee/specifications/servlet/4.0/servlet-spec-4.0.html#resource-ref">
     *      Servlet 4.0 Specification, Section 14.4.3: Resource References</a>
     */
    public void setDescription(String description) {
        this.description = description;
    }

    /**
     * Sets the name of this resource reference.
     * Corresponds to the {@code res-ref-name} element in the deployment descriptor.
     * <p>
     * The name is relative to the {@code java:comp/env} context. For example,
     * a name of {@code jdbc/MyDataSource} would be accessible as
     * {@code java:comp/env/jdbc/MyDataSource}.
     *
     * @param name the resource reference name
     * @see <a href="https://jakarta.ee/specifications/servlet/4.0/servlet-spec-4.0.html#resource-ref">
     *      Servlet 4.0 Specification, Section 14.4.3: Resource References</a>
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * Returns the name of this resource reference.
     *
     * @return the resource reference name
     */
    public String getName() {
        return name;
    }

    /**
     * Sets the expected Java type of the referenced resource.
     * Corresponds to the {@code res-type} element in the deployment descriptor.
     * <p>
     * This must be a fully-qualified class name. For a JDBC data source,
     * this would typically be {@code javax.sql.DataSource}.
     *
     * @param className the fully-qualified class name of the resource type
     * @see <a href="https://jakarta.ee/specifications/servlet/4.0/servlet-spec-4.0.html#resource-ref">
     *      Servlet 4.0 Specification, Section 14.4.3: Resource References</a>
     */
    public void setClassName(String className) {
        this.className = className;
    }

    /**
     * Sets the authentication type for this resource.
     * Corresponds to the {@code res-auth} element in the deployment descriptor.
     * <p>
     * {@code CONTAINER} indicates that the container will sign on to the resource
     * manager on behalf of the web application. {@code APPLICATION} indicates that
     * the application will sign on programmatically.
     *
     * @param resAuth the authentication type
     * @see javax.annotation.Resource.AuthenticationType#CONTAINER
     * @see javax.annotation.Resource.AuthenticationType#APPLICATION
     * @see <a href="https://jakarta.ee/specifications/servlet/4.0/servlet-spec-4.0.html#resource-ref">
     *      Servlet 4.0 Specification, Section 14.4.3: Resource References</a>
     */
    public void setResAuth(Resource.AuthenticationType resAuth) {
        this.resAuth = resAuth;
    }

    /**
     * Sets the sharing scope for connections to this resource.
     * Corresponds to the {@code res-sharing-scope} element in the deployment descriptor.
     * <p>
     * Valid values are {@code Shareable} (default) or {@code Unshareable}.
     * {@code Shareable} means connections obtained from the resource can be
     * shared by multiple components. {@code Unshareable} means connections
     * are not shared.
     *
     * @param resSharingScope the sharing scope (Shareable or Unshareable)
     * @see <a href="https://jakarta.ee/specifications/servlet/4.0/servlet-spec-4.0.html#resource-ref">
     *      Servlet 4.0 Specification, Section 14.4.3: Resource References</a>
     */
    public void setResSharingScope(String resSharingScope) {
        this.resSharingScope = resSharingScope;
    }

    public void init(Resource config) {
        description = config.description();
        name = config.name();
        resAuth = config.authenticationType();
        lookupName = config.lookup();
        mappedName = config.mappedName();
        className = config.type().getName();
        resSharingScope = config.shareable() ? "Shareable" : "Unshareable";
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

