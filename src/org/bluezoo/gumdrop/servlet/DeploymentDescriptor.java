/*
 * DeploymentDescriptor.java
 * Copyright (C) 2025 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 *
 * gumdrop is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * gumdrop is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with gumdrop.
 * If not, see <http://www.gnu.org/licenses/>.
 */
package org.bluezoo.gumdrop.servlet;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A deployment descriptor contains the definitions of various entities in
 * the web application, notably filter, servlets, and their mappings.
 *
 * This corresponds to the "javaee:web-commonType" in the servlet 4.0
 * specification.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
abstract class DeploymentDescriptor extends DescriptionGroup {

    int majorVersion = 2;
    int minorVersion = 4;

    Map<String,InitParam> contextParams = new LinkedHashMap<>();
    Map<String,FilterDef> filterDefs = new LinkedHashMap<>();
    List<FilterMapping> filterMappings = new ArrayList<>();
    List<ListenerDef> listenerDefs = new ArrayList<>();
    Map<String,ServletDef> servletDefs = new LinkedHashMap<>();
    List<ServletMapping> servletMappings = new ArrayList<>();
    SessionConfig sessionConfig;
    List<MimeMapping> mimeMappings = new ArrayList<>();
    List<String> welcomeFiles = new ArrayList<>();
    List<ErrorPage> errorPages = new ArrayList<>();
    List<JspConfig> jspConfigs = new ArrayList<>();
    List<SecurityConstraint> securityConstraints = new ArrayList<>();
    LoginConfig loginConfig;
    List<SecurityRole> securityRoles = new ArrayList<>();
    Map<String,String> localeEncodingMappings = new LinkedHashMap<>();
    List<ServletDataSource> dataSources = new ArrayList<>();

    boolean authentication;

    void reset() {
        super.reset();

        contextParams.clear();
        filterDefs.clear();
        filterMappings.clear();
        listenerDefs.clear();
        servletDefs.clear();
        servletMappings.clear();
        mimeMappings.clear();
        welcomeFiles.clear();
        errorPages.clear();
        jspConfigs.clear();
        securityConstraints.clear();
        loginConfig = null;
        securityRoles.clear();
        localeEncodingMappings.clear();
        dataSources.clear();

        authentication = false;
    }

    // Convenience methods for LoginConfig

    String getAuthMethod() {
        return (loginConfig == null) ? null : loginConfig.authMethod;
    }

    String getRealmName() {
        return (loginConfig == null) ? null : loginConfig.realmName;
    }

    String getFormLoginPage() {
        return (loginConfig == null) ? null : loginConfig.formLoginPage;
    }

    String getFormErrorPage() {
        return (loginConfig == null) ? null : loginConfig.formErrorPage;
    }

}
