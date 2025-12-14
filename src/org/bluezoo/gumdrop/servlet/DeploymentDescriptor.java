/*
 * DeploymentDescriptor.java
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

package org.bluezoo.gumdrop.servlet;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bluezoo.gumdrop.servlet.jndi.AdministeredObject;
import org.bluezoo.gumdrop.servlet.jndi.ConnectionFactory;
import org.bluezoo.gumdrop.servlet.jndi.DataSourceDef;
import org.bluezoo.gumdrop.servlet.jndi.EjbRef;
import org.bluezoo.gumdrop.servlet.jndi.EnvEntry;
import org.bluezoo.gumdrop.servlet.jndi.Injectable;
import org.bluezoo.gumdrop.servlet.jndi.JmsConnectionFactory;
import org.bluezoo.gumdrop.servlet.jndi.JmsDestination;
import org.bluezoo.gumdrop.servlet.jndi.MailSession;
import org.bluezoo.gumdrop.servlet.jndi.MessageDestinationRef;
import org.bluezoo.gumdrop.servlet.jndi.PersistenceContextRef;
import org.bluezoo.gumdrop.servlet.jndi.PersistenceUnitRef;
import org.bluezoo.gumdrop.servlet.jndi.Resource;
import org.bluezoo.gumdrop.servlet.jndi.ResourceEnvRef;
import org.bluezoo.gumdrop.servlet.jndi.ResourceRef;
import org.bluezoo.gumdrop.servlet.jndi.ServiceRef;

/**
 * A deployment descriptor contains the definitions of various entities in
 * the web application, notably filter, servlets, and their mappings.
 *
 * This corresponds to the "javaee:web-commonType" in the servlet 4.0
 * specification.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
abstract class DeploymentDescriptor implements Description {

    int majorVersion = 2;
    int minorVersion = 4;
    boolean metadataComplete;

    // Description
    String description;
    String displayName;
    String smallIcon;
    String largeIcon;

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
    JspConfig jspConfig;
    List<SecurityConstraint> securityConstraints = new ArrayList<>();
    LoginConfig loginConfig;
    List<SecurityRole> securityRoles = new ArrayList<>();
    List<EnvEntry> envEntries = new ArrayList<>();
    List<EjbRef> ejbRefs = new ArrayList<>();
    List<ServiceRef> serviceRefs = new ArrayList<>();
    List<ResourceRef> resourceRefs = new ArrayList<>();
    List<ResourceEnvRef> resourceEnvRefs = new ArrayList<>();
    List<MessageDestinationRef> messageDestinationRefs = new ArrayList<>();
    List<PersistenceContextRef> persistenceContextRefs = new ArrayList<>();
    List<PersistenceUnitRef> persistenceUnitRefs = new ArrayList<>();
    List<LifecycleCallback> postConstructs = new ArrayList<>();
    List<LifecycleCallback> preDestroys = new ArrayList<>();
    List<DataSourceDef> dataSourceDefs = new ArrayList<>();
    List<JmsConnectionFactory> jmsConnectionFactories = new ArrayList<>();
    List<JmsDestination> jmsDestinations = new ArrayList<>();
    List<MessageDestination> messageDestinations = new ArrayList<>();
    List<MailSession> mailSessions = new ArrayList<>();
    List<ConnectionFactory> connectionFactories = new ArrayList<>();
    List<AdministeredObject> administeredObjects = new ArrayList<>();
    Map<String,String> localeEncodingMappings = new LinkedHashMap<>();

    boolean authentication;

    boolean isEmpty() {
        return description == null &&
            displayName == null &&
            smallIcon == null &&
            largeIcon == null &&
            contextParams.isEmpty() &&
            filterDefs.isEmpty() &&
            filterMappings.isEmpty() &&
            listenerDefs.isEmpty() &&
            servletDefs.isEmpty() &&
            servletMappings.isEmpty() &&
            sessionConfig == null &&
            mimeMappings.isEmpty() &&
            welcomeFiles.isEmpty() &&
            errorPages.isEmpty() &&
            jspConfig == null &&
            securityConstraints.isEmpty() &&
            loginConfig == null &&
            securityRoles.isEmpty() &&
            envEntries.isEmpty() &&
            ejbRefs.isEmpty() &&
            serviceRefs.isEmpty() &&
            resourceRefs.isEmpty() &&
            resourceEnvRefs.isEmpty() &&
            messageDestinationRefs.isEmpty() &&
            persistenceContextRefs.isEmpty() &&
            persistenceUnitRefs.isEmpty() &&
            postConstructs.isEmpty() &&
            preDestroys.isEmpty() &&
            dataSourceDefs.isEmpty() &&
            jmsConnectionFactories.isEmpty() &&
            jmsDestinations.isEmpty() &&
            messageDestinations.isEmpty() &&
            mailSessions.isEmpty() &&
            connectionFactories.isEmpty() &&
            administeredObjects.isEmpty() &&
            localeEncodingMappings.isEmpty();
    }

    void reset() {
        description = null;
        displayName = null;
        smallIcon = null;
        largeIcon = null;

        contextParams.clear();
        filterDefs.clear();
        filterMappings.clear();
        listenerDefs.clear();
        servletDefs.clear();
        servletMappings.clear();
        mimeMappings.clear();
        welcomeFiles.clear();
        errorPages.clear();
        jspConfig = null;
        securityConstraints.clear();
        loginConfig = null;
        securityRoles.clear();
        envEntries.clear();
        ejbRefs.clear();
        serviceRefs.clear();
        resourceRefs.clear();
        resourceEnvRefs.clear();
        messageDestinationRefs.clear();
        persistenceContextRefs.clear();
        persistenceUnitRefs.clear();
        postConstructs.clear();
        preDestroys.clear();
        dataSourceDefs.clear();
        jmsConnectionFactories.clear();
        jmsDestinations.clear();
        mailSessions.clear();
        connectionFactories.clear();
        administeredObjects.clear();
        messageDestinations.clear();
        localeEncodingMappings.clear();

        authentication = false;
    }

    /**
     * Merge this deployment descriptor with the specified one.
     */
    void merge(DeploymentDescriptor other) {
        for (InitParam contextParam : other.contextParams.values()) {
            if (!contextParams.containsKey(contextParam.name)) {
                contextParams.put(contextParam.name, contextParam);
            }
        }
        for (FilterDef filterDef : other.filterDefs.values()) {
            if (!filterDefs.containsKey(filterDef.name)) {
                filterDefs.put(filterDef.name, filterDef);
            }
        }
        for (ServletDef servletDef : other.servletDefs.values()) {
            if (!servletDefs.containsKey(servletDef.name)) {
                servletDefs.put(servletDef.name, servletDef);
            }
        }
        filterMappings.addAll(other.filterMappings);
        listenerDefs.addAll(other.listenerDefs);
        servletMappings.addAll(other.servletMappings);
        if (sessionConfig == null) {
            sessionConfig = other.sessionConfig; // XXX check
        }
        mimeMappings.addAll(other.mimeMappings);
        welcomeFiles.addAll(other.welcomeFiles);
        errorPages.addAll(other.errorPages);
        if (jspConfig == null) {
            jspConfig = other.jspConfig;
        } else if (other.jspConfig != null) {
            jspConfig.merge(other.jspConfig);
        }
        securityConstraints.addAll(other.securityConstraints);
        if (loginConfig == null) {
            loginConfig = other.loginConfig; // XXX check
        }
        securityRoles.addAll(other.securityRoles);

        // resources and references
        envEntries.addAll(other.envEntries);
        ejbRefs.addAll(other.ejbRefs);
        serviceRefs.addAll(other.serviceRefs);
        resourceRefs.addAll(other.resourceRefs);
        resourceEnvRefs.addAll(other.resourceEnvRefs);
        messageDestinationRefs.addAll(other.messageDestinationRefs);
        persistenceContextRefs.addAll(other.persistenceContextRefs);
        persistenceUnitRefs.addAll(other.persistenceUnitRefs);
        postConstructs.addAll(other.postConstructs);
        preDestroys.addAll(other.preDestroys);
        dataSourceDefs.addAll(other.dataSourceDefs);
        jmsConnectionFactories.addAll(other.jmsConnectionFactories);
        jmsDestinations.addAll(other.jmsDestinations);
        mailSessions.addAll(other.mailSessions);
        connectionFactories.addAll(other.connectionFactories);
        administeredObjects.addAll(other.administeredObjects);
        messageDestinations.addAll(other.messageDestinations);

        for (String locale : other.localeEncodingMappings.keySet()) {
            if (!localeEncodingMappings.containsKey(locale)) {
                localeEncodingMappings.put(locale, other.localeEncodingMappings.get(locale));
            }
        }
    }

    /**
     * Return all the resources that need to be instantiated and bound in
     * JNDI.
     */
    Collection<Resource> getResources() {
        Set<Resource> resources = new LinkedHashSet<>();
        resources.addAll(dataSourceDefs);
        resources.addAll(jmsConnectionFactories);
        resources.addAll(jmsDestinations);
        resources.addAll(mailSessions);
        resources.addAll(connectionFactories);
        resources.addAll(administeredObjects);
        return resources;
    }

    /**
     * Returns all the resource references that need to have injection
     * target handling.
     */
    Collection<Injectable> getInjectables() {
        Set<Injectable> injectables = new LinkedHashSet<>();
        injectables.addAll(envEntries);
        injectables.addAll(ejbRefs);
        injectables.addAll(serviceRefs);
        injectables.addAll(resourceRefs);
        injectables.addAll(resourceEnvRefs);
        injectables.addAll(messageDestinationRefs);
        injectables.addAll(persistenceContextRefs);
        injectables.addAll(persistenceUnitRefs);
        injectables.addAll(administeredObjects);
        return injectables;
    }

    boolean isSecurityConstraintTarget(String urlPattern) {
        for (SecurityConstraint sc : securityConstraints) {
            for (ResourceCollection rc : sc.resourceCollections) {
                return rc.matchesExact(urlPattern);
            }
        }
        return false;
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

    // -- Setters --
    
    void addContextParam(InitParam contextParam) {
        contextParams.put(contextParam.name, contextParam);
    }

    void addFilterDef(FilterDef filterDef) {
        filterDefs.put(filterDef.name, filterDef);
    }

    void addFilterMapping(FilterMapping filterMapping) {
        filterMappings.add(filterMapping);
    }

    void addListenerDef(ListenerDef listenerDef) {
        listenerDefs.add(listenerDef);
    }

    void addServletDef(ServletDef servletDef) {
        servletDefs.put(servletDef.name, servletDef);
    }

    void addServletMapping(ServletMapping servletMapping) {
        servletMappings.add(servletMapping);
    }

    void setSessionConfig(SessionConfig sessionConfig) {
        this.sessionConfig = sessionConfig;
    }

    void addMimeMapping(MimeMapping mimeMapping) {
        mimeMappings.add(mimeMapping);
    }

    void addErrorPage(ErrorPage errorPage) {
        errorPages.add(errorPage);
    }

    void addSecurityConstraint(SecurityConstraint securityConstraint) {
        securityConstraints.add(securityConstraint);
        authentication = true;
    }

    void setLoginConfig(LoginConfig loginConfig) {
        this.loginConfig = loginConfig;
        authentication = true;
    }

    void addSecurityRole(SecurityRole securityRole) {
        securityRoles.add(securityRole);
    }

    void addEnvEntry(EnvEntry envEntry) {
        envEntries.add(envEntry);
    }

    void addEjbRef(EjbRef ejbRef) {
        ejbRefs.add(ejbRef);
    }

    void addServiceRef(ServiceRef serviceRef) {
        serviceRefs.add(serviceRef);
    }

    void addResourceRef(ResourceRef resourceRef) {
        resourceRefs.add(resourceRef);
    }

    void addResourceEnvRef(ResourceEnvRef resourceEnvRef) {
        resourceEnvRefs.add(resourceEnvRef);
    }

    void addMessageDestinationRef(MessageDestinationRef messageDestinationRef) {
        messageDestinationRefs.add(messageDestinationRef);
    }

    void addPersistenceContextRef(PersistenceContextRef persistenceContextRef) {
        persistenceContextRefs.add(persistenceContextRef);
    }

    void addPersistenceUnitRef(PersistenceUnitRef persistenceUnitRef) {
        persistenceUnitRefs.add(persistenceUnitRef);
    }

    void addPostConstruct(LifecycleCallback postConstruct) {
        postConstructs.add(postConstruct);
    }

    void addPreDestroy(LifecycleCallback preDestroy) {
        preDestroys.add(preDestroy);
    }

    void addDataSourceDef(DataSourceDef dataSourceDef) {
        dataSourceDefs.add(dataSourceDef);
    }

    void addJmsConnectionFactory(JmsConnectionFactory jmsConnectionFactory) {
        jmsConnectionFactories.add(jmsConnectionFactory);
    }

    void addJmsDestination(JmsDestination jmsDestination) {
        jmsDestinations.add(jmsDestination);
    }

    void addMailSession(MailSession mailSession) {
        mailSessions.add(mailSession);
    }

    void addConnectionFactory(ConnectionFactory connectionFactory) {
        connectionFactories.add(connectionFactory);
    }

    void addAdministeredObject(AdministeredObject administeredObject) {
        administeredObjects.add(administeredObject);
    }

    void addMessageDestination(MessageDestination messageDestination) {
        messageDestinations.add(messageDestination);
    }

    void addLocaleEncodingMapping(String locale, String encoding) {
        localeEncodingMappings.put(locale, encoding);
    }

    /**
     * Resolve all the filterMappings and servletMappings.
     * This cannot be done until the entire web deployment descriptor is
     * parsed, since you can define filter-mapping and servlet-mapping
     * elements before the filter and servlet elements they reference.
     */
    void resolve() {
        for (FilterMapping filterMapping : filterMappings) {
            filterMapping.filterDef = filterDefs.get(filterMapping.filterName);
            if (filterMapping.filterDef == null) {
                String message = Context.L10N.getString("warn.no_filter_def");
                Context.LOGGER.warning(message);
            }
            filterMapping.servletDefs.clear();
            for (String servletName : filterMapping.servletNames) {
                ServletDef servletDef = servletDefs.get(servletName);
                if (servletDef == null) {
                    String message = Context.L10N.getString("warn.no_servlet_def");
                    Context.LOGGER.warning(message);
                } else {
                    filterMapping.servletDefs.add(servletDef);
                }
            }
        }
        for (ServletMapping servletMapping : servletMappings) {
            servletMapping.servletDef = servletDefs.get(servletMapping.servletName);
            if (servletMapping.servletDef == null) {
                String message = Context.L10N.getString("warn.no_servlet_def");
                Context.LOGGER.warning(message);
            }
        }
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
