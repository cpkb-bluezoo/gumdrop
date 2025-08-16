/*
 * WebAppParser.java
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
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

/**
 * Parses a web application deployment descriptor, populating an application
 * context.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class WebAppParser extends DefaultHandler implements ErrorHandler {

    static final String PUBLIC_ID_23 = "-//Sun Microsystems, Inc.//DTD Web Application 2.3//EN";
    static final String SYSTEM_ID_23 = "http://java.sun.com/dtd/web-app_2_3.dtd";

    // Root element
    static final int WEB_APP = 1;

    // Top-level element
    static final int DESCRIPTION = 2;
    static final int DISPLAY_NAME = 3;
    static final int ICON = 4;
    static final int DISTRIBUTABLE = 5;
    static final int CONTEXT_PARAM = 6;
    static final int FILTER = 7;
    static final int FILTER_MAPPING = 8;
    static final int LISTENER = 9;
    static final int SERVLET = 10;
    static final int SERVLET_MAPPING = 11;
    static final int SESSION_CONFIG = 12;
    static final int MIME_MAPPING = 13;
    static final int WELCOME_FILE_LIST = 14;
    static final int ERROR_PAGE = 15;
    static final int JSP_CONFIG = 16;
    static final int SECURITY_CONSTRAINT = 17;
    static final int LOGIN_CONFIG = 18;
    static final int SECURITY_ROLE = 19;
    static final int ENV_ENTRY = 20;
    static final int EJB_REF = 21;
    static final int EJB_LOCAL_REF = 22;
    static final int SERVICE_REF = 23;
    static final int RESOURCE_REF = 24;
    static final int RESOURCE_ENV_REF = 25;
    static final int MESSAGE_DESTINATION_REF = 26;
    static final int MESSAGE_DESTINATION = 27;
    static final int LOCALE_ENCODING_MAPPING_LIST = 28;

    // Other elements
    static final int SMALL_ICON = 29;
    static final int LARGE_ICON = 30;
    static final int FILTER_NAME = 31;
    static final int FILTER_CLASS = 32;
    static final int INIT_PARAM = 33;
    static final int PARAM_NAME = 34;
    static final int PARAM_VALUE = 35;
    static final int URL_PATTERN = 36;
    static final int SERVLET_NAME = 37;
    static final int SERVLET_CLASS = 38;
    static final int JSP_FILE = 39;
    static final int LOAD_ON_STARTUP = 40;
    static final int RUN_AS = 41;
    static final int SECURITY_ROLE_REF = 42;
    static final int ROLE_NAME = 43;
    static final int ROLE_LINK = 44;
    static final int SESSION_TIMEOUT = 45;
    static final int EXTENSION = 46;
    static final int MIME_TYPE = 47;
    static final int LISTENER_CLASS = 48;
    static final int DISPATCHER = 49;
    static final int WELCOME_FILE = 50;
    static final int ERROR_CODE = 51;
    static final int EXCEPTION_TYPE = 52;
    static final int LOCATION = 53;
    static final int LOCALE_ENCODING_MAPPING = 54;
    static final int LOCALE = 55;
    static final int ENCODING = 56;
    static final int AUTH_METHOD = 57;
    static final int REALM_NAME = 58;
    static final int FORM_LOGIN_CONFIG = 59;
    static final int FORM_LOGIN_PAGE = 60;
    static final int FORM_ERROR_PAGE = 61;
    static final int TAGLIB = 62;
    static final int TAGLIB_URI = 63;
    static final int TAGLIB_LOCATION = 64;
    static final int JSP_PROPERTY_GROUP = 65;
    static final int WEB_RESOURCE_COLLECTION = 66;
    static final int WEB_RESOURCE_NAME = 67;
    static final int HTTP_METHOD = 68;
    static final int AUTH_CONSTRAINT = 69;
    static final int USER_DATA_CONSTRAINT = 70;
    static final int TRANSPORT_GUARANTEE = 71;
    static final int MULTIPART_CONFIG = 72;

    static final int RES_REF_NAME = 101;
    static final int RES_TYPE = 102;
    static final int RES_AUTH = 103;
    static final int ENV_ENTRY_NAME = 104;
    static final int ENV_ENTRY_TYPE = 105;
    static final int ENV_ENTRY_VALUE = 106;
    static final int EJB_REF_NAME = 107;
    static final int EJB_REF_TYPE = 108;
    static final int HOME = 109;
    static final int REMOTE = 110;
    static final int EJB_LINK = 111;
    static final int LOCAL_HOME = 112;
    static final int LOCAL = 113;
    static final int SERVICE_REF_NAME = 114;
    static final int SERVICE_INTERFACE = 115;
    static final int WSDL_FILE = 116;
    static final int JAXRPC_MAPPING_FILE = 117;
    static final int SERVICE_QNAME = 118;
    static final int PORT_COMPONENT_REF = 119;
    static final int HANDLER = 120;
    static final int RES_SHARING_SCOPE = 121;
    static final int RESOURCE_ENV_REF_NAME = 122;
    static final int RESOURCE_ENV_REF_TYPE = 123;
    static final int MESSAGE_DESTINATION_REF_NAME = 124;
    static final int MESSAGE_DESTINATION_TYPE = 125;
    static final int MESSAGE_DESTINATION_USAGE = 126;
    static final int MESSAGE_DESTINATION_LINK = 127;
    static final int MESSAGE_DESTINATION_NAME = 128;
    static final int MULTIPART_CONFIG_LOCATION = 129;
    static final int MAX_FILE_SIZE = 130;
    static final int MAX_REQUEST_SIZE = 131;
    static final int FILE_SIZE_THRESHOLD = 132;

    static final int EL_IGNORED = 201;
    static final int PAGE_ENCODING = 202;
    static final int SCRIPTING_INVALID = 203;
    static final int IS_XML = 204;
    static final int INCLUDE_PRELUDE = 205;
    static final int INCLUDE_CODA = 206;

    /**
     * The context being built.
     */
    final Context context;

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
    LinkedList states;

    /**
     * Target entity stack.
     */
    LinkedList targets;

    WebAppParser(Context context) {
        this.context = context;
    }

    void log(String message) {
        context.log(message);
    }

    public void parse(URL url) throws IOException, SAXException {
        InputStream in = null;
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            in = url.openStream();
            DigestInputStream di = new DigestInputStream(in, digest);
            InputSource source = new InputSource(di);
            source.setSystemId(url.toString());
            parse(source);
            context.digest = digest.digest();
        } catch (NoSuchAlgorithmException e) {
            IOException e2 = new IOException(e.getMessage());
            e2.initCause(e);
            throw e2;
        } finally {
            if (in != null) {
                in.close();
            }
        }
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
        return ret.trim();
    }

    void pushState(int state) {
        states.addLast(Integer.valueOf(state));
    }

    int peekState() {
        return ((Integer) states.getLast()).intValue();
    }

    int popState() {
        return ((Integer) states.removeLast()).intValue();
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
        states = new LinkedList();
        states.add(Integer.valueOf(0));
        targets = new LinkedList();
        targets.add(context);
    }

    public void startElement(String uri, String localName, String qName, Attributes atts) throws SAXException {
        String name = (localName == null) ? qName : localName;
        name = name.intern();
        int state = peekState();
        switch (state) {
            case 0:
                if (name == "web-app") {
                    String version = atts.getValue("version");
                    // It is not really legal to have no version attribute,
                    // but let's be gracious...
                    if (version != null) {
                        try {
                            int di = version.indexOf('.');
                            context.major = Integer.parseInt(version.substring(0, di));
                            context.minor = Integer.parseInt(version.substring(di + 1));
                        } catch (Exception e) {
                            throw new SAXParseException("invalid web-app version: " + version, loc);
                        }
                    }
                    pushState(WEB_APP);
                } else {
                    throw new SAXParseException("expecting web-app, found " + name, loc);
                }
                break;
            case WEB_APP:
                if (name == "description") {
                    pushText();
                    pushState(DESCRIPTION);
                } else if (name == "display-name") {
                    pushText();
                    pushState(DISPLAY_NAME);
                } else if (name == "icon") {
                    pushState(ICON);
                } else if (name == "distributable") {
                    pushState(DISTRIBUTABLE);
                    context.distributable = true;
                } else if (name == "context-param") {
                    pushState(CONTEXT_PARAM);
                    pushTarget(new InitParam());
                } else if (name == "filter") {
                    pushState(FILTER);
                    pushTarget(new FilterDef(context));
                } else if (name == "filter-mapping") {
                    pushState(FILTER_MAPPING);
                    pushTarget(new FilterMapping());
                } else if (name == "listener") {
                    pushState(LISTENER);
                    pushTarget(new ListenerDef(context));
                } else if (name == "servlet") {
                    pushState(SERVLET);
                    pushTarget(new ServletDef(context));
                } else if (name == "servlet-mapping") {
                    pushState(SERVLET_MAPPING);
                    pushTarget(new ServletMapping());
                } else if (name == "session-config") {
                    pushState(SESSION_CONFIG);
                } else if (name == "mime-mapping") {
                    pushState(MIME_MAPPING);
                    pushTarget(new MimeMapping());
                } else if (name == "welcome-file-list") {
                    pushState(WELCOME_FILE_LIST);
                } else if (name == "error-page") {
                    pushState(ERROR_PAGE);
                    pushTarget(new ErrorPage());
                } else if (name == "jsp-config") {
                    pushState(JSP_CONFIG);
                    pushTarget(new JspConfig());
                } else if (name == "security-constraint") {
                    pushState(SECURITY_CONSTRAINT);
                    pushTarget(new SecurityConstraint());
                    context.authentication = true;
                } else if (name == "login-config") {
                    pushState(LOGIN_CONFIG);
                    context.authentication = true;
                } else if (name == "security-role") {
                    pushState(SECURITY_ROLE);
                    pushTarget(new SecurityRole());
                } else if (name == "env-entry") {
                    pushState(ENV_ENTRY);
                    pushTarget(new HashMap());
                } else if (name == "ejb-ref") {
                    pushState(EJB_REF);
                    pushTarget(new HashMap());
                } else if (name == "ejb-local-ref") {
                    pushState(EJB_LOCAL_REF);
                    pushTarget(new HashMap());
                } else if (name == "service-ref") {
                    pushState(SERVICE_REF);
                    pushTarget(new HashMap());
                } else if (name == "resource-ref") {
                    pushState(RESOURCE_REF);
                    pushTarget(new HashMap());
                } else if (name == "resource-env-ref") {
                    pushState(RESOURCE_ENV_REF);
                    pushTarget(new HashMap());
                } else if (name == "message-destination-ref") {
                    pushState(MESSAGE_DESTINATION_REF);
                    pushTarget(new HashMap());
                } else if (name == "message-destination") {
                    pushState(MESSAGE_DESTINATION);
                    pushTarget(new HashMap());
                } else if (name == "locale-encoding-mapping-list") {
                    pushState(LOCALE_ENCODING_MAPPING_LIST);
                } else {
                    throw new SAXParseException("expecting top-level element, found " + name, loc);
                }
                break;
            case ICON:
                if (name == "small-icon") {
                    pushText();
                    pushState(SMALL_ICON);
                } else if (name == "large-icon") {
                    pushText();
                    pushState(LARGE_ICON);
                } else {
                    throw new SAXParseException("expecting icon child, found: " + name, loc);
                }
                break;
            case CONTEXT_PARAM:
                if (name == "param-name") {
                    pushText();
                    pushState(PARAM_NAME);
                } else if (name == "param-value") {
                    pushText();
                    pushState(PARAM_VALUE);
                } else {
                    throw new SAXParseException("expecting context-param child, found: " + name, loc);
                }
                break;
            case FILTER:
                if (name == "description") {
                    pushText();
                    pushState(DESCRIPTION);
                } else if (name == "display-name") {
                    pushText();
                    pushState(DESCRIPTION);
                } else if (name == "icon") {
                    pushState(ICON);
                } else if (name == "filter-name") {
                    pushText();
                    pushState(FILTER_NAME);
                } else if (name == "filter-class") {
                    pushText();
                    pushState(FILTER_CLASS);
                } else if (name == "init-param") {
                    pushState(INIT_PARAM);
                    pushTarget(new InitParam());
                } else {
                    throw new SAXParseException("expecting filter child, found: " + name, loc);
                }
                break;
            case INIT_PARAM:
                if (name == "description") {
                    pushText();
                    pushState(DESCRIPTION);
                } else if (name == "param-name") {
                    pushText();
                    pushState(PARAM_NAME);
                } else if (name == "param-value") {
                    pushText();
                    pushState(PARAM_VALUE);
                } else {
                    throw new SAXParseException("expecting init-param child, found: " + name, loc);
                }
                break;
            case FILTER_MAPPING:
                if (name == "filter-name") {
                    pushText();
                    pushState(FILTER_NAME);
                } else if (name == "url-pattern") {
                    pushText();
                    pushState(URL_PATTERN);
                } else if (name == "servlet-name") {
                    pushText();
                    pushState(SERVLET_NAME);
                } else if (name == "dispatcher") {
                    pushText();
                    pushState(DISPATCHER);
                } else {
                    throw new SAXParseException("expecting filter-mapping child, found: " + name, loc);
                }
                break;
            case LISTENER:
                if (name == "description") {
                    pushText();
                    pushState(DESCRIPTION);
                } else if (name == "display-name") {
                    pushText();
                    pushState(DISPLAY_NAME);
                } else if (name == "icon") {
                    pushState(ICON);
                } else if (name == "listener-class") {
                    pushText();
                    pushState(LISTENER_CLASS);
                } else {
                    throw new SAXParseException("expecting listener child, found: " + name, loc);
                }
                break;
            case SERVLET:
                if (name == "description") {
                    pushText();
                    pushState(DESCRIPTION);
                } else if (name == "display-name") {
                    pushText();
                    pushState(DISPLAY_NAME);
                } else if (name == "icon") {
                    pushState(ICON);
                } else if (name == "servlet-name") {
                    pushText();
                    pushState(SERVLET_NAME);
                } else if (name == "servlet-class") {
                    pushText();
                    pushState(SERVLET_CLASS);
                } else if (name == "jsp-file") {
                    pushText();
                    pushState(JSP_FILE);
                } else if (name == "init-param") {
                    pushState(INIT_PARAM);
                    pushTarget(new InitParam());
                } else if (name == "load-on-startup") {
                    pushText();
                    pushState(LOAD_ON_STARTUP);
                } else if (name == "run-as") {
                    pushState(RUN_AS);
                } else if (name == "security-role-ref") {
                    pushState(SECURITY_ROLE_REF);
                } else if (name == "multipart-config") {
                    pushTarget(new MultipartConfigDef());
                    pushState(MULTIPART_CONFIG);
                } else {
                    throw new SAXParseException("expecting servlet child, found: " + name, loc);
                }
                break;
            case RUN_AS:
                if (name == "description") {
                    pushText();
                    pushState(DESCRIPTION);
                } else if (name == "role-name") {
                    pushText();
                    pushState(ROLE_NAME);
                } else {
                    throw new SAXParseException("expecting run-as child, found: " + name, loc);
                }
                break;
            case SERVLET_MAPPING:
                if (name == "servlet-name") {
                    pushText();
                    pushState(SERVLET_NAME);
                } else if (name == "url-pattern") {
                    pushText();
                    pushState(URL_PATTERN);
                } else {
                    throw new SAXParseException("expecting servlet-mapping child, found: " + name, loc);
                }
                break;
            case SESSION_CONFIG:
                if (name == "session-timeout") {
                    pushText();
                    pushState(SESSION_TIMEOUT);
                } else {
                    throw new SAXParseException("expecting session-config child, found: " + name, loc);
                }
                break;
            case MIME_MAPPING:
                if (name == "extension") {
                    pushText();
                    pushState(EXTENSION);
                } else if (name == "mime-type") {
                    pushText();
                    pushState(MIME_TYPE);
                } else {
                    throw new SAXParseException("expecting mime-mapping child, found: " + name, loc);
                }
                break;
            case WELCOME_FILE_LIST:
                if (name == "welcome-file") {
                    pushText();
                    pushState(WELCOME_FILE);
                } else {
                    throw new SAXParseException("expecting welcome-file-list child, found: " + name, loc);
                }
                break;
            case ERROR_PAGE:
                if (name == "error-code") {
                    pushText();
                    pushState(ERROR_CODE);
                } else if (name == "exception-type") {
                    pushText();
                    pushState(EXCEPTION_TYPE);
                } else if (name == "location") {
                    pushText();
                    pushState(LOCATION);
                } else {
                    throw new SAXParseException("expecting welcome-file-list child, found: " + name, loc);
                }
                break;
            case JSP_CONFIG:
                if (name == "taglib") {
                    pushState(TAGLIB);
                    pushTarget(new Taglib());
                } else if (name == "jsp-property-group") {
                    pushState(JSP_PROPERTY_GROUP);
                } else {
                    throw new SAXParseException("expecting jsp-config child, found: " + name, loc);
                }
                break;
            case TAGLIB:
                if (name == "taglib-uri") {
                    pushText();
                    pushState(TAGLIB_URI);
                } else if (name == "taglib-location") {
                    pushText();
                    pushState(TAGLIB_LOCATION);
                } else {
                    throw new SAXParseException("expecting taglib child, found: " + name, loc);
                }
                break;
            case JSP_PROPERTY_GROUP:
                if (name == "description") {
                    pushText();
                    pushState(DESCRIPTION);
                } else if (name == "url-pattern") {
                    pushText();
                    pushState(URL_PATTERN);
                } else if (name == "el-ignored") {
                    pushState(EL_IGNORED);
                    ((JspPropertyGroup) peekTarget()).elIgnored = true;
                } else if (name == "page-encoding") {
                    pushText();
                    pushState(PAGE_ENCODING);
                } else if (name == "scripting-invalid") {
                    pushState(SCRIPTING_INVALID);
                    ((JspPropertyGroup) peekTarget()).scriptingInvalid = true;
                } else if (name == "is-xml") {
                    pushState(IS_XML);
                    ((JspPropertyGroup) peekTarget()).isXml = true;
                } else if (name == "include-prelude") {
                    pushText();
                    pushState(INCLUDE_PRELUDE);
                } else if (name == "include-coda") {
                    pushText();
                    pushState(INCLUDE_CODA);
                } else {
                    throw new SAXParseException("expecting jsp-property-group child, found: " + name, loc);
                }
                break;
            case SECURITY_CONSTRAINT:
                if (name == "display-name") {
                    pushText();
                    pushState(DISPLAY_NAME);
                } else if (name == "web-resource-collection") {
                    pushState(WEB_RESOURCE_COLLECTION);
                    pushTarget(new ResourceCollection());
                } else if (name == "auth-constraint") {
                    pushState(AUTH_CONSTRAINT);
                } else if (name == "user-data-constraint") {
                    pushState(USER_DATA_CONSTRAINT);
                } else {
                    throw new SAXParseException("expecting security-constraint child, found: " + name, loc);
                }
                break;
            case WEB_RESOURCE_COLLECTION:
                if (name == "web-resource-name") {
                    pushText();
                    pushState(WEB_RESOURCE_NAME);
                } else if (name == "description") {
                    pushText();
                    pushState(DESCRIPTION);
                } else if (name == "url-pattern") {
                    pushText();
                    pushState(URL_PATTERN);
                } else if (name == "http-method") {
                    pushText();
                    pushState(HTTP_METHOD);
                } else {
                    throw new SAXParseException("expecting web-resource-collection child, found: " + name, loc);
                }
                break;
            case AUTH_CONSTRAINT:
                if (name == "description") {
                    pushText();
                    pushState(DESCRIPTION);
                } else if (name == "role-name") {
                    pushText();
                    pushState(ROLE_NAME);
                } else {
                    throw new SAXParseException("expecting auth-constraint child, found: " + name, loc);
                }
                break;
            case USER_DATA_CONSTRAINT:
                if (name == "description") {
                    pushText();
                    pushState(DESCRIPTION);
                } else if (name == "transport-guarantee") {
                    pushText();
                    pushState(TRANSPORT_GUARANTEE);
                } else {
                    throw new SAXParseException("expecting auth-constraint child, found: " + name, loc);
                }
                break;
            case LOGIN_CONFIG:
                if (name == "auth-method") {
                    pushText();
                    pushState(AUTH_METHOD);
                } else if (name == "realm-name") {
                    pushText();
                    pushState(REALM_NAME);
                } else if (name == "form-login-config") {
                    pushState(FORM_LOGIN_CONFIG);
                } else {
                    throw new SAXParseException("expecting login-config child, found: " + name, loc);
                }
                break;
            case FORM_LOGIN_CONFIG:
                if (name == "form-login-page") {
                    pushText();
                    pushState(FORM_LOGIN_PAGE);
                } else if (name == "form-error-page") {
                    pushText();
                    pushState(FORM_ERROR_PAGE);
                } else {
                    throw new SAXParseException("expecting form-login-config child, found: " + name, loc);
                }
                break;
            case SECURITY_ROLE:
                if (name == "description") {
                    pushText();
                    pushState(DESCRIPTION);
                } else if (name == "role-name") {
                    pushText();
                    pushState(ROLE_NAME);
                } else {
                    throw new SAXParseException("expecting form-login-config child, found: " + name, loc);
                }
                break;
            case ENV_ENTRY:
                if (name == "description") {
                    pushText();
                    pushState(DESCRIPTION);
                } else if (name == "env-entry-name") {
                    pushText();
                    pushState(ENV_ENTRY_NAME);
                } else if (name == "env-entry-type") {
                    pushText();
                    pushState(ENV_ENTRY_TYPE);
                } else if (name == "env-entry-value") {
                    pushText();
                    pushState(ENV_ENTRY_VALUE);
                } else {
                    throw new SAXParseException("expecting env-entry child, found: " + name, loc);
                }
                break;
            case EJB_REF:
                if (name == "description") {
                    pushText();
                    pushState(DESCRIPTION);
                } else if (name == "ejb-ref-name") {
                    pushText();
                    pushState(EJB_REF_NAME);
                } else if (name == "ejb-ref-type") {
                    pushText();
                    pushState(EJB_REF_TYPE);
                } else if (name == "home") {
                    pushText();
                    pushState(HOME);
                } else if (name == "remote") {
                    pushText();
                    pushState(REMOTE);
                } else if (name == "ejb-link") {
                    pushText();
                    pushState(EJB_LINK);
                } else {
                    throw new SAXParseException("expecting ejb-ref child, found: " + name, loc);
                }
                break;
            case EJB_LOCAL_REF:
                if (name == "description") {
                    pushText();
                    pushState(DESCRIPTION);
                } else if (name == "ejb-ref-name") {
                    pushText();
                    pushState(EJB_REF_NAME);
                } else if (name == "ejb-ref-type") {
                    pushText();
                    pushState(EJB_REF_TYPE);
                } else if (name == "local-home") {
                    pushText();
                    pushState(LOCAL_HOME);
                } else if (name == "local") {
                    pushText();
                    pushState(LOCAL);
                } else if (name == "ejb-link") {
                    pushText();
                    pushState(EJB_LINK);
                } else {
                    throw new SAXParseException("expecting ejb-local-ref child, found: " + name, loc);
                }
                break;
            case SERVICE_REF:
                if (name == "description") {
                    pushText();
                    pushState(DESCRIPTION);
                } else if (name == "display-name") {
                    pushText();
                    pushState(DISPLAY_NAME);
                } else if (name == "icon") {
                    pushState(ICON);
                } else if (name == "service-ref-name") {
                    pushText();
                    pushState(SERVICE_REF_NAME);
                } else if (name == "service-interface") {
                    pushText();
                    pushState(SERVICE_INTERFACE);
                } else if (name == "wsdl-file") {
                    pushText();
                    pushState(WSDL_FILE);
                } else if (name == "jaxrpc-mapping-file") {
                    pushText();
                    pushState(JAXRPC_MAPPING_FILE);
                } else if (name == "service-qname") {
                    pushText();
                    pushState(SERVICE_QNAME);
                } else if (name == "port-component-ref") {
                    pushText();
                    pushState(PORT_COMPONENT_REF);
                } else if (name == "handler") {
                    pushText();
                    pushState(HANDLER);
                } else {
                    throw new SAXParseException("expecting service-ref child, found: " + name, loc);
                }
                break;
            case RESOURCE_REF:
                if (name == "description") {
                    pushText();
                    pushState(DESCRIPTION);
                } else if (name == "res-ref-name") {
                    pushText();
                    pushState(RES_REF_NAME);
                } else if (name == "res-type") {
                    pushText();
                    pushState(RES_TYPE);
                } else if (name == "res-auth") {
                    pushText();
                    pushState(RES_AUTH);
                } else if (name == "res-sharing-scope") {
                    pushText();
                    pushState(RES_SHARING_SCOPE);
                } else {
                    throw new SAXParseException("expecting resource-ref child, found: " + name, loc);
                }
                break;
            case RESOURCE_ENV_REF:
                if (name == "description") {
                    pushText();
                    pushState(DESCRIPTION);
                } else if (name == "resource-env-ref-name") {
                    pushText();
                    pushState(RESOURCE_ENV_REF_NAME);
                } else if (name == "resource-env-ref-type") {
                    pushText();
                    pushState(RESOURCE_ENV_REF_TYPE);
                } else {
                    throw new SAXParseException("expecting resource-env-ref child, found: " + name, loc);
                }
                break;
            case MESSAGE_DESTINATION_REF:
                if (name == "description") {
                    pushText();
                    pushState(DESCRIPTION);
                } else if (name == "message-destination-ref-name") {
                    pushText();
                    pushState(MESSAGE_DESTINATION_REF_NAME);
                } else if (name == "message-destination-type") {
                    pushText();
                    pushState(MESSAGE_DESTINATION_TYPE);
                } else if (name == "message-destination-usage") {
                    pushText();
                    pushState(MESSAGE_DESTINATION_USAGE);
                } else if (name == "message-destination-link") {
                    pushText();
                    pushState(MESSAGE_DESTINATION_LINK);
                } else {
                    throw new SAXParseException("expecting message-destination-ref child, found: " + name, loc);
                }
                break;
            case MESSAGE_DESTINATION:
                if (name == "description") {
                    pushText();
                    pushState(DESCRIPTION);
                } else if (name == "display-name") {
                    pushText();
                    pushState(DISPLAY_NAME);
                } else if (name == "icon") {
                    pushState(ICON);
                } else if (name == "message-destination-name") {
                    pushText();
                    pushState(MESSAGE_DESTINATION_NAME);
                } else {
                    throw new SAXParseException("expecting message-destination child, found: " + name, loc);
                }
                break;
            case LOCALE_ENCODING_MAPPING_LIST:
                if (name == "locale-encoding-mapping") {
                    pushState(LOCALE_ENCODING_MAPPING);
                    pushTarget(new LocaleEncodingMapping());
                } else {
                    throw new SAXParseException("expecting locale-encoding-mapping-list child, found: " + name, loc);
                }
                break;
            case LOCALE_ENCODING_MAPPING:
                if (name == "locale") {
                    pushText();
                    pushState(LOCALE);
                } else if (name == "encoding") {
                    pushText();
                    pushState(ENCODING);
                } else {
                    throw new SAXParseException("expecting locale-encoding-mapping child, found: " + name, loc);
                }
                break;
            case MULTIPART_CONFIG:
                if (name == "location") {
                    pushText();
                    pushState(MULTIPART_CONFIG_LOCATION);
                } else if (name == "max-file-size") {
                    pushText();
                    pushState(MAX_FILE_SIZE);
                } else if (name == "max-request-size") {
                    pushText();
                    pushState(MAX_REQUEST_SIZE);
                } else if (name == "file-size-threshold") {
                    pushText();
                    pushState(FILE_SIZE_THRESHOLD);
                } else {
                    throw new SAXParseException("expecting multipart-config child, found: " + name, loc);
                }
                break;
            default:
                throw new SAXParseException("unexpected element: " + name, loc);
        }
    }

    public void endElement(String uri, String localName, String qName) throws SAXException {
        String name = (localName == null) ? qName : localName;
        name = name.intern();
        int state = popState();
        switch (state) {
            case DESCRIPTION:
                String description = popText();
                switch (peekState()) {
                    case WEB_APP:
                        ((Context) peekTarget()).description = description;
                        break;
                    case FILTER:
                        ((FilterDef) peekTarget()).description = description;
                        break;
                    case LISTENER:
                        ((ListenerDef) peekTarget()).description = description;
                        break;
                    case SERVLET:
                        ((ServletDef) peekTarget()).description = description;
                        break;
                    case SECURITY_ROLE:
                        ((SecurityRole) peekTarget()).description = description;
                        break;
                    case WEB_RESOURCE_COLLECTION:
                        ((ResourceCollection) peekTarget()).description = description;
                        break;
                    case JSP_PROPERTY_GROUP:
                        ((JspPropertyGroup) peekTarget()).description = description;
                        break;
                    case RUN_AS:
                    case SECURITY_ROLE_REF:
                    case AUTH_CONSTRAINT:
                    case USER_DATA_CONSTRAINT:
                    case ENV_ENTRY:
                    case EJB_REF:
                    case EJB_LOCAL_REF:
                    case SERVICE_REF:
                    case RESOURCE_REF:
                    case RESOURCE_ENV_REF:
                    case MESSAGE_DESTINATION_REF:
                    case MESSAGE_DESTINATION:
                        break; // NOOP
                }
                break;
            case SMALL_ICON:
                String smallIcon = popText();
                switch (peekState()) {
                    case WEB_APP:
                        ((Context) peekTarget()).smallIcon = smallIcon;
                        break;
                    case FILTER:
                        ((FilterDef) peekTarget()).smallIcon = smallIcon;
                        break;
                    case LISTENER:
                        ((ListenerDef) peekTarget()).smallIcon = smallIcon;
                        break;
                    case SERVLET:
                        ((ServletDef) peekTarget()).smallIcon = smallIcon;
                        break;
                    case SERVICE_REF:
                    case MESSAGE_DESTINATION:
                        ((Map) peekTarget()).put("small-icon", smallIcon);
                        break;
                }
                break;
            case LARGE_ICON:
                String largeIcon = popText();
                switch (peekState()) {
                    case WEB_APP:
                        ((Context) peekTarget()).largeIcon = largeIcon;
                        break;
                    case FILTER:
                        ((FilterDef) peekTarget()).largeIcon = largeIcon;
                        break;
                    case LISTENER:
                        ((FilterDef) peekTarget()).largeIcon = largeIcon;
                        break;
                    case SERVLET:
                        ((ServletDef) peekTarget()).largeIcon = largeIcon;
                        break;
                    case SERVICE_REF:
                    case MESSAGE_DESTINATION:
                        ((Map) peekTarget()).put("large-icon", largeIcon);
                        break;
                }
                break;
            case DISPLAY_NAME:
                String displayName = popText();
                switch (peekState()) {
                    case WEB_APP:
                        ((Context) peekTarget()).displayName = displayName;
                        break;
                    case SECURITY_CONSTRAINT:
                        ((SecurityConstraint) peekTarget()).displayName = displayName;
                        break;
                    case SERVICE_REF:
                    case MESSAGE_DESTINATION:
                        ((Map) peekTarget()).put("display-name", displayName);
                        break;
                }
                break;
            case CONTEXT_PARAM:
                InitParam contextParam = (InitParam) popTarget();
                ((Context) peekTarget()).initParams.put(contextParam.name, contextParam);
                break;
            case FILTER:
                FilterDef filterDef = (FilterDef) popTarget();
                context.filterDefs.put(filterDef.name, filterDef);
                break;
            case FILTER_MAPPING:
                FilterMapping filterMapping = (FilterMapping) popTarget();
                context.filterMappings.add(filterMapping);
                break;
            case FILTER_NAME:
                String filterName = popText();
                switch (peekState()) {
                    case FILTER:
                        ((FilterDef) peekTarget()).name = filterName;
                        break;
                    case FILTER_MAPPING:
                        ((FilterMapping) peekTarget()).name = filterName;
                        break;
                }
                break;
            case FILTER_CLASS:
                ((FilterDef) peekTarget()).className = popText();
                break;
            case DISPATCHER:
                int dispatcher = 0;
                String dispatcherVal = popText();
                if ("REQUEST".equals(dispatcherVal)) {
                    dispatcher = FilterMapping.REQUEST;
                } else if ("FORWARD".equals(dispatcherVal)) {
                    dispatcher = FilterMapping.FORWARD;
                } else if ("INCLUDE".equals(dispatcherVal)) {
                    dispatcher = FilterMapping.INCLUDE;
                } else if ("ERROR".equals(dispatcherVal)) {
                    dispatcher = FilterMapping.ERROR;
                }
                ((FilterMapping) peekTarget()).dispatcher |= dispatcher;
                break;
            case INIT_PARAM:
                InitParam initParam = (InitParam) popTarget();
                switch (peekState()) {
                    case FILTER:
                        ((FilterDef) peekTarget()).initParams.put(initParam.name, initParam);
                        break;
                    case SERVLET:
                        ((ServletDef) peekTarget()).initParams.put(initParam.name, initParam);
                        break;
                }
                break;
            case PARAM_NAME:
                ((InitParam) peekTarget()).name = popText();
                break;
            case PARAM_VALUE:
                ((InitParam) peekTarget()).value = popText();
                break;
            case URL_PATTERN:
                String urlPattern = popText();
                if (urlPattern.indexOf('\n') != -1) {
                    throw new SAXParseException("LF in url-pattern", loc);
                }
                if (urlPattern.indexOf('\r') != -1) {
                    throw new SAXParseException("CR in url-pattern", loc);
                }
                switch (peekState()) {
                    case FILTER_MAPPING:
                        ((FilterMapping) peekTarget()).urlPattern = urlPattern;
                        break;
                    case SERVLET_MAPPING:
                        ((ServletMapping) peekTarget()).urlPattern = urlPattern;
                        break;
                    case WEB_RESOURCE_COLLECTION:
                        ((ResourceCollection) peekTarget()).urlPatterns.add(urlPattern);
                        break;
                    case JSP_PROPERTY_GROUP:
                        ((JspPropertyGroup) peekTarget()).urlPatterns.add(urlPattern);
                        break;
                }
                break;
            case SERVLET:
                ServletDef servletDef = (ServletDef) popTarget();
                context.servletDefs.put(servletDef.name, servletDef);
                break;
            case SERVLET_MAPPING:
                ServletMapping servletMapping = (ServletMapping) popTarget();
                context.servletMappings.add(servletMapping);
                break;
            case SERVLET_NAME:
                String servletName = popText();
                switch (peekState()) {
                    case SERVLET:
                        ((ServletDef) peekTarget()).name = servletName;
                        break;
                    case SERVLET_MAPPING:
                        ((ServletMapping) peekTarget()).name = servletName;
                        break;
                    case FILTER_MAPPING:
                        ((FilterMapping) peekTarget()).servletName = servletName;
                        break;
                }
                break;
            case SERVLET_CLASS:
                ((ServletDef) peekTarget()).className = popText();
                break;
            case JSP_FILE:
                ((ServletDef) peekTarget()).jspFile = popText();
                break;
            case LISTENER:
                ListenerDef listenerDef = (ListenerDef) popTarget();
                context.listenerDefs.add(listenerDef);
                break;
            case LISTENER_CLASS:
                ((ListenerDef) peekTarget()).className = popText();
                break;
            case LOAD_ON_STARTUP:
                int loadOnStartup = Integer.parseInt(popText());
                ((ServletDef) peekTarget()).loadOnStartup = loadOnStartup;
                break;
            case ROLE_NAME:
                String roleName = popText();
                switch (peekState()) {
                    case RUN_AS:
                        // ServletDef run-as role-name
                        ((ServletDef) peekTarget()).runAs = roleName;
                        break;
                    case SECURITY_ROLE_REF:
                        // ServletDef security-role-ref role-name
                        break;
                    case SECURITY_ROLE:
                        ((SecurityRole) peekTarget()).roleName = roleName;
                        break;
                    case AUTH_CONSTRAINT:
                        ((SecurityConstraint) peekTarget()).authConstraints.add(roleName);
                        break;
                }
                break;
            case SESSION_TIMEOUT:
                int sessionTimeout = Integer.parseInt(popText());
                ((Context) peekTarget()).sessionTimeout = sessionTimeout;
                break;
            case MIME_MAPPING:
                MimeMapping mimeMapping = (MimeMapping) popTarget();
                context.mimeMappings.add(mimeMapping);
                break;
            case EXTENSION:
                String extension = popText();
                ((MimeMapping) peekTarget()).extension = extension;
                break;
            case MIME_TYPE:
                String mimeType = popText();
                ((MimeMapping) peekTarget()).mimeType = mimeType;
                break;
            case WELCOME_FILE:
                String welcomeFile = popText();
                ((Context) peekTarget()).welcomeFiles.add(welcomeFile);
                break;
            case ERROR_PAGE:
                ErrorPage errorPage = (ErrorPage) popTarget();
                context.errorPages.add(errorPage);
                break;
            case ERROR_CODE:
                int errorCode = Integer.parseInt(popText());
                ((ErrorPage) peekTarget()).errorCode = errorCode;
                break;
            case EXCEPTION_TYPE:
                String exceptionType = popText();
                ((ErrorPage) peekTarget()).exceptionType = exceptionType;
                break;
            case LOCATION:
                String location = popText();
                if (location.indexOf('\n') != -1) {
                    throw new SAXParseException("LF in location", loc);
                }
                if (location.indexOf('\r') != -1) {
                    throw new SAXParseException("CR in location", loc);
                }
                ((ErrorPage) peekTarget()).location = location;
                break;
            case JSP_CONFIG:
                JspConfig jspConfig = (JspConfig) popTarget();
                ((Context) peekTarget()).jspConfigs.add(jspConfig);
                break;
            case TAGLIB:
                Taglib taglib = (Taglib) popTarget();
                ((JspConfig) peekTarget()).taglibs.add(taglib);
                break;
            case JSP_PROPERTY_GROUP:
                JspPropertyGroup jspPropertyGroup = (JspPropertyGroup) popTarget();
                ((JspConfig) peekTarget()).jspPropertyGroups.add(jspPropertyGroup);
                break;
            case PAGE_ENCODING:
                String pageEncoding = popText();
                ((JspPropertyGroup) peekTarget()).pageEncoding = pageEncoding;
                break;
            case INCLUDE_PRELUDE:
                String includePrelude = popText();
                ((JspPropertyGroup) peekTarget()).includePrelude = includePrelude;
                break;
            case INCLUDE_CODA:
                String includeCoda = popText();
                ((JspPropertyGroup) peekTarget()).includeCoda = includeCoda;
                break;
            case AUTH_METHOD:
                String authMethod = popText();
                ((Context) peekTarget()).authMethod = authMethod;
                break;
            case REALM_NAME:
                String realmName = popText();
                ((Context) peekTarget()).realmName = realmName;
                break;
            case FORM_LOGIN_PAGE:
                String formLoginPage = popText();
                ((Context) peekTarget()).formLoginPage = formLoginPage;
                break;
            case FORM_ERROR_PAGE:
                String formErrorPage = popText();
                ((Context) peekTarget()).formErrorPage = formErrorPage;
                break;
            case SECURITY_ROLE:
                SecurityRole securityRole = (SecurityRole) popTarget();
                context.securityRoles.add(securityRole);
                break;
            case TAGLIB_URI:
                String taglibUri = popText();
                ((Taglib) peekTarget()).taglibUri = taglibUri;
                break;
            case TAGLIB_LOCATION:
                String taglibLocation = popText();
                ((Taglib) peekTarget()).taglibLocation = taglibLocation;
                break;
            case SECURITY_CONSTRAINT:
                SecurityConstraint sc = (SecurityConstraint) popTarget();
                context.securityConstraints.add(sc);
                break;
            case WEB_RESOURCE_COLLECTION:
                ResourceCollection rc = (ResourceCollection) popTarget();
                SecurityConstraint rcsc = (SecurityConstraint) peekTarget();
                rcsc.resourceCollections.add(rc);
                break;
            case WEB_RESOURCE_NAME:
                String webResourceName = popText();
                ((ResourceCollection) peekTarget()).name = webResourceName;
                break;
            case HTTP_METHOD:
                String httpMethod = popText();
                ((ResourceCollection) peekTarget()).httpMethods.add(httpMethod);
                break;
            case TRANSPORT_GUARANTEE:
                String transportGuarantee = popText();
                int tg = SecurityConstraint.NONE;
                if ("INTEGRAL".equals(transportGuarantee)) {
                    tg = SecurityConstraint.INTEGRAL;
                } else if ("CONFIDENTIAL".equals(transportGuarantee)) {
                    tg = SecurityConstraint.CONFIDENTIAL;
                }
                ((SecurityConstraint) peekTarget()).transportGuarantee = tg;
                break;
            case ENV_ENTRY_NAME:
            case ENV_ENTRY_TYPE:
            case ENV_ENTRY_VALUE:
            case EJB_REF_NAME:
            case EJB_REF_TYPE:
            case HOME:
            case REMOTE:
            case EJB_LINK:
            case LOCAL_HOME:
            case LOCAL:
            case SERVICE_REF_NAME:
            case SERVICE_INTERFACE:
            case WSDL_FILE:
            case JAXRPC_MAPPING_FILE:
            case SERVICE_QNAME:
            case PORT_COMPONENT_REF:
            case HANDLER:
            case RES_REF_NAME:
            case RES_TYPE:
            case RES_AUTH:
            case RES_SHARING_SCOPE:
            case RESOURCE_ENV_REF_NAME:
            case RESOURCE_ENV_REF_TYPE:
            case MESSAGE_DESTINATION_REF_NAME:
            case MESSAGE_DESTINATION_TYPE:
            case MESSAGE_DESTINATION_USAGE:
            case MESSAGE_DESTINATION_LINK:
            case MESSAGE_DESTINATION_NAME:
                ((Map) peekTarget()).put(qName, popText());
                break;
            case ENV_ENTRY:
                Map envEntry = (Map) popTarget();
                ResourceDef resourceDef =
                        new ResourceDef(
                                (String) envEntry.get("env-entry-name"),
                                (String) envEntry.get("env-entry-type"));
                String envEntryDefaultValue = (String) envEntry.get("env-entry-value");
                if (envEntryDefaultValue != null) {
                    resourceDef.setProperty("", envEntryDefaultValue);
                }
                context.addResource(resourceDef);
                break;
            case EJB_REF:
                Map ejbRef = (Map) popTarget();
                // ejbRef
                break;
            case EJB_LOCAL_REF:
                Map ejbLocalRef = (Map) popTarget();
                // ejbLocalRef
                break;
            case SERVICE_REF:
                Map serviceRef = (Map) popTarget();
                // serviceRef
                break;
            case RESOURCE_REF:
                Map resourceRef = (Map) popTarget();
                // resourceRef
                break;
            case RESOURCE_ENV_REF:
                Map resourceEnvRef = (Map) popTarget();
                // resourceEnvRef
                break;
            case MESSAGE_DESTINATION_REF:
                Map messageDestinationRef = (Map) popTarget();
                // messageDestinationRef
                break;
            case MESSAGE_DESTINATION:
                Map messageDestination = (Map) popTarget();
                // messageDestination
                break;
            case LOCALE_ENCODING_MAPPING:
                LocaleEncodingMapping lem = (LocaleEncodingMapping) popTarget();
                ((Context) peekTarget()).localeEncodingMappings.put(lem.locale, lem.encoding);
                break;
            case LOCALE:
                String locale = popText();
                ((LocaleEncodingMapping) peekTarget()).locale = locale;
                break;
            case ENCODING:
                String encoding = popText();
                ((LocaleEncodingMapping) peekTarget()).encoding = encoding;
                break;
            case MULTIPART_CONFIG:
                MultipartConfigDef mc = (MultipartConfigDef) popTarget();
                ((ServletDef) peekTarget()).multipartConfig = mc;
                break;
            case MULTIPART_CONFIG_LOCATION:
                ((MultipartConfigDef) peekTarget()).location = popText();
                break;
            case MAX_FILE_SIZE:
                ((MultipartConfigDef) peekTarget()).maxFileSize = Long.parseLong(popText());
                break;
            case MAX_REQUEST_SIZE:
                ((MultipartConfigDef) peekTarget()).maxRequestSize = Long.parseLong(popText());
                break;
            case FILE_SIZE_THRESHOLD:
                ((MultipartConfigDef) peekTarget()).fileSizeThreshold = Long.parseLong(popText());
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
