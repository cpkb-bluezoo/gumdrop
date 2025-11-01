/*
 * ServiceRef.java
 * Copyright (C) 2025 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 *
 * This software is dual-licensed:
 *
 * 1. GNU General Public License v3 (or later) for open source use
 *    See LICENCE-GPL3 file for GPL terms and conditions.
 *
 * 2. Commercial License for proprietary use
 *    Contact Chris Burdess <dog@gnu.org> for commercial licensing terms.
 *    Mimecast Services Limited has been granted commercial usage rights under
 *    separate license agreement.
 *
 * gumdrop is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package org.bluezoo.gumdrop.servlet;

import java.util.ArrayList;
import java.util.List;
import javax.xml.ws.WebServiceRef;

/**
 * A reference to a web service.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class ServiceRef implements Description, Injectable {

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
    HandlerDef handler;
    List<HandlerChainDef> handlerChains;

    // Injectable
    String lookupName;
    InjectionTarget injectionTarget;
    String mappedName;

    void init(WebServiceRef config) {
        name = config.name();
        className = config.type().getName();
        serviceInterface = config.value().getName();
        wsdlFile = config.wsdlLocation();
        lookupName = config.lookup();
        mappedName = config.mappedName();
    }

    void addHandlerChain(HandlerChainDef handlerChain) {
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
