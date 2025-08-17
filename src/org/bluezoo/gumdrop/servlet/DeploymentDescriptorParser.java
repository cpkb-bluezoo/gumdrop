/*
 * DeploymentDescriptorParser.java
 * Copyright (C) 2005, 2013, 2025 Chris Burdess
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

import org.xml.sax.Attributes;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.MessageFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.logging.Logger;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import javax.servlet.DispatcherType;

/**
 * Parses a web application deployment descriptor, populating an application
 * context.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class DeploymentDescriptorParser extends DefaultHandler implements ErrorHandler {

    static final Logger LOGGER = Logger.getLogger(DeploymentDescriptorParser.class.getName());
    static final ResourceBundle L10N = ResourceBundle.getBundle("org.bluezoo.gumdrop.servlet.L10N");

    enum State {
        INIT(""),

        // Root element
        WEB_APP("web-app"),
        WEB_FRAGMENT("web-fragment"),

        // 1. web-app
        MODULE_NAME("module-name"),

        // descriptionGroup
        DESCRIPTION("description"), // 2.
        DISPLAY_NAME("display-name"), // 3.
        ICON("icon"), // 4.

        DISTRIBUTABLE("distributable"), // 5.
        CONTEXT_PARAM("context-param"), // 6.
        FILTER("filter"), // 7.
        FILTER_MAPPING("filter-mapping"), // 8.
        LISTENER("listener"), // 9.
        SERVLET("servlet"),
        SERVLET_MAPPING("servlet-mapping"),
        SESSION_CONFIG("session-config"),
        MIME_MAPPING("mime-mapping"),
        WELCOME_FILE_LIST("welcome-file-list"),
        ERROR_PAGE("error-page"),
        JSP_CONFIG("jsp-config"),
        SECURITY_CONSTRAINT("security-constraint"),
        LOGIN_CONFIG("login-config"),
        SECURITY_ROLE("security-role"),

        // jdniEnvironmentRefsGroup
        ENV_ENTRY("env-entry"),
        EJB_REF("ejb-ref"),
        EJB_LOCAL_REF("ejb-local-ref"),
        SERVICE_REF("service-ref"),
        RESOURCE_REF("resource-ref"),
        RESOURCE_ENV_REF("resource-env-ref"),
        MESSAGE_DESTINATION_REF("message-destination-ref"),
        PERSISTENCE_CONTEXT_REF("persistence-context-ref"),
        PERSISTENCE_UNIT_REF("persistence-unit-ref"),
        POST_CONSTRUCT("post-construct"),
        PRE_DESTROY("pre-destroy"),
        DATA_SOURCE("data-source"),
        JMS_CONNECTION_FACTORY("jms-connection-factory"),
        JMS_DESTINATION("jms-destination"),
        MAIL_SESSION("mail-session"),
        CONNECTION_FACTORY("connection-factory"),
        ADMINISTERED_OBJECT("administered-object"),

        MESSAGE_DESTINATION("message-destination"),
        LOCALE_ENCODING_MAPPING_LIST("locale-encoding-mapping-list"),

        DEFAULT_CONTEXT_PATH("default-context-path"), // 30.
        REQUEST_CHARACTER_ENCODING("request-character-encoding"),
        RESPONSE_CHARACTER_ENCODING("response-character-encoding"),
        DENY_UNCOVERED_HTTP_METHODS("deny-uncovered-http-methods"), // 18.
        ABSOLUTE_ORDERING("absolute-ordering"),
        // end top-level elements

        // 4. icon
        SMALL_ICON("small-icon"),
        LARGE_ICON("large-icon"),

        // 7. filter
        FILTER_NAME("filter-name"),
        FILTER_CLASS("filter-class"),
        ASYNC_SUPPORTED("async-supported"),

        // init-param
        INIT_PARAM("init-param"),

        // paramValueType
        PARAM_NAME("param-name"),
        PARAM_VALUE("param-value"),

        // filter-mapping, servlet-mapping
        URL_PATTERN("url-pattern"),
        DISPATCHER("dispatcher"),

        // 9. listener
        LISTENER_CLASS("listener-class"),

        // 10. servlet
        SERVLET_NAME("servlet-name"),
        SERVLET_CLASS("servlet-class"),
        JSP_FILE("jsp-file"),
        LOAD_ON_STARTUP("load-on-startup"),
        ENABLED("enabled"),
        RUN_AS("run-as"),
        SECURITY_ROLE_REF("security-role-ref"),
        ROLE_NAME("role-name"),
        ROLE_LINK("role-link"),
        MULTIPART_CONFIG("multipart-config"),
        MAX_FILE_SIZE("max-file-size"),
        MAX_REQUEST_SIZE("max-request-size"),
        FILE_SIZE_THRESHOLD("file-size-threshold"),

        // 12. session-config
        SESSION_TIMEOUT("session-timeout"),
        COOKIE_CONFIG("cookie-config"),
        TRACKING_MODE("tracking-mode"),

        // cookie-config
        DOMAIN("domain"),
        PATH("path"),
        COMMENT("comment"),
        HTTP_ONLY("http-only"),
        SECURE("secure"),
        MAX_AGE("max-age"),
        SAME_SITE("same-site"),

        // 13. mime-mapping
        EXTENSION("extension"),
        MIME_TYPE("mime-type"),

        // 14. welcome-file-list
        WELCOME_FILE("welcome-file"),

        // 15. error-page
        ERROR_CODE("error-code"),
        EXCEPTION_TYPE("exception-type"),
        LOCATION("location"),

        // 16. jsp-config
        TAGLIB("taglib"),
        TAGLIB_URI("taglib-uri"),
        TAGLIB_LOCATION("taglib-location"),
        JSP_PROPERTY_GROUP("jsp-property-group"),

        // 17. security-constraint
        WEB_RESOURCE_COLLECTION("web-resource-collection"),
        WEB_RESOURCE_NAME("web-resource-name"),
        HTTP_METHOD("http-method"),
        HTTP_METHOD_OMISSION("http-method-omission"),
        AUTH_CONSTRAINT("auth-constraint"),
        USER_DATA_CONSTRAINT("user-data-constraint"),
        TRANSPORT_GUARANTEE("transport-guarantee"),

        // 19. login-config
        AUTH_METHOD("auth-method"),
        REALM_NAME("realm-name"),
        FORM_LOGIN_CONFIG("form-login-config"),
        FORM_LOGIN_PAGE("form-login-page"),
        FORM_ERROR_PAGE("form-error-page"),

        // 21. env-entry
        ENV_ENTRY_NAME("env-entry-name"),
        ENV_ENTRY_TYPE("env-entry-type"),
        ENV_ENTRY_VALUE("env-entry-value"),
        MAPPED_NAME("mapped-name"),
        INJECTION_TARGET("injection-target"),
        INJECTION_TARGET_CLASS("injection-target-class"),
        INJECTION_TARGET_NAME("injection-target-name"),
        LOOKUP_NAME("lookup-name"),

        // 22. ejb-ref
        EJB_REF_NAME("ejb-ref-name"),
        EJB_REF_TYPE("ejb-ref-type"),
        HOME("home"),
        REMOTE("remote"),
        EJB_LINK("ejb-link"),

        // 23. ejb-local-ref
        LOCAL_HOME("local-home"),
        LOCAL("local"),

        // 24. service-ref
        SERVICE_REF_NAME("service-ref-name"),
        SERVICE_INTERFACE("service-interface"),
        SERVICE_REF_TYPE("service-ref-type"),
        WDSL_FILE("wdsl-file"),
        JAXRPC_MAPPING_FILE("jaxrpc-mapping-file"),
        SERVICE_QNAME("service-qname"),
        PORT_COMPONENT_REF("port-component-ref"),
        HANDLER("handler"),
        HANDLER_CHAINS("handler-chains"),

        // 25. resource-ref
        RES_REF_NAME("res-ref-name"),
        RES_TYPE("res-type"),
        RES_AUTH("res-auth"),
        RES_SHARING_SCOPE("res-sharing-scope"),

        // 26. resource-env-ref
        RESOURCE_ENV_REF_NAME("resource-env-ref-name"),
        RESOURCE_ENV_REF_TYPE("resource-env-ref-type"),

        // 27. message-destination-ref
        MESSAGE_DESTINATION_REF_NAME("message-destination-ref-name"),
        MESSAGE_DESTINATION_TYPE("message-destination-type"),
        MESSAGE_DESTINATION_USAGE("message-destination-usage"),
        MESSAGE_DESTINATION_LINK("message-destination-link"),
        
        // 28. message-destination
        MESSAGE_DESTINATION_NAME("message-destination-name"),

        // 29. locale-encoding-mapping-list
        LOCALE_ENCODING_MAPPING("locale-encoding-mapping"),
        LOCALE("locale"),
        ENCODING("encoding"),

        // absolute-ordering
        OTHERS("others"),

        // jsp-property-group
        EL_IGNORED("el-ignored"),
        SCRIPTING_INVALID("scripting-invalid"),
        PAGE_ENCODING("page-encoding"),
        INCLUDE_PRELUDE("include-prelude"),
        INCLUDE_CODA("include-coda"),
        DEFAULT_CONTENT_TYPE("default-content-type"),
        BUFFER("buffer"),
        TRIM_DIRECTIVE_WHITESPACES("trim-directive-whitespaces"),

        // web-fragment.xml
        NAME("name"),
        ORDERING("ordering"),
        BEFORE("before"),
        AFTER("after"),

        // Other
        UNKNOWN(null);

        final String elementName;

        private State(String elementName) {
            this.elementName = elementName;
        }

    }

    static Map<String,State> STATE_LOOKUP;
    static {
        STATE_LOOKUP = new HashMap<>();
        for (State state : State.values()) {
            STATE_LOOKUP.put(state.elementName, state);
        }
    }

    /**
     * The deployment descriptor being built.
     * This may be a Context or a WebFragment.
     */
    DeploymentDescriptor descriptor;

    /**
     * Sink for character data.
     */
    StringBuilder buf;

    /**
     * SAX locator.
     */
    Locator loc;

    /**
     * Parser state.
     */
    Deque<State> states = new ArrayDeque<>();

    /**
     * Target entity stack.
     */
    Deque targets = new ArrayDeque();

    State mode;
    MessageDigest digest;

    DeploymentDescriptorParser() {
        try {
            digest = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            // fatal
            RuntimeException e2 = new RuntimeException("No MD5 support in JRE");
            e2.initCause(e);
            throw e2;
        }
    }

    public void parse(DeploymentDescriptor descriptor, URL url) throws IOException, SAXException {
        this.descriptor = descriptor;
        try (InputStream in = url.openStream()) {
            DigestInputStream di = new DigestInputStream(in, digest);
            InputSource source = new InputSource(di);
            source.setSystemId(url.toString());
            parse(source);
        }
    }

    /**
     * Returns the MD5 digest of the deployment descriptor parsed by this
     * object.
     */
    byte[] getDigest() {
        return digest.digest();
    }

    private void parse(InputSource in) throws IOException, SAXException {
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(true);
            SAXParser parser = factory.newSAXParser();
            XMLReader reader = parser.getXMLReader();
            reader.setContentHandler(this);
            reader.setErrorHandler(this);
            reader.parse(in);
        } catch (ParserConfigurationException e) {
            SAXException e2 = new SAXException(e.getMessage());
            e2.initCause(e);
            throw e2;
        }
    }

    void pushText() {
        if (buf == null) {
            buf = new StringBuilder();
        } else {
            buf.setLength(0);
        }
    }

    String popText() {
        String ret = buf.toString();
        buf = null;
        return ret.trim(); // SRV 14.2
    }

    /**
     * NB the presence of an element indicates a true value, unless its text
     * content is explicitly set to "false".
     */
    boolean popBoolean() {
        return !"false".equals(popText());
    }

    void pushState(State state) {
        states.addLast(state);
    }

    State peekState() {
        return states.getLast();
    }

    State popState() {
        return states.removeLast();
    }

    void pushTarget(Object target) {
        targets.addLast(target);
    }

    Object peekTarget() {
        return targets.getLast();
    }

    Object popTarget() {
        return targets.removeLast();
    }

    public void setLocator(Locator loc) {
        this.loc = loc;
    }

    public void startDocument() throws SAXException {
        states.clear();
        pushState(State.INIT);
        targets = new LinkedList();
        pushTarget(descriptor);
    }

    public void startElement(String uri, String localName, String qName, Attributes atts) throws SAXException {
        String name = (localName == null) ? qName : localName;
        State parentState = peekState();
        State state = STATE_LOOKUP.get(name);
        if (state == null) {
            state = State.UNKNOWN;
        }
        switch (parentState) {
            case INIT:
                if (state == State.WEB_APP || state == State.WEB_FRAGMENT) {
                    String version = atts.getValue("version");
                    // It is not really legal to have no version attribute,
                    // but let's be gracious...
                    if (version != null) {
                        try {
                            int di = version.indexOf('.');
                            descriptor.majorVersion = Integer.parseInt(version.substring(0, di));
                            descriptor.minorVersion = Integer.parseInt(version.substring(di + 1));
                        } catch (Exception e) {
                            throw new SAXParseException("invalid " + state.elementName + " version: " + version, loc);
                        }
                    }
                    String metadataComplete = atts.getValue("metadata-complete");
                    if (state == State.WEB_APP && "true".equals(metadataComplete) && descriptor instanceof Context) {
                        ((Context) peekTarget()).metadataComplete = true;
                    }
                }
                break;
            case WEB_APP:
                switch (state) {
                    case MODULE_NAME:
                    case DEFAULT_CONTEXT_PATH:
                    case REQUEST_CHARACTER_ENCODING:
                    case RESPONSE_CHARACTER_ENCODING:
                        pushText();
                        break;
                    case ABSOLUTE_ORDERING:
                        // TODO
                        break;
                }
                // fall through
            case WEB_FRAGMENT:
                // elements common to both WEB_APP and WEB_FRAGMENT
                switch (state) {
                    case DESCRIPTION:
                    case DISPLAY_NAME:
                    case DISTRIBUTABLE:
                        pushText();
                        break;
                    case CONTEXT_PARAM:
                        pushTarget(new InitParam());
                        break;
                    case FILTER:
                        pushTarget(new FilterDef());
                        break;
                    case FILTER_MAPPING:
                        pushTarget(new FilterMapping());
                        break;
                    case LISTENER:
                        pushTarget(new ListenerDef());
                        break;
                    case SERVLET:
                        pushTarget(new ServletDef());
                        break;
                    case SERVLET_MAPPING:
                        pushTarget(new ServletMapping());
                        break;
                    case SESSION_CONFIG:
                        pushTarget(new SessionConfig());
                        break;
                    case MIME_MAPPING:
                        pushTarget(new MimeMapping());
                        break;
                    case ERROR_PAGE:
                        pushTarget(new ErrorPage());
                        break;
                    case JSP_CONFIG:
                        pushTarget(new JspConfig());
                        break;
                    case SECURITY_CONSTRAINT:
                        pushTarget(new SecurityConstraint());
                        descriptor.authentication = true;
                        break;
                    case LOGIN_CONFIG:
                        pushTarget(new LoginConfig());
                        descriptor.authentication = true;
                        break;
                    case SECURITY_ROLE:
                        pushTarget(new SecurityRole());
                        break;
                    case ENV_ENTRY:
                    case EJB_REF:
                    case EJB_LOCAL_REF:
                    case SERVICE_REF:
                    case RESOURCE_REF:
                    case RESOURCE_ENV_REF:
                    case MESSAGE_DESTINATION_REF:
                    case PERSISTENCE_CONTEXT_REF:
                    case PERSISTENCE_UNIT_REF:
                    case POST_CONSTRUCT:
                    case PRE_DESTROY:
                    case DATA_SOURCE:
                    case JMS_CONNECTION_FACTORY:
                    case JMS_DESTINATION:
                    case MAIL_SESSION:
                    case CONNECTION_FACTORY:
                    case ADMINISTERED_OBJECT:
                    case MESSAGE_DESTINATION:
                        //pushTarget(new HashMap());
                        break;
                }
                // WEB_FRAGMENT only
                if (parentState == State.WEB_FRAGMENT) {
                    switch (state) {
                        case NAME:
                            pushText();
                            break;
                        // TODO relative ordering
                    }
                }
                break;
            case ICON:
                switch (state) {
                    case SMALL_ICON:
                    case LARGE_ICON:
                        pushText();
                }
                break;
            case CONTEXT_PARAM:
            case INIT_PARAM:
                switch (state) {
                    case DESCRIPTION:
                    case PARAM_NAME:
                    case PARAM_VALUE:
                        pushText();
                }
                break;
            case FILTER:
                switch (state) {
                    case DESCRIPTION:
                    case DISPLAY_NAME:
                    case FILTER_NAME:
                    case FILTER_CLASS:
                        pushText();
                        break;
                    case INIT_PARAM:
                        pushTarget(new InitParam());
                        break;
                }
                break;
            case FILTER_MAPPING:
                switch (state) {
                    case FILTER_NAME:
                    case URL_PATTERN:
                    case SERVLET_NAME:
                    case DISPATCHER:
                        pushText();
                }
                break;
            case LISTENER:
                switch (state) {
                    case DESCRIPTION:
                    case DISPLAY_NAME:
                    case LISTENER_CLASS:
                        pushText();
                        break;
                }
                break;
            case SERVLET:
                switch (state) {
                    case DESCRIPTION:
                    case DISPLAY_NAME:
                    case SERVLET_NAME:
                    case SERVLET_CLASS:
                    case JSP_FILE:
                    case LOAD_ON_STARTUP:
                        pushText();
                        break;
                    case INIT_PARAM:
                        pushTarget(new InitParam());
                        break;
                    case RUN_AS:
                    case SECURITY_ROLE_REF:
                        pushTarget(new SecurityRole());
                        break;
                    case MULTIPART_CONFIG:
                        pushTarget(new MultipartConfigDef());
                        break;
                }
                break;
            case RUN_AS:
                switch (state) {
                    case DESCRIPTION:
                    case ROLE_NAME:
                        pushText();
                }
                break;
            case SERVLET_MAPPING:
                switch (state) {
                    case SERVLET_NAME:
                    case URL_PATTERN:
                        pushText();
                }
                break;
            case SESSION_CONFIG:
                switch (state) {
                    case SESSION_TIMEOUT:
                    case TRACKING_MODE:
                        pushText();
                        break;
                    case COOKIE_CONFIG:
                        pushTarget(new CookieConfig());
                        break;
                }
                break;
            case COOKIE_CONFIG:
                switch (state) {
                    case NAME:
                    case DOMAIN:
                    case PATH:
                    case COMMENT:
                    case HTTP_ONLY:
                    case SECURE:
                    case MAX_AGE:
                    case SAME_SITE:
                        pushText();
                        break;
                }
                break;
            case MIME_MAPPING:
                switch (state) {
                    case EXTENSION:
                    case MIME_TYPE:
                        pushText();
                }
                break;
            case WELCOME_FILE_LIST:
                switch (state) {
                    case WELCOME_FILE:
                        pushText();
                }
                break;
            case ERROR_PAGE:
                switch (state) {
                    case ERROR_CODE:
                    case EXCEPTION_TYPE:
                    case LOCATION:
                        pushText();
                }
                break;
            case JSP_CONFIG:
                switch (state) {
                    case TAGLIB:
                        pushTarget(new Taglib());
                        break;
                    case JSP_PROPERTY_GROUP:
                        pushTarget(new JspPropertyGroup());
                        break;
                }
                break;
            case TAGLIB:
                switch (state) {
                    case TAGLIB_URI:
                    case TAGLIB_LOCATION:
                        pushText();
                }
                break;
            case JSP_PROPERTY_GROUP:
                switch (state) {
                    case DESCRIPTION:
                    case URL_PATTERN:
                    case EL_IGNORED:
                    case PAGE_ENCODING:
                    case SCRIPTING_INVALID:
                    case INCLUDE_PRELUDE:
                    case INCLUDE_CODA:
                    case BUFFER:
                    case TRIM_DIRECTIVE_WHITESPACES:
                        pushText();
                }
                break;
            case SECURITY_CONSTRAINT:
                switch (state) {
                    case DISPLAY_NAME:
                        pushText();
                        break;
                    case WEB_RESOURCE_COLLECTION:
                        pushTarget(new ResourceCollection());
                        break;
                    case AUTH_CONSTRAINT:
                        pushTarget(new SecurityRole());
                        break;
                }
                break;
            case WEB_RESOURCE_COLLECTION:
                switch (state) {
                    case WEB_RESOURCE_NAME:
                    case DESCRIPTION:
                    case URL_PATTERN:
                    case HTTP_METHOD:
                    case HTTP_METHOD_OMISSION:
                        pushText();
                }
                break;
            case AUTH_CONSTRAINT:
                switch (state) {
                    case ROLE_NAME:
                        pushText();
                }
                break;
            case USER_DATA_CONSTRAINT:
                switch (state) {
                    case TRANSPORT_GUARANTEE:
                        pushText();
                }
                break;
            case LOGIN_CONFIG:
                switch (state) {
                    case AUTH_METHOD:
                    case REALM_NAME:
                        pushText();
                }
                break;
            case FORM_LOGIN_CONFIG:
                switch (state) {
                    case FORM_LOGIN_PAGE:
                    case FORM_ERROR_PAGE:
                        pushText();
                }
                break;
            case SECURITY_ROLE:
                switch (state) {
                    case DESCRIPTION:
                    case ROLE_NAME:
                        pushText();
                }
                break;
            /*case ENV_ENTRY:
                switch (state) {
                    case DESCRIPTION:
                    case ENV_ENTRY_NAME:
                    case ENV_ENTRY_TYPE:
                    case ENV_ENTRY_VALUE:
                        pushText();
                }
                break;
            case EJB_REF:
                switch (state) {
                    case DESCRIPTION:
                    case EJB_REF_NAME:
                    case EJB_REF_TYPE:
                    case HOME:
                    case REMOTE:
                    case EJB_LINK:
                        pushText();
                }
                break;
            case EJB_LOCAL_REF:
                switch (state) {
                    case DESCRIPTION:
                    case EJB_REF_NAME:
                    case EJB_REF_TYPE:
                    case LOCAL_HOME:
                    case LOCAL:
                    case EJB_LINK:
                        pushText();
                }
                break;
            case SERVICE_REF:
                switch (state) {
                    case DESCRIPTION:
                    case DISPLAY_NAME:
                    case SERVICE_REF_NAME:
                    case SERVICE_INTERFACE:
                    case WDSL_FILE:
                    case JAXRPC_MAPPING_FILE:
                    case SERVICE_QNAME:
                    case PORT_COMPONENT_REF:
                    case HANDLER:
                        pushText();
                }
                break;
            case RESOURCE_REF:
                switch (state) {
                    case DESCRIPTION:
                    case RES_REF_NAME:
                    case RES_TYPE:
                    case RES_AUTH:
                    case RES_SHARING_SCOPE:
                        pushText();
                }
                break;
            case RESOURCE_ENV_REF:
                switch (state) {
                    case DESCRIPTION:
                    case RESOURCE_ENV_REF_NAME:
                    case RESOURCE_ENV_REF_TYPE:
                        pushText();
                }
                break;
            case MESSAGE_DESTINATION_REF:
                switch (state) {
                    case DESCRIPTION:
                    case MESSAGE_DESTINATION_REF_NAME:
                    case MESSAGE_DESTINATION_TYPE:
                    case MESSAGE_DESTINATION_USAGE:
                    case MESSAGE_DESTINATION_LINK:
                        pushText();
                }
                break;
            case MESSAGE_DESTINATION:
                switch (state) {
                    case DESCRIPTION:
                    case DISPLAY_NAME:
                    case MESSAGE_DESTINATION_NAME:
                        pushText();
                }
                break;*/
            case LOCALE_ENCODING_MAPPING_LIST:
                switch (state) {
                    case LOCALE_ENCODING_MAPPING:
                        pushTarget(new LocaleEncodingMapping());
                }
                break;
            case LOCALE_ENCODING_MAPPING:
                switch (state) {
                    case LOCALE:
                    case ENCODING:
                        pushText();
                }
                break;
            case MULTIPART_CONFIG:
                switch (state) {
                    case LOCATION:
                    case MAX_FILE_SIZE:
                    case MAX_REQUEST_SIZE:
                    case FILE_SIZE_THRESHOLD:
                        pushText();
                }
                break;
            case ABSOLUTE_ORDERING:
                switch (state) {
                    case NAME:
                        pushText();
                }
                break;
            case BEFORE:
            case AFTER:
                switch (state) {
                    case NAME:
                        pushText();
                }
                break;
        }
        pushState(state);
    }

    public void endElement(String uri, String localName, String qName) throws SAXException {
        String name = (localName == null) ? qName : localName;
        State state = popState();
        State parentState = peekState();

        WebFragment fragment;

        switch (parentState) {
            case WEB_APP:
                switch (state) {
                    case MODULE_NAME:
                        ((Context) peekTarget()).moduleName = popText();
                        break;
                    case DEFAULT_CONTEXT_PATH:
                        ((Context) peekTarget()).defaultContextPath = popText();
                        break;
                    case REQUEST_CHARACTER_ENCODING:
                        ((Context) peekTarget()).requestCharacterEncoding = popText();
                        break;
                    case RESPONSE_CHARACTER_ENCODING:
                        ((Context) peekTarget()).responseCharacterEncoding = popText();
                        break;
                    case DISTRIBUTABLE:
                        ((Context) peekTarget()).distributable = popBoolean();
                        break;
                }
                // fall through
            case WEB_FRAGMENT:
                switch (state) {
                    case DESCRIPTION:
                        ((DeploymentDescriptor) peekTarget()).description = popText();
                        break;
                    case DISPLAY_NAME:
                        ((DeploymentDescriptor) peekTarget()).displayName = popText();
                        break;
                    case CONTEXT_PARAM:
                        InitParam contextParam = (InitParam) popTarget();
                        ((DeploymentDescriptor) peekTarget()).contextParams.put(contextParam.name, contextParam);
                        break;
                    case FILTER:
                        FilterDef filterDef = (FilterDef) popTarget();
                        ((DeploymentDescriptor) peekTarget()).filterDefs.put(filterDef.name, filterDef);
                        break;
                    case FILTER_MAPPING:
                        FilterMapping filterMapping = (FilterMapping) popTarget();
                        ((DeploymentDescriptor) peekTarget()).filterMappings.add(filterMapping);
                        break;
                    case LISTENER:
                        ListenerDef listenerDef = (ListenerDef) popTarget();
                        ((DeploymentDescriptor) peekTarget()).listenerDefs.add(listenerDef);
                        break;
                    case SERVLET:
                        ServletDef servletDef = (ServletDef) popTarget();
                        ((DeploymentDescriptor) peekTarget()).servletDefs.put(servletDef.name, servletDef);
                        break;
                    case SERVLET_MAPPING:
                        ServletMapping servletMapping = (ServletMapping) popTarget();
                        ((DeploymentDescriptor) peekTarget()).servletMappings.add(servletMapping);
                        break;
                    case SESSION_CONFIG:
                        SessionConfig sessionConfig = (SessionConfig) popTarget();
                        ((DeploymentDescriptor) peekTarget()).sessionConfig = sessionConfig;
                        break;
                    case MIME_MAPPING:
                        MimeMapping mimeMapping = (MimeMapping) popTarget();
                        ((DeploymentDescriptor) peekTarget()).mimeMappings.add(mimeMapping);
                        break;
                    case ERROR_PAGE:
                        ErrorPage errorPage = (ErrorPage) popTarget();
                        ((DeploymentDescriptor) peekTarget()).errorPages.add(errorPage);
                        break;
                    case JSP_CONFIG:
                        JspConfig jspConfig = (JspConfig) popTarget();
                        ((DeploymentDescriptor) peekTarget()).jspConfigs.add(jspConfig);
                        break;
                    case SECURITY_CONSTRAINT:
                        SecurityConstraint securityConstraint = (SecurityConstraint) popTarget();
                        ((DeploymentDescriptor) peekTarget()).securityConstraints.add(securityConstraint);
                        ((DeploymentDescriptor) peekTarget()).authentication = true;
                        break;
                    case LOGIN_CONFIG:
                        LoginConfig loginConfig = (LoginConfig) popTarget();
                        ((DeploymentDescriptor) peekTarget()).loginConfig = loginConfig;
                        ((DeploymentDescriptor) peekTarget()).authentication = true;
                        break;
                    case SECURITY_ROLE:
                        SecurityRole securityRole = (SecurityRole) popTarget();
                        ((DeploymentDescriptor) peekTarget()).securityRoles.add(securityRole);
                        break;
                    // TODO jndiEnvironmentRefsGroup
                    // TODO message-destimation
                }
                // WEB_FRAGMENT ONLY
                if (parentState == State.WEB_FRAGMENT) {
                    switch (state) {
                        case NAME:
                            ((WebFragment) peekTarget()).name = popText();
                            break;
                        // TODO ordering
                    }
                }
                break;
            case ICON:
                switch (state) {
                    case SMALL_ICON:
                        ((DescriptionGroup) peekTarget()).smallIcon = popText();
                        break;
                    case LARGE_ICON:
                        ((DescriptionGroup) peekTarget()).largeIcon = popText();
                        break;
                }
                break;
            case CONTEXT_PARAM:
            case INIT_PARAM:
                switch (state) {
                    case DESCRIPTION:
                        ((InitParam) peekTarget()).description = popText();
                        break;
                    case PARAM_NAME:
                        ((InitParam) peekTarget()).name = popText();
                        break;
                    case PARAM_VALUE:
                        ((InitParam) peekTarget()).value = popText();
                        break;
                }
                break;
            case FILTER:
                switch (state) {
                    case DESCRIPTION:
                        ((FilterDef) peekTarget()).description = popText();
                    case DISPLAY_NAME:
                        ((FilterDef) peekTarget()).displayName = popText();
                    case FILTER_NAME:
                        ((FilterDef) peekTarget()).name = popText();
                    case FILTER_CLASS:
                        ((FilterDef) peekTarget()).className = popText();
                        break;
                    case INIT_PARAM:
                        InitParam initParam = (InitParam) popTarget();
                        ((FilterDef) peekTarget()).initParams.put(initParam.name, initParam);
                        break;
                }
                break;
            case FILTER_MAPPING:
                switch (state) {
                    case FILTER_NAME:
                        ((FilterMapping) peekTarget()).name = popText();
                        break;
                    case URL_PATTERN:
                        ((FilterMapping) peekTarget()).urlPattern = popText();
                        break;
                    case SERVLET_NAME:
                        ((FilterMapping) peekTarget()).servletName = popText();
                        break;
                    case DISPATCHER:
                        ((FilterMapping) peekTarget()).dispatchers.add(DispatcherType.valueOf(popText()));
                        break;
                }
                break;
            case LISTENER:
                switch (state) {
                    case DESCRIPTION:
                        ((ListenerDef) peekTarget()).description = popText();
                        break;
                    case DISPLAY_NAME:
                        ((ListenerDef) peekTarget()).displayName = popText();
                        break;
                    case LISTENER_CLASS:
                        ((ListenerDef) peekTarget()).className = popText();
                        break;
                }
                break;
            case SERVLET:
                switch (state) {
                    case DESCRIPTION:
                        ((ServletDef) peekTarget()).description = popText();
                        break;
                    case DISPLAY_NAME:
                        ((ServletDef) peekTarget()).displayName = popText();
                        break;
                    case SERVLET_NAME:
                        ((ServletDef) peekTarget()).name = popText();
                        break;
                    case SERVLET_CLASS:
                        ((ServletDef) peekTarget()).className = popText();
                        break;
                    case JSP_FILE:
                        ((ServletDef) peekTarget()).jspFile = popText();
                        break;
                    case LOAD_ON_STARTUP:
                        String text = popText();
                        try {
                            ((ServletDef) peekTarget()).loadOnStartup = Integer.parseInt(text);
                        } catch (NumberFormatException e) {
                            // Log and continue
                            String message = L10N.getString("warn.invalid_number");
                            message = MessageFormat.format(message, text);
                            LOGGER.warning(message);
                        }
                        break;
                    case INIT_PARAM:
                        InitParam initParam = (InitParam) popTarget();
                        ((ServletDef) peekTarget()).initParams.put(initParam.name, initParam);
                        break;
                    case RUN_AS:
                        SecurityRole runAs = (SecurityRole) popTarget();
                        ((ServletDef) peekTarget()).runAs = runAs.roleName;
                        break;
                    case SECURITY_ROLE_REF:
                        SecurityRole securityRoleRef = (SecurityRole) popTarget();
                        ((ServletDef) peekTarget()).securityRoleRef = securityRoleRef;
                        break;
                    case MULTIPART_CONFIG:
                        MultipartConfigDef multipartConfig = (MultipartConfigDef) popTarget();
                        ((ServletDef) peekTarget()).multipartConfig = multipartConfig;
                        break;
                }
                break;
            case SERVLET_MAPPING:
                switch (state) {
                    case SERVLET_NAME:
                        ((ServletMapping) peekTarget()).name = popText();
                        break;
                    case URL_PATTERN:
                        ((ServletMapping) peekTarget()).urlPattern = popText();
                        break;
                }
                break;
            case SESSION_CONFIG:
                switch (state) {
                    case SESSION_TIMEOUT:
                        String text = popText();
                        try {
                            ((SessionConfig) peekTarget()).sessionTimeout = Integer.parseInt(text);
                        } catch (NumberFormatException e) {
                            // Log and continue
                            String message = L10N.getString("warn.invalid_number");
                            message = MessageFormat.format(message, text);
                            LOGGER.warning(message);
                        }
                        break;
                    case COOKIE_CONFIG:
                        CookieConfig cookieConfig = (CookieConfig) popTarget();
                        ((SessionConfig) peekTarget()).cookieConfig = cookieConfig;
                        break;
                    case TRACKING_MODE:
                        SessionConfig.TrackingMode trackingMode = SessionConfig.TrackingMode.valueOf(popText());
                        ((SessionConfig) peekTarget()).trackingModes.add(trackingMode);
                        break;
                }
                break;
            case COOKIE_CONFIG:
                switch (state) {
                    case NAME:
                        ((CookieConfig) peekTarget()).name = popText();
                        break;
                    case DOMAIN:
                        ((CookieConfig) peekTarget()).domain = popText();
                        break;
                    case PATH:
                        ((CookieConfig) peekTarget()).path = popText();
                        break;
                    case COMMENT:
                        ((CookieConfig) peekTarget()).comment = popText();
                        break;
                    case HTTP_ONLY:
                        ((CookieConfig) peekTarget()).httpOnly = popBoolean();
                        break;
                    case SECURE:
                        ((CookieConfig) peekTarget()).secure = popBoolean();
                        break;
                    case MAX_AGE:
                        String text = popText();
                        try {
                            ((CookieConfig) peekTarget()).maxAge = Integer.parseInt(text);
                        } catch (NumberFormatException e) {
                            // Log and continue
                            String message = L10N.getString("warn.invalid_number");
                            message = MessageFormat.format(message, text);
                            LOGGER.warning(message);
                        }
                        break;
                    case SAME_SITE:
                        ((CookieConfig) peekTarget()).sameSite = CookieConfig.SameSite.valueOf(popText());
                        break;
                }
                break;
            case MIME_MAPPING:
                switch (state) {
                    case EXTENSION:
                        ((MimeMapping) peekTarget()).extension = popText();
                        break;
                    case MIME_TYPE:
                        ((MimeMapping) peekTarget()).mimeType = popText();
                        break;
                }
                break;
            case WELCOME_FILE_LIST:
                switch (state) {
                    case WELCOME_FILE:
                        ((DeploymentDescriptor) peekTarget()).welcomeFiles.add(popText());
                        break;
                }
                break;
            case ERROR_PAGE:
                switch (state) {
                    case ERROR_CODE:
                        String text = popText();
                        try {
                            ((ErrorPage) peekTarget()).errorCode = Integer.parseInt(text);
                        } catch (NumberFormatException e) {
                            // Log and continue
                            String message = L10N.getString("warn.invalid_number");
                            message = MessageFormat.format(message, text);
                            LOGGER.warning(message);
                        }
                        break;
                    case EXCEPTION_TYPE:
                        ((ErrorPage) peekTarget()).exceptionType = popText();
                        break;
                    case LOCATION:
                        ((ErrorPage) peekTarget()).location = popText();
                        break;
                }
                break;
            case JSP_CONFIG:
                switch (state) {
                    case TAGLIB:
                        Taglib taglib = (Taglib) popTarget();
                        ((JspConfig) peekTarget()).taglibs.add(taglib);
                        break;
                    case JSP_PROPERTY_GROUP:
                        JspPropertyGroup jspPropertyGroup = (JspPropertyGroup) popTarget();
                        ((JspConfig) peekTarget()).jspPropertyGroups.add(jspPropertyGroup);
                        break;
                }
                break;
            case TAGLIB:
                switch (state) {
                    case TAGLIB_URI:
                        ((Taglib) peekTarget()).taglibUri = popText();
                        break;
                    case TAGLIB_LOCATION:
                        ((Taglib) peekTarget()).taglibLocation = popText();
                        break;
                }
                break;
            case JSP_PROPERTY_GROUP:
                switch (state) {
                    case DESCRIPTION:
                        ((JspPropertyGroup) peekTarget()).description = popText();
                        break;
                    case URL_PATTERN:
                        ((JspPropertyGroup) peekTarget()).urlPatterns.add(popText());
                        break;
                    case EL_IGNORED:
                        ((JspPropertyGroup) peekTarget()).elIgnored = popBoolean();
                        break;
                    case PAGE_ENCODING:
                        ((JspPropertyGroup) peekTarget()).pageEncoding = popText();
                        break;
                    case SCRIPTING_INVALID:
                        ((JspPropertyGroup) peekTarget()).scriptingInvalid = popBoolean();
                        break;
                    case INCLUDE_PRELUDE:
                        ((JspPropertyGroup) peekTarget()).includePrelude = popText();
                        break;
                    case INCLUDE_CODA:
                        ((JspPropertyGroup) peekTarget()).includeCoda = popText();
                        break;
                    case BUFFER:
                        String text = popText();
                        try {
                            ((JspPropertyGroup) peekTarget()).buffer = Long.parseLong(text);
                        } catch (NumberFormatException e) {
                            // Log and continue
                            String message = L10N.getString("warn.invalid_number");
                            message = MessageFormat.format(message, text);
                            LOGGER.warning(message);
                        }
                        break;
                    case TRIM_DIRECTIVE_WHITESPACES:
                        ((JspPropertyGroup) peekTarget()).trimDirectiveWhitespaces = popBoolean();
                        break;
                }
                break;
            case SECURITY_CONSTRAINT:
                switch (state) {
                    case DISPLAY_NAME:
                        ((SecurityConstraint) peekTarget()).displayName = popText();
                        break;
                    case WEB_RESOURCE_COLLECTION:
                        ResourceCollection resourceCollection = (ResourceCollection) popTarget();
                        ((SecurityConstraint) peekTarget()).resourceCollections.add(resourceCollection);
                        break;
                    case AUTH_CONSTRAINT:
                        SecurityRole securityRole = (SecurityRole) popTarget();
                        ((SecurityConstraint) peekTarget()).authConstraints.add(securityRole.roleName);
                        break;
                }
                break;
            case WEB_RESOURCE_COLLECTION:
                switch (state) {
                    case WEB_RESOURCE_NAME:
                        ((ResourceCollection) peekTarget()).name = popText();
                        break;
                    case DESCRIPTION:
                        ((ResourceCollection) peekTarget()).description = popText();
                        break;
                    case URL_PATTERN:
                        ((ResourceCollection) peekTarget()).urlPatterns.add(popText());
                        break;
                    case HTTP_METHOD:
                        ((ResourceCollection) peekTarget()).httpMethods.add(popText());
                        break;
                    case HTTP_METHOD_OMISSION:
                        ((ResourceCollection) peekTarget()).httpMethodOmissions.add(popText());
                        break;
                }
                break;
            case AUTH_CONSTRAINT:
                switch (state) {
                    case ROLE_NAME:
                        ((SecurityRole) peekTarget()).roleName = popText();
                        break;
                }
                break;
            case USER_DATA_CONSTRAINT:
                switch (state) {
                    case TRANSPORT_GUARANTEE:
                        ((SecurityConstraint) peekTarget()).transportGuarantee = SecurityConstraint.TransportGuarantee.valueOf(popText());
                        break;
                }
                break;
            case LOGIN_CONFIG:
                switch (state) {
                    case AUTH_METHOD:
                        ((LoginConfig) peekTarget()).authMethod = popText();
                        break;
                    case REALM_NAME:
                        ((LoginConfig) peekTarget()).realmName = popText();
                        break;
                }
                break;
            case FORM_LOGIN_CONFIG:
                switch (state) {
                    case FORM_LOGIN_PAGE:
                        ((LoginConfig) peekTarget()).formLoginPage = popText();
                        break;
                    case FORM_ERROR_PAGE:
                        ((LoginConfig) peekTarget()).formErrorPage = popText();
                        break;
                }
                break;
            case SECURITY_ROLE:
                switch (state) {
                    case DESCRIPTION:
                        ((SecurityRole) peekTarget()).description = popText();
                        break;
                    case ROLE_NAME:
                        ((SecurityRole) peekTarget()).roleName = popText();
                        break;
                }
                break;
// TODO jndi elements
            case LOCALE_ENCODING_MAPPING_LIST:
                switch (state) {
                    case LOCALE_ENCODING_MAPPING:
                        LocaleEncodingMapping lem = (LocaleEncodingMapping) popTarget();
                        descriptor.localeEncodingMappings.put(lem.locale, lem.encoding);
                        break;
                }
                break;
            case LOCALE_ENCODING_MAPPING:
                switch (state) {
                    case LOCALE:
                        ((LocaleEncodingMapping) peekTarget()).locale = popText();
                        break;
                    case ENCODING:
                        ((LocaleEncodingMapping) peekTarget()).encoding = popText();
                        break;
                }
                break;
            case ABSOLUTE_ORDERING:
                switch (state) {
                    case NAME:
                        ((Context) peekTarget()).absoluteOrdering.add(popText());
                        break;
                    case OTHERS:
                        ((Context) peekTarget()).absoluteOrdering.add(WebFragment.OTHERS);
                        break;
                }
                break;
            case BEFORE:
                fragment = (WebFragment) peekTarget();
                if (fragment.before == null) {
                    fragment.before = new ArrayList<>();
                }
                switch (state) {
                    case NAME:
                        fragment.before.add(popText());
                        break;
                    case OTHERS:
                        fragment.before.add(WebFragment.OTHERS);
                        break;
                }
                break;
            case AFTER:
                fragment = (WebFragment) peekTarget();
                if (fragment.after == null) {
                    fragment.after = new ArrayList<>();
                }
                switch (state) {
                    case NAME:
                        fragment.after.add(popText());
                        break;
                    case OTHERS:
                        fragment.after.add(WebFragment.OTHERS);
                        break;
                }
                break;
        }
    }

    public void characters(char[] ch, int off, int len) {
        if (buf != null) {
            buf.append(ch, off, len);
        }
    }

    /**
     * Represents a locale-encoding mapping.
     * Used temporarily during parsing.
     */
    static class LocaleEncodingMapping {

        String locale;
        String encoding;
    }

}
