/*
 * DeploymentDescriptorParser.java
 * Copyright (C) 2005, 2013, 2025 Chris Burdess
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

import javax.annotation.Resource;
import javax.persistence.PersistenceContextType;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import javax.servlet.DispatcherType;
import javax.servlet.SessionTrackingMode;
import javax.servlet.annotation.ServletSecurity;

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

        // Injectable
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
        WSDL_FILE("wsdl-file"),
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
        // not documented in servlet spec:
        IS_XML("is-xml"),
        DEFERRED_SYNTAX_ALLOWED_AS_LITERAL("deferred-syntax-allowed-as-literal"),
        ERROR_ON_UNDECLARED_NAMESPACE("error-on-undeclared-namespace"),

        // web-fragment.xml
        NAME("name"),
        ORDERING("ordering"),
        BEFORE("before"),
        AFTER("after"),

        // poorly documented JNDI and reflection stuff
        // JAX-WS
        HANDLER_NAME("handler-name"),
        HANDLER_CLASS("handler-class"),
        HANDLER_CHAIN("handler-chain"),
        PROTOCOL_BINDINGS("protocol-bindings"),
        PORT_NAME_PATTERN("port-name-pattern"),
        // JPA
        PERSISTENCE_CONTEXT_REF_NAME("persistence-context-ref-name"),
        PERSISTENCE_UNIT_REF_NAME("persistence-unit-ref-name"),
        PERSISTENCE_UNIT_NAME("persistence-unit-name"),
        PERSISTENCE_CONTEXT_TYPE("persistence-context-type"),
        // lifecycle callbacks
        LIFECYCLE_CALLBACK_CLASS("lifecycle-callback-class"),
        LIFECYCLE_CALLBACK_METHOD("lifecycle-callback-method"),
        // data-source
        CLASS_NAME("class-name"),
        SERVER_NAME("server-name"),
        PORT_NUMBER("port-number"),
        DATABASE_NAME("database-name"),
        USER("user"),
        PASSWORD("password"),
        URL("url"),
        ISOLATION_LEVEL("isolation-level"),
        INITIAL_POOL_SIZE("initial-pool-size"),
        MAX_POOL_SIZE("max-pool-size"),
        MIN_POOL_SIZE("min-pool-size"),
        MAX_IDLE_TIME("max-idle-time"),
        MAX_STATEMENTS("max-statements"),
        TRANSACTION_ISOLATION("transaction-isolation"),
        PROPERTY("property"),
        VALUE("value"),
        // jms-connection-factory
        INTERFACE_NAME("interface-name"),
        CLIENT_ID("client-id"),
        TRANSACTIONAL("transactional"),
        POOL("pool"),
        CONNECTION_TIMEOUT_IN_SECONDS("connection-timeout-in-seconds"),
        RESOURCE_ADAPTER("resource-adapter"),
        // mail-session
        STORE_PROTOCOL("store-protocol"),
        STORE_PROTOCOL_CLASS("store-protocol-class"),
        TRANSPORT_PROTOCOL("transport-protocol"),
        TRANSPORT_PROTOCOL_CLASS("transport-protocol-class"),
        HOST("host"),
        FROM("from"),
        // connection-factory
        JNDI_NAME("jndi-name"),
        CONNECTION_DEFINITION("connection-definition"),
        CONNECTION_DEFINITION_ID("connection-definition-id"),
        CONFIG_PROPERTY("config-property"),
        CONFIG_PROPERTY_NAME("config-property-name"),
        CONFIG_PROPERTY_VALUE("config-property-value"),
        // administered-object
        ADMINISTERED_OBJECT_INTERFACE("administered-object-interface"),
        ADMINISTERED_OBJECT_CLASS("administered-object-class"),

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
    
    // Keep reference to working SAX parser factory for reuse
    private SAXParserFactory saxParserFactory;

    DeploymentDescriptorParser() {
        this(null);
    }

    DeploymentDescriptorParser(SAXParserFactory saxParserFactory) {
        this.saxParserFactory = saxParserFactory;
        try {
            digest = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            // fatal
            RuntimeException e2 = new RuntimeException("No MD5 support in JRE");
            e2.initCause(e);
            throw e2;
        }
    }

    public void parse(DeploymentDescriptor descriptor, InputStream in) throws IOException, SAXException {
        this.descriptor = descriptor;
        DigestInputStream di = new DigestInputStream(in, digest);
        InputSource source = new InputSource(di);
        //source.setSystemId(url.toString());
        parse(source);
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
            // Use provided factory or create new one
            if (saxParserFactory == null) {
                saxParserFactory = SAXParserFactory.newInstance();
                saxParserFactory.setNamespaceAware(true);
                saxParserFactory.setValidating(false);
            }
            
            SAXParser parser = saxParserFactory.newSAXParser();
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
    
    /**
     * Returns the SAXParserFactory that was successfully used to parse the deployment descriptor.
     * This factory can be reused for other XML parsing tasks like JSP files.
     * 
     * @return the working SAXParserFactory, or null if parsing hasn't been performed
     */
    public SAXParserFactory getSAXParserFactory() {
        return saxParserFactory;
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
                    if ("true".equals(metadataComplete)) {
                        descriptor.metadataComplete = true;
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
                        pushTarget(new EnvEntry());
                        break;
                    case EJB_REF:
                        pushTarget(new EjbRef(true));
                        break;
                    case EJB_LOCAL_REF:
                        pushTarget(new EjbRef(false));
                        break;
                    case SERVICE_REF:
                        pushTarget(new ServiceRef());
                        break;
                    case RESOURCE_REF:
                        pushTarget(new ResourceRef());
                        break;
                    case RESOURCE_ENV_REF:
                        pushTarget(new ResourceEnvRef());
                        break;
                    case MESSAGE_DESTINATION_REF:
                        pushTarget(new MessageDestinationRef());
                        break;
                    case PERSISTENCE_CONTEXT_REF:
                        pushTarget(new PersistenceContextRef());
                        break;
                    case PERSISTENCE_UNIT_REF:
                        pushTarget(new PersistenceUnitRef());
                        break;
                    case POST_CONSTRUCT:
                    case PRE_DESTROY:
                        pushTarget(new LifecycleCallback());
                        break;
                    case DATA_SOURCE:
                        pushTarget(new DataSourceDef());
                        break;
                    case JMS_CONNECTION_FACTORY:
                        pushTarget(new JmsConnectionFactory());
                        break;
                    case JMS_DESTINATION:
                        pushTarget(new JmsDestination());
                        break;
                    case MAIL_SESSION:
                        pushTarget(new MailSession());
                        break;
                    case CONNECTION_FACTORY:
                        pushTarget(new ConnectionFactory());
                        break;
                    case ADMINISTERED_OBJECT:
                        pushTarget(new AdministeredObject());
                        break;
                    case MESSAGE_DESTINATION:
                        pushTarget(new MessageDestination());
                        break;
                }
                // WEB_FRAGMENT only
                if (parentState == State.WEB_FRAGMENT) {
                    switch (state) {
                        case NAME:
                            pushText();
                            break;
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
                    case DEFAULT_CONTENT_TYPE:
                    case BUFFER:
                    case TRIM_DIRECTIVE_WHITESPACES:
                    case IS_XML:
                    case DEFERRED_SYNTAX_ALLOWED_AS_LITERAL:
                    case ERROR_ON_UNDECLARED_NAMESPACE:
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
            case ENV_ENTRY:
                switch (state) {
                    case DESCRIPTION:
                    case ENV_ENTRY_NAME:
                    case ENV_ENTRY_TYPE:
                    case ENV_ENTRY_VALUE:
                    case MAPPED_NAME:
                    case LOOKUP_NAME:
                        pushText();
                        break;
                    case INJECTION_TARGET:
                        pushTarget(new InjectionTarget());
                        break;
                }
                break;
            case INJECTION_TARGET:
                switch (state) {
                    case INJECTION_TARGET_CLASS:
                    case INJECTION_TARGET_NAME:
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
                    case MAPPED_NAME:
                    case LOOKUP_NAME:
                        pushText();
                        break;
                    case INJECTION_TARGET:
                        pushTarget(new InjectionTarget());
                        break;
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
                    case MAPPED_NAME:
                    case LOOKUP_NAME:
                        pushText();
                        break;
                    case INJECTION_TARGET:
                        pushTarget(new InjectionTarget());
                        break;
                }
                break;
            case SERVICE_REF:
                switch (state) {
                    case DESCRIPTION:
                    case DISPLAY_NAME:
                    case SERVICE_REF_NAME:
                    case SERVICE_INTERFACE:
                    case WSDL_FILE:
                    case JAXRPC_MAPPING_FILE:
                    case SERVICE_QNAME:
                    case PORT_COMPONENT_REF:
                    case MAPPED_NAME:
                    case LOOKUP_NAME:
                        pushText();
                        break;
                    case HANDLER:
                        pushTarget(new HandlerDef());
                        break;
                    case INJECTION_TARGET:
                        pushTarget(new InjectionTarget());
                        break;
                }
                break;
            case HANDLER:
                switch (state) {
                    case HANDLER_NAME:
                    case HANDLER_CLASS:
                        pushText();
                        break;
                    case INIT_PARAM:
                        pushTarget(new InitParam());
                        break;
                }
                break;
            case HANDLER_CHAINS:
                switch (state) {
                    case HANDLER_CHAIN:
                        pushTarget(new HandlerChainDef());
                        break;
                }
                break;
            case HANDLER_CHAIN:
                switch (state) {
                    case PROTOCOL_BINDINGS:
                    case PORT_NAME_PATTERN:
                        pushText();
                        break;
                    case HANDLER:
                        pushTarget(new HandlerDef());
                        break;
                }
                break;
            case RESOURCE_REF:
                switch (state) {
                    case DESCRIPTION:
                    case RES_REF_NAME:
                    case RES_TYPE:
                    case RES_AUTH:
                    case RES_SHARING_SCOPE:
                    case MAPPED_NAME:
                    case LOOKUP_NAME:
                        pushText();
                        break;
                    case INJECTION_TARGET:
                        pushTarget(new InjectionTarget());
                        break;
                }
                break;
            case RESOURCE_ENV_REF:
                switch (state) {
                    case DESCRIPTION:
                    case RESOURCE_ENV_REF_NAME:
                    case RESOURCE_ENV_REF_TYPE:
                    case MAPPED_NAME:
                    case LOOKUP_NAME:
                        pushText();
                        break;
                    case INJECTION_TARGET:
                        pushTarget(new InjectionTarget());
                        break;
                }
                break;
            case MESSAGE_DESTINATION_REF:
                switch (state) {
                    case DESCRIPTION:
                    case MESSAGE_DESTINATION_REF_NAME:
                    case MESSAGE_DESTINATION_TYPE:
                    case MESSAGE_DESTINATION_USAGE:
                    case MESSAGE_DESTINATION_LINK:
                    case MAPPED_NAME:
                    case LOOKUP_NAME:
                        pushText();
                        break;
                    case INJECTION_TARGET:
                        pushTarget(new InjectionTarget());
                        break;
                }
                break;
            case PERSISTENCE_CONTEXT_REF:
                switch (state) {
                    case DESCRIPTION:
                    case PERSISTENCE_CONTEXT_REF_NAME:
                    case PERSISTENCE_CONTEXT_TYPE:
                    case PERSISTENCE_UNIT_NAME:
                    case MAPPED_NAME:
                    case LOOKUP_NAME:
                        pushText();
                        break;
                    case INJECTION_TARGET:
                        pushTarget(new InjectionTarget());
                        break;
                }
                break;
            case PERSISTENCE_UNIT_REF:
                switch (state) {
                    case DESCRIPTION:
                    case PERSISTENCE_UNIT_REF_NAME:
                    case PERSISTENCE_UNIT_NAME:
                    case MAPPED_NAME:
                    case LOOKUP_NAME:
                        pushText();
                        break;
                    case INJECTION_TARGET:
                        pushTarget(new InjectionTarget());
                        break;
                }
                break;
            case POST_CONSTRUCT:
            case PRE_DESTROY:
                switch (state) {
                    case LIFECYCLE_CALLBACK_CLASS:
                    case LIFECYCLE_CALLBACK_METHOD:
                        pushText();
                }
                break;
            case DATA_SOURCE:
                switch (state) {
                    case DESCRIPTION:
                    case NAME:
                    case CLASS_NAME:
                    case SERVER_NAME:
                    case PORT_NUMBER:
                    case DATABASE_NAME:
                    case USER:
                    case PASSWORD:
                    case URL:
                    case ISOLATION_LEVEL:
                    case INITIAL_POOL_SIZE:
                    case MAX_POOL_SIZE:
                    case MIN_POOL_SIZE:
                    case MAX_IDLE_TIME:
                    case MAX_STATEMENTS:
                    case TRANSACTION_ISOLATION:
                        pushText();
                        break;
                    case PROPERTY:
                        pushTarget(new InitParam());
                        break;
                }
                break;
            case JMS_CONNECTION_FACTORY:
                switch (state) {
                    case DESCRIPTION:
                    case NAME:
                    case INTERFACE_NAME:
                    case CLASS_NAME:
                    case USER:
                    case PASSWORD:
                    case CLIENT_ID:
                    case TRANSACTIONAL:
                    case RESOURCE_ADAPTER:
                        pushText();
                        break;
                    case POOL:
                        pushTarget(new JmsConnectionFactory.Pool());
                        break;
                    case PROPERTY:
                        pushTarget(new InitParam());
                        break;
                }
                break;
            case POOL:
                switch (state) {
                    case MAX_POOL_SIZE:
                    case MIN_POOL_SIZE:
                    case CONNECTION_TIMEOUT_IN_SECONDS:
                        pushText();
                        break;
                }
                break;
            case JMS_DESTINATION:
                switch (state) {
                    case DESCRIPTION:
                    case NAME:
                    case INTERFACE_NAME:
                    case CLASS_NAME:
                        pushText();
                        break;
                    case PROPERTY:
                        pushTarget(new InitParam());
                        break;
                }
                break;
            case MAIL_SESSION:
                switch (state) {
                    case DESCRIPTION:
                    case NAME:
                    case STORE_PROTOCOL:
                    case STORE_PROTOCOL_CLASS:
                    case TRANSPORT_PROTOCOL:
                    case TRANSPORT_PROTOCOL_CLASS:
                    case HOST:
                    case USER:
                    case PASSWORD:
                    case FROM:
                        pushText();
                        break;
                    case PROPERTY:
                        pushTarget(new InitParam());
                        break;
                }
                break;
            case CONNECTION_FACTORY:
                switch (state) {
                    case JNDI_NAME:
                        pushText();
                        break;
                }
                break;
            case CONNECTION_DEFINITION:
                switch (state) {
                    case CONNECTION_DEFINITION_ID:
                        pushText();
                        break;
                    case CONFIG_PROPERTY:
                        pushTarget(new InitParam());
                        break;
                }
                break;
            case CONFIG_PROPERTY:
                switch (state) {
                    case CONFIG_PROPERTY_NAME:
                    case CONFIG_PROPERTY_VALUE:
                        pushText();
                        break;
                }
                break;
            case ADMINISTERED_OBJECT:
                switch (state) {
                    case DESCRIPTION:
                    case JNDI_NAME:
                    case ADMINISTERED_OBJECT_INTERFACE:
                    case ADMINISTERED_OBJECT_CLASS:
                    case LOOKUP_NAME:
                    case MAPPED_NAME:
                        pushText();
                        break;
                    case CONFIG_PROPERTY:
                        pushTarget(new InitParam());
                        break;
                    case INJECTION_TARGET:
                        pushTarget(new InjectionTarget());
                        break;
                }
                break;
            case MESSAGE_DESTINATION:
                switch (state) {
                    case DESCRIPTION:
                    case DISPLAY_NAME:
                    case MESSAGE_DESTINATION_NAME:
                    case MAPPED_NAME:
                    case LOOKUP_NAME:
                        pushText();
                }
                break;
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
        String text;

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
                        ((Description) peekTarget()).setDescription(popText());
                        break;
                    case DISPLAY_NAME:
                        ((Description) peekTarget()).setDisplayName(popText());
                        break;
                    case CONTEXT_PARAM:
                        InitParam contextParam = (InitParam) popTarget();
                        ((DeploymentDescriptor) peekTarget()).addContextParam(contextParam);
                        break;
                    case FILTER:
                        FilterDef filterDef = (FilterDef) popTarget();
                        ((DeploymentDescriptor) peekTarget()).addFilterDef(filterDef);
                        break;
                    case FILTER_MAPPING:
                        FilterMapping filterMapping = (FilterMapping) popTarget();
                        ((DeploymentDescriptor) peekTarget()).addFilterMapping(filterMapping);
                        break;
                    case LISTENER:
                        ListenerDef listenerDef = (ListenerDef) popTarget();
                        ((DeploymentDescriptor) peekTarget()).addListenerDef(listenerDef);
                        break;
                    case SERVLET:
                        ServletDef servletDef = (ServletDef) popTarget();
                        ((DeploymentDescriptor) peekTarget()).addServletDef(servletDef);
                        break;
                    case SERVLET_MAPPING:
                        ServletMapping servletMapping = (ServletMapping) popTarget();
                        ((DeploymentDescriptor) peekTarget()).addServletMapping(servletMapping);
                        break;
                    case SESSION_CONFIG:
                        SessionConfig sessionConfig = (SessionConfig) popTarget();
                        ((DeploymentDescriptor) peekTarget()).setSessionConfig(sessionConfig);
                        break;
                    case MIME_MAPPING:
                        MimeMapping mimeMapping = (MimeMapping) popTarget();
                        ((DeploymentDescriptor) peekTarget()).addMimeMapping(mimeMapping);
                        break;
                    case ERROR_PAGE:
                        ErrorPage errorPage = (ErrorPage) popTarget();
                        ((DeploymentDescriptor) peekTarget()).addErrorPage(errorPage);
                        break;
                    case JSP_CONFIG:
                        JspConfig jspConfig = (JspConfig) popTarget();
                        ((DeploymentDescriptor) peekTarget()).jspConfig = jspConfig;
                        break;
                    case SECURITY_CONSTRAINT:
                        SecurityConstraint securityConstraint = (SecurityConstraint) popTarget();
                        ((DeploymentDescriptor) peekTarget()).addSecurityConstraint(securityConstraint);
                        break;
                    case LOGIN_CONFIG:
                        LoginConfig loginConfig = (LoginConfig) popTarget();
                        ((DeploymentDescriptor) peekTarget()).setLoginConfig(loginConfig);
                        break;
                    case SECURITY_ROLE:
                        SecurityRole securityRole = (SecurityRole) popTarget();
                        ((DeploymentDescriptor) peekTarget()).addSecurityRole(securityRole);
                        break;
                    case ENV_ENTRY:
                        EnvEntry envEntry = (EnvEntry) popTarget();
                        ((DeploymentDescriptor) peekTarget()).addEnvEntry(envEntry);
                        break;
                    case EJB_REF:
                        EjbRef ejbRef = (EjbRef) popTarget();
                        ((DeploymentDescriptor) peekTarget()).addEjbRef(ejbRef);
                        break;
                    case EJB_LOCAL_REF:
                        EjbRef ejbLocalRef = (EjbRef) popTarget();
                        ((DeploymentDescriptor) peekTarget()).addEjbRef(ejbLocalRef);
                        break;
                    case SERVICE_REF:
                        ServiceRef serviceRef = (ServiceRef) popTarget();
                        ((DeploymentDescriptor) peekTarget()).addServiceRef(serviceRef);
                        break;
                    case RESOURCE_REF:
                        ResourceRef resourceRef = (ResourceRef) popTarget();
                        ((DeploymentDescriptor) peekTarget()).addResourceRef(resourceRef);
                        break;
                    case RESOURCE_ENV_REF:
                        ResourceEnvRef resourceEnvRef = (ResourceEnvRef) popTarget();
                        ((DeploymentDescriptor) peekTarget()).addResourceEnvRef(resourceEnvRef);
                        break;
                    case MESSAGE_DESTINATION_REF:
                        MessageDestinationRef messageDestinationRef = (MessageDestinationRef) popTarget();
                        ((DeploymentDescriptor) peekTarget()).addMessageDestinationRef(messageDestinationRef);
                        break;
                    case PERSISTENCE_CONTEXT_REF:
                        PersistenceContextRef persistenceContextRef = (PersistenceContextRef) popTarget();
                        ((DeploymentDescriptor) peekTarget()).addPersistenceContextRef(persistenceContextRef);
                        break;
                    case PERSISTENCE_UNIT_REF:
                        PersistenceUnitRef persistenceUnitRef = (PersistenceUnitRef) popTarget();
                        ((DeploymentDescriptor) peekTarget()).addPersistenceUnitRef(persistenceUnitRef);
                        break;
                    case POST_CONSTRUCT:
                        LifecycleCallback postConstruct = (LifecycleCallback) popTarget();
                        ((DeploymentDescriptor) peekTarget()).addPostConstruct(postConstruct);
                        break;
                    case PRE_DESTROY:
                        LifecycleCallback preDestroy = (LifecycleCallback) popTarget();
                        ((DeploymentDescriptor) peekTarget()).addPreDestroy(preDestroy);
                        break;
                    case DATA_SOURCE:
                        DataSourceDef dataSourceDef = (DataSourceDef) popTarget();
                        ((DeploymentDescriptor) peekTarget()).addDataSourceDef(dataSourceDef);
                        break;
                    case JMS_CONNECTION_FACTORY:
                        JmsConnectionFactory jmsConnectionFactory = (JmsConnectionFactory) popTarget();
                        ((DeploymentDescriptor) peekTarget()).addJmsConnectionFactory(jmsConnectionFactory);
                        break;
                    case JMS_DESTINATION:
                        JmsDestination jmsDestination = (JmsDestination) popTarget();
                        ((DeploymentDescriptor) peekTarget()).addJmsDestination(jmsDestination);
                        break;
                    case MAIL_SESSION:
                        MailSession mailSession = (MailSession) popTarget();
                        ((DeploymentDescriptor) peekTarget()).addMailSession(mailSession);
                        break;
                    case CONNECTION_FACTORY:
                        ConnectionFactory connectionFactory = (ConnectionFactory) popTarget();
                        ((DeploymentDescriptor) peekTarget()).addConnectionFactory(connectionFactory);
                        break;
                    case ADMINISTERED_OBJECT:
                        AdministeredObject administeredObject = (AdministeredObject) popTarget();
                        ((DeploymentDescriptor) peekTarget()).addAdministeredObject(administeredObject);
                        break;
                    case MESSAGE_DESTINATION:
                        MessageDestination messageDestination = (MessageDestination) popTarget();
                        ((DeploymentDescriptor) peekTarget()).addMessageDestination(messageDestination);
                        break;
                }
                // WEB_FRAGMENT ONLY
                if (parentState == State.WEB_FRAGMENT) {
                    switch (state) {
                        case NAME:
                            ((WebFragment) peekTarget()).name = popText();
                            break;
                    }
                }
                break;
            case ICON:
                switch (state) {
                    case SMALL_ICON:
                        ((Description) peekTarget()).setSmallIcon(popText());
                        break;
                    case LARGE_ICON:
                        ((Description) peekTarget()).setLargeIcon(popText());
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
                        ((Description) peekTarget()).setDescription(popText());
                        break;
                    case DISPLAY_NAME:
                        ((Description) peekTarget()).setDisplayName(popText());
                        break;
                    case FILTER_NAME:
                        ((FilterDef) peekTarget()).name = popText();
                        break;
                    case FILTER_CLASS:
                        ((FilterDef) peekTarget()).className = popText();
                        break;
                    case INIT_PARAM:
                        InitParam initParam = (InitParam) popTarget();
                        ((FilterDef) peekTarget()).addInitParam(initParam);
                        break;
                }
                break;
            case FILTER_MAPPING:
                switch (state) {
                    case FILTER_NAME:
                        ((FilterMapping) peekTarget()).filterName = popText();
                        break;
                    case URL_PATTERN:
                        ((FilterMapping) peekTarget()).addUrlPattern(popText());
                        break;
                    case SERVLET_NAME:
                        ((FilterMapping) peekTarget()).addServletName(popText());
                        break;
                    case DISPATCHER:
                        ((FilterMapping) peekTarget()).dispatchers.add(DispatcherType.valueOf(popText()));
                        break;
                }
                break;
            case LISTENER:
                switch (state) {
                    case DESCRIPTION:
                        ((Description) peekTarget()).setDescription(popText());
                        break;
                    case DISPLAY_NAME:
                        ((Description) peekTarget()).setDisplayName(popText());
                        break;
                    case LISTENER_CLASS:
                        ((ListenerDef) peekTarget()).className = popText();
                        break;
                }
                break;
            case SERVLET:
                switch (state) {
                    case DESCRIPTION:
                        ((Description) peekTarget()).setDescription(popText());
                        break;
                    case DISPLAY_NAME:
                        ((Description) peekTarget()).setDisplayName(popText());
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
                        text = popText();
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
                        ((ServletDef) peekTarget()).addInitParam(initParam);
                        break;
                    case RUN_AS:
                        String runAs = ((SecurityRole) popTarget()).roleName;
                        ((ServletDef) peekTarget()).runAs = runAs;
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
                        ((ServletMapping) peekTarget()).servletName = popText();
                        break;
                    case URL_PATTERN:
                        ((ServletMapping) peekTarget()).addUrlPattern(popText());
                        break;
                }
                break;
            case SESSION_CONFIG:
                switch (state) {
                    case SESSION_TIMEOUT:
                        text = popText();
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
                        ((SessionConfig) peekTarget()).addTrackingMode(SessionTrackingMode.valueOf(popText()));
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
                        text = popText();
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
                        text = popText();
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
                        ((JspConfig) peekTarget()).addTaglib(taglib);
                        break;
                    case JSP_PROPERTY_GROUP:
                        JspPropertyGroup jspPropertyGroup = (JspPropertyGroup) popTarget();
                        ((JspConfig) peekTarget()).addJspPropertyGroup(jspPropertyGroup);
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
                        ((JspPropertyGroup) peekTarget()).elIgnored = Boolean.valueOf(popBoolean());
                        break;
                    case PAGE_ENCODING:
                        ((JspPropertyGroup) peekTarget()).pageEncoding = popText();
                        break;
                    case SCRIPTING_INVALID:
                        ((JspPropertyGroup) peekTarget()).scriptingInvalid = Boolean.valueOf(popBoolean());
                        break;
                    case INCLUDE_PRELUDE:
                        ((JspPropertyGroup) peekTarget()).includePrelude.add(popText());
                        break;
                    case INCLUDE_CODA:
                        ((JspPropertyGroup) peekTarget()).includeCoda.add(popText());
                        break;
                    case DEFAULT_CONTENT_TYPE:
                        ((JspPropertyGroup) peekTarget()).defaultContentType = popText();
                        break;
                    case BUFFER:
                        text = popText();
                        try {
                            ((JspPropertyGroup) peekTarget()).buffer = Long.valueOf(text);
                        } catch (NumberFormatException e) {
                            // Log and continue
                            String message = L10N.getString("warn.invalid_number");
                            message = MessageFormat.format(message, text);
                            LOGGER.warning(message);
                        }
                        break;
                    case TRIM_DIRECTIVE_WHITESPACES:
                        ((JspPropertyGroup) peekTarget()).trimDirectiveWhitespaces = Boolean.valueOf(popBoolean());
                        break;
                    case IS_XML:
                        ((JspPropertyGroup) peekTarget()).isXml = Boolean.valueOf(popBoolean());
                        break;
                    case DEFERRED_SYNTAX_ALLOWED_AS_LITERAL:
                        ((JspPropertyGroup) peekTarget()).deferredSyntaxAllowedAsLiteral = Boolean.valueOf(popBoolean());
                        break;
                    case ERROR_ON_UNDECLARED_NAMESPACE:
                        ((JspPropertyGroup) peekTarget()).errorOnUndeclaredNamespace = Boolean.valueOf(popBoolean());
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
                        ((SecurityConstraint) peekTarget()).addResourceCollection(resourceCollection);
                        break;
                    case AUTH_CONSTRAINT:
                        String authConstraint = ((SecurityRole) popTarget()).roleName;
                        ((SecurityConstraint) peekTarget()).addAuthConstraint(authConstraint);
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
                        try {
                            ((SecurityConstraint) peekTarget()).transportGuarantee = ServletSecurity.TransportGuarantee.valueOf(popText());
                        } catch (IllegalArgumentException e) { // INTEGRAL can map to CONFIDENTIAL
                            ((SecurityConstraint) peekTarget()).transportGuarantee = ServletSecurity.TransportGuarantee.CONFIDENTIAL;
                        }
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
            case ENV_ENTRY:
                switch (state) {
                    case DESCRIPTION:
                        ((EnvEntry) peekTarget()).description = popText();
                        break;
                    case ENV_ENTRY_NAME:
                        ((EnvEntry) peekTarget()).name = popText();
                        break;
                    case ENV_ENTRY_TYPE:
                        ((EnvEntry) peekTarget()).className = popText();
                        break;
                    case ENV_ENTRY_VALUE:
                        ((EnvEntry) peekTarget()).value = popText();
                        break;
                    case MAPPED_NAME:
                        ((Injectable) peekTarget()).setMappedName(popText());
                        break;
                    case LOOKUP_NAME:
                        ((Injectable) peekTarget()).setLookupName(popText());
                        break;
                    case INJECTION_TARGET:
                        InjectionTarget injectionTarget = (InjectionTarget) popTarget();
                        ((Injectable) peekTarget()).setInjectionTarget(injectionTarget);
                        break;
                }
                break;
            case INJECTION_TARGET:
                switch (state) {
                    case INJECTION_TARGET_CLASS:
                        ((InjectionTarget) peekTarget()).className = popText();
                        break;
                    case INJECTION_TARGET_NAME:
                        ((InjectionTarget) peekTarget()).name = popText();
                        break;
                }
                break;
            case EJB_REF:
                switch (state) {
                    case DESCRIPTION:
                        ((EjbRef) peekTarget()).description = popText();
                        break;
                    case EJB_REF_NAME:
                        ((EjbRef) peekTarget()).name = popText();
                        break;
                    case EJB_REF_TYPE:
                        ((EjbRef) peekTarget()).className = popText();
                        break;
                    case HOME:
                        ((EjbRef) peekTarget()).home = popText();
                        break;
                    case REMOTE:
                        ((EjbRef) peekTarget()).remoteOrLocal = popText();
                        break;
                    case EJB_LINK:
                        ((EjbRef) peekTarget()).ejbLink = popText();
                        break;
                    case MAPPED_NAME:
                        ((Injectable) peekTarget()).setMappedName(popText());
                        break;
                    case LOOKUP_NAME:
                        ((Injectable) peekTarget()).setLookupName(popText());
                        break;
                    case INJECTION_TARGET:
                        InjectionTarget injectionTarget = (InjectionTarget) popTarget();
                        ((Injectable) peekTarget()).setInjectionTarget(injectionTarget);
                        break;
                }
                break;
            case EJB_LOCAL_REF:
                switch (state) {
                    case DESCRIPTION:
                        ((EjbRef) peekTarget()).description = popText();
                        break;
                    case EJB_REF_NAME:
                        ((EjbRef) peekTarget()).name = popText();
                        break;
                    case EJB_REF_TYPE:
                        ((EjbRef) peekTarget()).className = popText();
                        break;
                    case LOCAL_HOME:
                        ((EjbRef) peekTarget()).home = popText();
                        break;
                    case LOCAL:
                        ((EjbRef) peekTarget()).remoteOrLocal = popText();
                        break;
                    case EJB_LINK:
                        ((EjbRef) peekTarget()).ejbLink = popText();
                        break;
                    case MAPPED_NAME:
                        ((Injectable) peekTarget()).setMappedName(popText());
                        break;
                    case LOOKUP_NAME:
                        ((Injectable) peekTarget()).setLookupName(popText());
                        break;
                    case INJECTION_TARGET:
                        InjectionTarget injectionTarget = (InjectionTarget) popTarget();
                        ((Injectable) peekTarget()).setInjectionTarget(injectionTarget);
                        break;
                }
                break;
            case SERVICE_REF:
                switch (state) {
                    case DESCRIPTION:
                        ((Description) peekTarget()).setDescription(popText());
                        break;
                    case DISPLAY_NAME:
                        ((Description) peekTarget()).setDisplayName(popText());
                        break;
                    case SERVICE_REF_NAME:
                        ((ServiceRef) peekTarget()).name = popText();
                        break;
                    case SERVICE_REF_TYPE:
                        ((ServiceRef) peekTarget()).className = popText();
                        break;
                    case WSDL_FILE:
                        ((ServiceRef) peekTarget()).wsdlFile = popText();
                        break;
                    case JAXRPC_MAPPING_FILE:
                        ((ServiceRef) peekTarget()).jaxrpcMappingFile = popText();
                        break;
                    case SERVICE_QNAME:
                        ((ServiceRef) peekTarget()).serviceQname = popText();
                        break;
                    case PORT_COMPONENT_REF:
                        ((ServiceRef) peekTarget()).portComponentRef = popText();
                        break;
                    case HANDLER:
                        HandlerDef handlerDef = (HandlerDef) popTarget();
                        ((ServiceRef) peekTarget()).handler = handlerDef;
                    case MAPPED_NAME:
                        ((Injectable) peekTarget()).setMappedName(popText());
                        break;
                    case LOOKUP_NAME:
                        ((Injectable) peekTarget()).setLookupName(popText());
                        break;
                    case INJECTION_TARGET:
                        InjectionTarget injectionTarget = (InjectionTarget) popTarget();
                        ((Injectable) peekTarget()).setInjectionTarget(injectionTarget);
                        break;
                }
                break;
            case HANDLER:
                switch (state) {
                    case HANDLER_NAME:
                        ((HandlerDef) peekTarget()).name = popText();
                        break;
                    case HANDLER_CLASS:
                        ((HandlerDef) peekTarget()).className = popText();
                        break;
                    case INIT_PARAM:
                        InitParam initParam = (InitParam) popTarget();
                        ((HandlerDef) peekTarget()).addInitParam(initParam);
                        break;
                }
                break;
            case HANDLER_CHAINS:
                switch (state) {
                    case HANDLER_CHAIN:
                        HandlerChainDef handlerChain = (HandlerChainDef) popTarget();
                        ((ServiceRef) peekTarget()).addHandlerChain(handlerChain);
                        break;
                }
                break;
            case HANDLER_CHAIN:
                switch (state) {
                    case PROTOCOL_BINDINGS:
                        ((HandlerChainDef) peekTarget()).protocolBindings = popText();
                        break;
                    case PORT_NAME_PATTERN:
                        ((HandlerChainDef) peekTarget()).portNamePattern = popText();
                        break;
                    case HANDLER:
                        HandlerDef handler = (HandlerDef) popTarget();
                        ((HandlerChainDef) peekTarget()).addHandler(handler);
                        break;
                }
                break;
            case RESOURCE_REF:
                switch (state) {
                    case DESCRIPTION:
                        ((ResourceRef) peekTarget()).description = popText();
                        break;
                    case RES_REF_NAME:
                        ((ResourceRef) peekTarget()).name = popText();
                        break;
                    case RES_TYPE:
                        ((ResourceRef) peekTarget()).className = popText();
                        break;
                    case RES_AUTH:
                        ((ResourceRef) peekTarget()).resAuth = Resource.AuthenticationType.valueOf(popText());
                        break;
                    case RES_SHARING_SCOPE:
                        ((ResourceRef) peekTarget()).resSharingScope = popText();
                        break;
                    case MAPPED_NAME:
                        ((Injectable) peekTarget()).setMappedName(popText());
                        break;
                    case LOOKUP_NAME:
                        ((Injectable) peekTarget()).setLookupName(popText());
                        break;
                    case INJECTION_TARGET:
                        InjectionTarget injectionTarget = (InjectionTarget) popTarget();
                        ((Injectable) peekTarget()).setInjectionTarget(injectionTarget);
                        break;
                }
                break;
            case RESOURCE_ENV_REF:
                switch (state) {
                    case DESCRIPTION:
                        ((ResourceEnvRef) peekTarget()).description = popText();
                        break;
                    case RESOURCE_ENV_REF_NAME:
                        ((ResourceEnvRef) peekTarget()).name = popText();
                        break;
                    case RESOURCE_ENV_REF_TYPE:
                        ((ResourceEnvRef) peekTarget()).className = popText();
                        break;
                    case MAPPED_NAME:
                        ((Injectable) peekTarget()).setMappedName(popText());
                        break;
                    case LOOKUP_NAME:
                        ((Injectable) peekTarget()).setLookupName(popText());
                        break;
                    case INJECTION_TARGET:
                        InjectionTarget injectionTarget = (InjectionTarget) popTarget();
                        ((Injectable) peekTarget()).setInjectionTarget(injectionTarget);
                        break;
                }
                break;
            case MESSAGE_DESTINATION_REF:
                switch (state) {
                    case DESCRIPTION:
                        ((MessageDestinationRef) peekTarget()).description = popText();
                        break;
                    case MESSAGE_DESTINATION_REF_NAME:
                        ((MessageDestinationRef) peekTarget()).name = popText();
                        break;
                    case MESSAGE_DESTINATION_TYPE:
                        ((MessageDestinationRef) peekTarget()).className = popText();
                        break;
                    case MESSAGE_DESTINATION_USAGE:
                        ((MessageDestinationRef) peekTarget()).messageDestinationUsage = popText();
                        break;
                    case MESSAGE_DESTINATION_LINK:
                        ((MessageDestinationRef) peekTarget()).messageDestinationLink = popText();
                        break;
                    case MAPPED_NAME:
                        ((Injectable) peekTarget()).setMappedName(popText());
                        break;
                    case LOOKUP_NAME:
                        ((Injectable) peekTarget()).setLookupName(popText());
                        break;
                    case INJECTION_TARGET:
                        InjectionTarget injectionTarget = (InjectionTarget) popTarget();
                        ((Injectable) peekTarget()).setInjectionTarget(injectionTarget);
                        break;
                }
                break;
            case PERSISTENCE_CONTEXT_REF:
                switch (state) {
                    case DESCRIPTION:
                        ((PersistenceContextRef) peekTarget()).description = popText();
                        break;
                    case PERSISTENCE_CONTEXT_REF_NAME:
                        ((PersistenceContextRef) peekTarget()).name = popText();
                        break;
                    case PERSISTENCE_CONTEXT_TYPE:
                        ((PersistenceContextRef) peekTarget()).type = PersistenceContextType.valueOf(popText().toUpperCase());
                        break;
                    case PERSISTENCE_UNIT_NAME:
                        ((PersistenceContextRef) peekTarget()).unitName = popText();
                        break;
                    case MAPPED_NAME:
                        ((Injectable) peekTarget()).setMappedName(popText());
                        break;
                    case LOOKUP_NAME:
                        ((Injectable) peekTarget()).setLookupName(popText());
                        break;
                    case INJECTION_TARGET:
                        InjectionTarget injectionTarget = (InjectionTarget) popTarget();
                        ((Injectable) peekTarget()).setInjectionTarget(injectionTarget);
                        break;
                }
                break;
            case PERSISTENCE_UNIT_REF:
                switch (state) {
                    case DESCRIPTION:
                        ((PersistenceUnitRef) peekTarget()).description = popText();
                        break;
                    case PERSISTENCE_UNIT_REF_NAME:
                        ((PersistenceUnitRef) peekTarget()).name = popText();
                        break;
                    case PERSISTENCE_UNIT_NAME:
                        ((PersistenceUnitRef) peekTarget()).unitName = popText();
                        break;
                    case MAPPED_NAME:
                        ((Injectable) peekTarget()).setMappedName(popText());
                        break;
                    case LOOKUP_NAME:
                        ((Injectable) peekTarget()).setLookupName(popText());
                        break;
                    case INJECTION_TARGET:
                        InjectionTarget injectionTarget = (InjectionTarget) popTarget();
                        ((Injectable) peekTarget()).setInjectionTarget(injectionTarget);
                        break;
                }
                break;
            case POST_CONSTRUCT:
            case PRE_DESTROY:
                switch (state) {
                    case LIFECYCLE_CALLBACK_CLASS:
                        ((LifecycleCallback) peekTarget()).className = popText();
                        break;
                    case LIFECYCLE_CALLBACK_METHOD:
                        ((LifecycleCallback) peekTarget()).methodName = popText();
                        break;
                }
                break;
            case DATA_SOURCE:
                switch (state) {
                    case DESCRIPTION:
                        ((DataSourceDef) peekTarget()).description = popText();
                        break;
                    case NAME:
                        ((DataSourceDef) peekTarget()).name = popText();
                        break;
                    case CLASS_NAME:
                        ((DataSourceDef) peekTarget()).className = popText();
                        break;
                    case SERVER_NAME:
                        ((DataSourceDef) peekTarget()).serverName = popText();
                        break;
                    case PORT_NUMBER:
                        text = popText();
                        try {
                            ((DataSourceDef) peekTarget()).portNumber = Integer.parseInt(text);
                        } catch (NumberFormatException e) {
                            String message = L10N.getString("warn.invalid_number");
                            message = MessageFormat.format(message, text);
                            LOGGER.warning(message);
                        }
                        break;
                    case DATABASE_NAME:
                        ((DataSourceDef) peekTarget()).databaseName = popText();
                        break;
                    case USER:
                        ((DataSourceDef) peekTarget()).user = popText();
                        break;
                    case PASSWORD:
                        ((DataSourceDef) peekTarget()).password = popText();
                        break;
                    case ISOLATION_LEVEL:
                        ((DataSourceDef) peekTarget()).isolationLevel = DataSourceDef.getIsolationLevel(popText());
                        break;
                    case INITIAL_POOL_SIZE:
                        text = popText();
                        try {
                            ((DataSourceDef) peekTarget()).initialPoolSize = Integer.parseInt(text);
                        } catch (NumberFormatException e) {
                            String message = L10N.getString("warn.invalid_number");
                            message = MessageFormat.format(message, text);
                            LOGGER.warning(message);
                        }
                        break;
                    case MAX_POOL_SIZE:
                        text = popText();
                        try {
                            ((DataSourceDef) peekTarget()).maxPoolSize = Integer.parseInt(text);
                        } catch (NumberFormatException e) {
                            String message = L10N.getString("warn.invalid_number");
                            message = MessageFormat.format(message, text);
                            LOGGER.warning(message);
                        }
                        break;
                    case MIN_POOL_SIZE:
                        text = popText();
                        try {
                            ((DataSourceDef) peekTarget()).minPoolSize = Integer.parseInt(text);
                        } catch (NumberFormatException e) {
                            String message = L10N.getString("warn.invalid_number");
                            message = MessageFormat.format(message, text);
                            LOGGER.warning(message);
                        }
                        break;
                    case MAX_IDLE_TIME:
                        text = popText();
                        try {
                            ((DataSourceDef) peekTarget()).maxIdleTime = Integer.parseInt(text);
                        } catch (NumberFormatException e) {
                            String message = L10N.getString("warn.invalid_number");
                            message = MessageFormat.format(message, text);
                            LOGGER.warning(message);
                        }
                        break;
                    case MAX_STATEMENTS:
                        text = popText();
                        try {
                            ((DataSourceDef) peekTarget()).maxStatements = Integer.parseInt(text);
                        } catch (NumberFormatException e) {
                            String message = L10N.getString("warn.invalid_number");
                            message = MessageFormat.format(message, text);
                            LOGGER.warning(message);
                        }
                        break;
                    case TRANSACTION_ISOLATION:
                        ((DataSourceDef) peekTarget()).transactionIsolation = DataSourceDef.getIsolationLevel(popText());
                        break;
                    case PROPERTY:
                        InitParam initParam = (InitParam) popTarget();
                        ((DataSourceDef) peekTarget()).addProperty(initParam.name, initParam.value);
                        break;
                }
                break;
            case PROPERTY:
                switch (state) {
                    case NAME:
                        ((InitParam) peekTarget()).name = popText();
                        break;
                    case VALUE:
                        ((InitParam) peekTarget()).value = popText();
                        break;
                }
                break;
            case JMS_CONNECTION_FACTORY:
                switch (state) {
                    case DESCRIPTION:
                        ((JmsConnectionFactory) peekTarget()).description = popText();
                        break;
                    case NAME:
                        ((JmsConnectionFactory) peekTarget()).name = popText();
                        break;
                    case INTERFACE_NAME:
                        ((JmsConnectionFactory) peekTarget()).interfaceName = popText();
                        break;
                    case CLASS_NAME:
                        ((JmsConnectionFactory) peekTarget()).className = popText();
                        break;
                    case USER:
                        ((JmsConnectionFactory) peekTarget()).user = popText();
                        break;
                    case PASSWORD:
                        ((JmsConnectionFactory) peekTarget()).password = popText();
                        break;
                    case CLIENT_ID:
                        ((JmsConnectionFactory) peekTarget()).clientId = popText();
                        break;
                    case TRANSACTIONAL:
                        ((JmsConnectionFactory) peekTarget()).transactional = popBoolean();
                        break;
                    case POOL:
                        JmsConnectionFactory.Pool pool = (JmsConnectionFactory.Pool) popTarget();
                        ((JmsConnectionFactory) peekTarget()).pool = pool;
                        break;
                    case PROPERTY:
                        InitParam initParam = (InitParam) popTarget();
                        ((JmsConnectionFactory) peekTarget()).addProperty(initParam.name, initParam.value);
                        break;
                    case RESOURCE_ADAPTER:
                        ((JmsConnectionFactory) peekTarget()).resourceAdapter = popText();
                        break;
                }
                break;
            case POOL:
                switch (state) {
                    case MAX_POOL_SIZE:
                        text = popText();
                        try {
                            ((JmsConnectionFactory.Pool) peekTarget()).maxPoolSize = Integer.parseInt(text);
                        } catch (NumberFormatException e) {
                            String message = L10N.getString("warn.invalid_number");
                            message = MessageFormat.format(message, text);
                            LOGGER.warning(message);
                        }
                        break;
                    case MIN_POOL_SIZE:
                        text = popText();
                        try {
                            ((JmsConnectionFactory.Pool) peekTarget()).minPoolSize = Integer.parseInt(text);
                        } catch (NumberFormatException e) {
                            String message = L10N.getString("warn.invalid_number");
                            message = MessageFormat.format(message, text);
                            LOGGER.warning(message);
                        }
                        break;
                    case CONNECTION_TIMEOUT_IN_SECONDS:
                        text = popText();
                        try {
                            ((JmsConnectionFactory.Pool) peekTarget()).connectionTimeoutInSeconds = Integer.parseInt(text);
                        } catch (NumberFormatException e) {
                            String message = L10N.getString("warn.invalid_number");
                            message = MessageFormat.format(message, text);
                            LOGGER.warning(message);
                        }
                        break;
                }
                break;
            case JMS_DESTINATION:
                switch (state) {
                    case DESCRIPTION:
                        ((JmsConnectionFactory) peekTarget()).description = popText();
                        break;
                    case NAME:
                        ((JmsConnectionFactory) peekTarget()).name = popText();
                        break;
                    case INTERFACE_NAME:
                        ((JmsConnectionFactory) peekTarget()).interfaceName = popText();
                        break;
                    case CLASS_NAME:
                        ((JmsConnectionFactory) peekTarget()).className = popText();
                        break;
                    case PROPERTY:
                        InitParam initParam = (InitParam) popTarget();
                        ((JmsConnectionFactory) peekTarget()).addProperty(initParam.name, initParam.value);
                        break;
                }
                break;
            case MAIL_SESSION:
                switch (state) {
                    case DESCRIPTION:
                        ((MailSession) peekTarget()).description = popText();
                        break;
                    case NAME:
                        ((MailSession) peekTarget()).name = popText();
                        break;
                    case STORE_PROTOCOL:
                        ((MailSession) peekTarget()).storeProtocol = popText();
                        break;
                    case STORE_PROTOCOL_CLASS:
                        ((MailSession) peekTarget()).storeProtocolClass = popText();
                        break;
                    case TRANSPORT_PROTOCOL:
                        ((MailSession) peekTarget()).transportProtocol = popText();
                        break;
                    case TRANSPORT_PROTOCOL_CLASS:
                        ((MailSession) peekTarget()).transportProtocolClass = popText();
                        break;
                    case HOST:
                        ((MailSession) peekTarget()).host = popText();
                        break;
                    case USER:
                        ((MailSession) peekTarget()).user = popText();
                        break;
                    case PASSWORD:
                        ((MailSession) peekTarget()).password = popText();
                        break;
                    case FROM:
                        ((MailSession) peekTarget()).from = popText();
                        break;
                    case PROPERTY:
                        InitParam initParam = (InitParam) popTarget();
                        ((MailSession) peekTarget()).addProperty(initParam.name, initParam.value);
                        break;
                }
                break;
            case CONNECTION_FACTORY:
                switch (state) {
                    case JNDI_NAME:
                        ((ConnectionFactory) peekTarget()).jndiName = popText();
                        break;
                }
                break;
            case CONNECTION_DEFINITION:
                switch (state) {
                    case CONNECTION_DEFINITION_ID:
                        ((ConnectionFactory) peekTarget()).connectionDefinitionId = popText();
                        break;
                    case CONFIG_PROPERTY:
                        InitParam initParam = (InitParam) popTarget();
                        ((ConnectionFactory) peekTarget()).addProperty(initParam.name, initParam.value);
                        break;
                }
                break;
            case CONFIG_PROPERTY:
                switch (state) {
                    case CONFIG_PROPERTY_NAME:
                        ((InitParam) peekTarget()).name = popText();
                        break;
                    case CONFIG_PROPERTY_VALUE:
                        ((InitParam) peekTarget()).value = popText();
                        break;
                }
                break;
            case ADMINISTERED_OBJECT:
                switch (state) {
                    case DESCRIPTION:
                        ((AdministeredObject) peekTarget()).description = popText();
                        break;
                    case JNDI_NAME:
                        ((AdministeredObject) peekTarget()).jndiName = popText();
                        break;
                    case ADMINISTERED_OBJECT_INTERFACE:
                        ((AdministeredObject) peekTarget()).administeredObjectInterface = popText();
                        break;
                    case ADMINISTERED_OBJECT_CLASS:
                        ((AdministeredObject) peekTarget()).administeredObjectClass = popText();
                        break;
                    case LOOKUP_NAME:
                        ((AdministeredObject) peekTarget()).lookupName = popText();
                        break;
                    case MAPPED_NAME:
                        ((AdministeredObject) peekTarget()).mappedName = popText();
                        break;
                    case CONFIG_PROPERTY:
                        InitParam initParam = (InitParam) popTarget();
                        ((AdministeredObject) peekTarget()).addProperty(initParam.name, initParam.value);
                        break;
                    case INJECTION_TARGET:
                        InjectionTarget injectionTarget = (InjectionTarget) popTarget();
                        ((Injectable) peekTarget()).setInjectionTarget(injectionTarget);
                        break;
                }
                break;
            case MESSAGE_DESTINATION:
                switch (state) {
                    case DESCRIPTION:
                        ((Description) peekTarget()).setDescription(popText());
                        break;
                    case DISPLAY_NAME:
                        ((Description) peekTarget()).setDisplayName(popText());
                        break;
                    case MESSAGE_DESTINATION_NAME:
                        ((MessageDestination) peekTarget()).messageDestinationName = popText();
                        break;
                    case MAPPED_NAME:
                        ((MessageDestination) peekTarget()).mappedName = popText();
                        break;
                    case LOOKUP_NAME:
                        ((MessageDestination) peekTarget()).lookupName = popText();
                        break;
                }
                break;
            case LOCALE_ENCODING_MAPPING_LIST:
                switch (state) {
                    case LOCALE_ENCODING_MAPPING:
                        LocaleEncodingMapping lem = (LocaleEncodingMapping) popTarget();
                        descriptor.addLocaleEncodingMapping(lem.locale, lem.encoding);
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
