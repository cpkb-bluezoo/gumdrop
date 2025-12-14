/*
 * ServiceRef.java
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

import java.util.ArrayList;
import java.util.List;

import javax.xml.ws.WebServiceRef;

import org.bluezoo.gumdrop.servlet.Description;

/**
 * A reference to a web service.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class ServiceRef implements Description, Injectable {

    // Description
    String description;
    String displayName;
    String smallIcon;
    String largeIcon;

    String name; // service-ref-name
    String serviceInterface;
    String className; // service-ref-type
    String wsdlFile;
    String jaxrpcMappingFile;
    String serviceQname;
    String portComponentRef;
    Object handler; // HandlerDef
    List<Object> handlerChains = new ArrayList<>(); // List<HandlerChainDef>

    // Injectable
    String lookupName;
    InjectionTarget injectionTarget;
    String mappedName;

    // -- Setters and Getters --

    /**
     * Sets the name of this service reference.
     * Corresponds to the {@code service-ref-name} element in the deployment descriptor.
     * <p>
     * The name is relative to the {@code java:comp/env} context.
     *
     * @param name the service reference name
     * @see <a href="https://jakarta.ee/specifications/servlet/4.0/servlet-spec-4.0.html#service-ref">
     *      Servlet 4.0 Specification, Section 14.4.8: Web Service References</a>
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * Returns the name of this service reference.
     *
     * @return the service reference name
     */
    public String getName() {
        return name;
    }

    /**
     * Sets the service interface class.
     * Corresponds to the {@code service-interface} element in the deployment descriptor.
     * <p>
     * This is the fully-qualified class name of the JAX-WS Service class
     * (either generated or {@code javax.xml.ws.Service}).
     *
     * @param serviceInterface the service interface class name
     * @see <a href="https://jakarta.ee/specifications/servlet/4.0/servlet-spec-4.0.html#service-ref">
     *      Servlet 4.0 Specification, Section 14.4.8: Web Service References</a>
     */
    public void setServiceInterface(String serviceInterface) {
        this.serviceInterface = serviceInterface;
    }

    /**
     * Sets the service endpoint interface class or generated service class.
     * Corresponds to the {@code service-ref-type} element in the deployment descriptor.
     * <p>
     * This is the fully-qualified class name that the injected reference
     * should be assignable to.
     *
     * @param className the service reference type class name
     * @see <a href="https://jakarta.ee/specifications/servlet/4.0/servlet-spec-4.0.html#service-ref">
     *      Servlet 4.0 Specification, Section 14.4.8: Web Service References</a>
     */
    public void setClassName(String className) {
        this.className = className;
    }

    /**
     * Sets the location of the WSDL file.
     * Corresponds to the {@code wsdl-file} element in the deployment descriptor.
     * <p>
     * This is a path relative to the module root. The WSDL file is required
     * unless the service is a generated SEI.
     *
     * @param wsdlFile the WSDL file path relative to the module root
     * @see <a href="https://jakarta.ee/specifications/servlet/4.0/servlet-spec-4.0.html#service-ref">
     *      Servlet 4.0 Specification, Section 14.4.8: Web Service References</a>
     */
    public void setWsdlFile(String wsdlFile) {
        this.wsdlFile = wsdlFile;
    }

    /**
     * Sets the location of the JAX-RPC mapping file.
     * Corresponds to the {@code jaxrpc-mapping-file} element in the deployment descriptor.
     * <p>
     * This is a path relative to the module root. Only used with JAX-RPC style
     * web services (deprecated in favor of JAX-WS).
     *
     * @param jaxrpcMappingFile the JAX-RPC mapping file path
     * @see <a href="https://jakarta.ee/specifications/servlet/4.0/servlet-spec-4.0.html#service-ref">
     *      Servlet 4.0 Specification, Section 14.4.8: Web Service References</a>
     */
    public void setJaxrpcMappingFile(String jaxrpcMappingFile) {
        this.jaxrpcMappingFile = jaxrpcMappingFile;
    }

    /**
     * Sets the qualified name of the service.
     * Corresponds to the {@code service-qname} element in the deployment descriptor.
     * <p>
     * This is the fully qualified QName of the service as declared in the WSDL.
     *
     * @param serviceQname the service QName as a string
     * @see <a href="https://jakarta.ee/specifications/servlet/4.0/servlet-spec-4.0.html#service-ref">
     *      Servlet 4.0 Specification, Section 14.4.8: Web Service References</a>
     */
    public void setServiceQname(String serviceQname) {
        this.serviceQname = serviceQname;
    }

    /**
     * Sets a reference to a port component.
     * Corresponds to the {@code port-component-ref} element in the deployment descriptor.
     * <p>
     * This allows customization of the port and its configuration.
     *
     * @param portComponentRef the port component reference
     * @see <a href="https://jakarta.ee/specifications/servlet/4.0/servlet-spec-4.0.html#service-ref">
     *      Servlet 4.0 Specification, Section 14.4.8: Web Service References</a>
     */
    public void setPortComponentRef(String portComponentRef) {
        this.portComponentRef = portComponentRef;
    }

    /**
     * Sets a handler for this service reference.
     * Corresponds to the {@code handler} element in the deployment descriptor.
     * <p>
     * Handlers are invoked before and after the service endpoint is invoked.
     *
     * @param handler the handler definition
     * @see <a href="https://jakarta.ee/specifications/servlet/4.0/servlet-spec-4.0.html#service-ref">
     *      Servlet 4.0 Specification, Section 14.4.8: Web Service References</a>
     */
    public void setHandler(Object handler) {
        this.handler = handler;
    }

    public void init(WebServiceRef config) {
        name = config.name();
        className = config.type().getName();
        serviceInterface = config.value().getName();
        wsdlFile = config.wsdlLocation();
        lookupName = config.lookup();
        mappedName = config.mappedName();
    }

    public void addHandlerChain(Object handlerChain) {
        handlerChains.add(handlerChain);
    }

    // -- Description --

    @Override public String getDescription() {
        return description;
    }

    @Override public void setDescription(String description) {
        this.description = description;
    }

    @Override public String getDisplayName() {
        return displayName;
    }

    @Override public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    @Override public String getSmallIcon() {
        return smallIcon;
    }

    @Override public void setSmallIcon(String smallIcon) {
        this.smallIcon = smallIcon;
    }

    @Override public String getLargeIcon() {
        return largeIcon;
    }

    @Override public void setLargeIcon(String largeIcon) {
        this.largeIcon = largeIcon;
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

