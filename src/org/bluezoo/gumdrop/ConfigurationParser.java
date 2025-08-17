/*
 * ConfigurationParser.java
 * Copyright (C) 2005 Chris Burdess
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

package org.bluezoo.gumdrop;

import org.bluezoo.gumdrop.servlet.Container;
import org.bluezoo.gumdrop.servlet.Context;
import org.bluezoo.gumdrop.servlet.ResourceDef;
import org.bluezoo.gumdrop.servlet.ResourceHandlerFactory;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.text.MessageFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.jar.JarFile;
import java.util.logging.Level;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

/**
 * <code>gumdroprc</code> parser.
 * Constructs a list of connectors.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class ConfigurationParser extends DefaultHandler {

    static final int SERVER = 1;
    static final int CONTAINER = 2;
    static final int CONTEXT = 3;
    static final int CONNECTOR = 4;
    static final int RESOURCE = 5;
    static final int REALM = 6;
    static final int PARAMETER = 7;

    private List<Connector> connectors;
    private Locator loc;
    private Deque<Integer> states = new ArrayDeque<Integer>();
    private Container container;
    private Context context;

    public List<Connector> parse(File file) throws SAXException, IOException {
        InputStream in = null;
        try {
            in = new BufferedInputStream(new FileInputStream(file));
            InputSource source = new InputSource(in);
            source.setSystemId(file.toURL().toString());
            parse(source);
            return connectors;
        } finally {
            if (in != null) {
                in.close();
            }
        }
    }

    void parse(InputSource source) throws SAXException, IOException {
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(true);
            SAXParser parser = factory.newSAXParser();
            XMLReader reader = parser.getXMLReader();
            reader.setContentHandler(this);
            reader.setErrorHandler(this);
            reader.parse(source);
        } catch (ParserConfigurationException e) {
            SAXException e2 = new SAXException(e.getMessage());
            e2.initCause(e);
            throw e2;
        }
    }

    int peekState() {
        return states.getLast();
    }

    int popState() {
        return states.removeLast();
    }

    void pushState(int state) {
        states.addLast(state);
    }

    /**
     * Use introspection to set a property on a bean.
     */
    public static void setValue(Object obj, String name, Object value, Method[] methods)
            throws Exception {
        String mutatorName =
                new StringBuffer("set")
                        .append(Character.toUpperCase(name.charAt(0)))
                        .append(name.substring(1))
                        .toString();
        // Transform deployment-descriptor style names to bean-style names
        int hi = mutatorName.indexOf('-');
        while (hi != -1) {
            mutatorName =
                    new StringBuffer(mutatorName.substring(0, hi))
                            .append(Character.toUpperCase(mutatorName.charAt(hi + 1)))
                            .append(mutatorName.substring(hi + 2))
                            .toString();
            hi = mutatorName.indexOf('-');
        }
        Object[] args = {value};
        for (int i = 0; i < methods.length; i++) {
            Method method = methods[i];
            if (method.getName().equals(mutatorName)) {
                Class[] pta = method.getParameterTypes();
                if (pta.length != 1) {
                    continue;
                }
                Class pt = pta[0];
                if (pt == Integer.TYPE && value instanceof String) {
                    args[0] = Integer.valueOf((String) value);
                }
                if (pt == Boolean.TYPE && value instanceof String) {
                    args[0] = Boolean.valueOf((String) value);
                }
                method.invoke(obj, args);
                return;
            }
        }
        String message = Server.L10N.getString("warn.no_bean_property");
        message = MessageFormat.format(message, name);
        Server.LOGGER.warning(message);
    }

    Connector createConnector(Attributes atts) throws SAXException {
        // Instantiate connector
        String className = atts.getValue("class");
        try {
            Class connectorClass = Class.forName(className);
            Connector connector = (Connector) connectorClass.newInstance();
            // Configure connector
            Method[] methods = connectorClass.getMethods();
            int len = atts.getLength();
            for (int i = 0; i < len; i++) {
                String name = atts.getLocalName(i);
                name = (name == null) ? atts.getQName(i) : name;
                if (!"class".equals(name)) {
                    String value = atts.getValue(i);
                    setValue(connector, name, value, methods);
                }
            }
            if (container != null) {
                setValue(connector, "container", container, methods);
            }
            return connector;
        } catch (Exception e) {
            String message = Server.L10N.getString("err.bad_connector");
            message = MessageFormat.format(message, className);
            throw new SAXParseException(message, loc, e);
        }
    }

    Container createContainer(Attributes atts) throws SAXException {
        try {
            Container ret = new Container();
            Method[] methods = Container.class.getMethods();
            int len = atts.getLength();
            for (int i = 0; i < len; i++) {
                String propName = atts.getQName(i);
                String propValue = atts.getValue(i);
                setValue(ret, propName, propValue, methods);
            }
            URL.setURLStreamHandlerFactory(new ResourceHandlerFactory(container));
            return ret;
        } catch (Exception e) {
            String message = Server.L10N.getString("err.bad_container");
            throw new SAXParseException(message, loc, e);
        }
    }

    Context createContext(Attributes atts) throws SAXException {
        String path = atts.getValue("path");
        String root = atts.getValue("root");
        JarFile warFile = null;
        URL contextRootUrl = null;
        if (root.length() > 0 && root.charAt(0) == '~') {
            root = System.getProperty("user.home") + root.substring(1);
        }
        File file = new File(root);
        if (file.exists() && file.canRead()) {
            try {
                if (file.isDirectory()) {
                    contextRootUrl = file.toURL();
                } else if (file.isFile()) {
                    warFile = new JarFile(file);
                    contextRootUrl = file.toURL();
                    contextRootUrl = new URL("jar", null, -1, contextRootUrl.toString() + "!/");
                } else {
                    String message = Server.L10N.getString("err.bad_root");
                    message = MessageFormat.format(message, root);
                    throw new IOException(message);
                }
                if (Server.LOGGER.isLoggable(Level.FINE)) {
                    String message = Server.L10N.getString("info.load_context");
                    String pathDesc = "".equals(path) ? Server.L10N.getString("root") : path;
                    message = MessageFormat.format(message, file, pathDesc);
                    Server.LOGGER.fine(message);
                }
                context = new Context(container, path, contextRootUrl, file, warFile);
                Method[] methods = Context.class.getMethods();
                int len = atts.getLength();
                for (int i = 0; i < len; i++) {
                    String propName = atts.getQName(i);
                    if ("path".equals(propName) || "root".equals(propName)) {
                        continue;
                    }
                    String propValue = atts.getValue(i);
                    setValue(context, propName, propValue, methods);
                }
                context.load();
                return context;
            } catch (Exception e) {
                String message = Server.L10N.getString("err.bad_context");
                message = MessageFormat.format(message, file);
                throw new SAXParseException(message, loc, e);
            }
        } else {
            String message = Server.L10N.getString("err.no_context");
            message = MessageFormat.format(message, file);
            throw new SAXParseException(message, loc);
        }
    }

    ResourceDef createResourceDef(Attributes atts) throws SAXException {
        String name = atts.getValue("name");
        String type = atts.getValue("type");
        String value = atts.getValue("value");
        if (name == null) {
            String message = Server.L10N.getString("err.missing_attribute");
            message = MessageFormat.format(message, "name");
            throw new SAXParseException(message, loc);
        }
        if (type == null) {
            type = "java.lang.String";
        }
        ResourceDef resourceDef = new ResourceDef(name, type);
        if (value != null) {
            resourceDef.setProperty("", value);
        }
        int len = atts.getLength();
        for (int i = 0; i < len; i++) {
            String propName = atts.getQName(i);
            if ("name".equals(propName) || "type".equals(propName) || "value".equals(propName)) {
                continue;
            }
            String propValue = atts.getValue(i);
            resourceDef.setProperty(propName, propValue);
        }
        return resourceDef;
    }

    Realm createRealm(Attributes atts) throws SAXException {
        String type = atts.getValue("type");
        if (type == null) {
            String message = Server.L10N.getString("err.missing_attribute");
            message = MessageFormat.format(message, "type");
            throw new SAXParseException(message, loc);
        }
        try {
            Class t = Class.forName(type);
            Method[] methods = t.getMethods();
            Realm realm = (Realm) t.newInstance();
            int len = atts.getLength();
            for (int i = 0; i < len; i++) {
                String propName = atts.getQName(i);
                if ("name".equals(propName) || "type".equals(propName)) {
                    continue;
                }
                String propValue = atts.getValue(i);
                setValue(realm, propName, propValue, methods);
            }
            return realm;
        } catch (Exception e) {
            RuntimeException e2 = new RuntimeException();
            e2.initCause(e);
            throw e2;
        }
    }

    // -- DefaultHandler --

    public void setLocator(Locator loc) {
        this.loc = loc;
    }

    public void startDocument() throws SAXException {
        connectors = new ArrayList<Connector>();
        loc = null;
        states.clear();
        states.addLast(0);
        container = null;
        context = null;
    }

    public void startElement(String uri, String localName, String qName, Attributes atts)
            throws SAXException {
        String name = (localName == null) ? qName : localName;
        name = name.intern();
        int state = peekState();
        switch (state) {
            case 0:
                if (name == "server") {
                    pushState(SERVER);
                } else {
                    String message = Server.L10N.getString("err.expected");
                    message = MessageFormat.format(message, "server", name);
                    throw new SAXParseException(message, loc);
                }
                break;
            case SERVER:
                if (name == "container") {
                    container = createContainer(atts);
                    pushState(CONTAINER);
                } else if (name == "connector") {
                    connectors.add(createConnector(atts));
                    pushState(CONNECTOR);
                } else {
                    String message = Server.L10N.getString("err.expected_child");
                    message = MessageFormat.format(message, "server", name);
                    throw new SAXParseException(message, loc);
                }
                break;
            case CONTAINER:
                if (name == "context") {
                    container.addContext(createContext(atts));
                    pushState(CONTEXT);
                } else if (name == "connector") {
                    connectors.add(createConnector(atts));
                    pushState(CONNECTOR);
                } else if (name == "resource") {
                    container.addResource(createResourceDef(atts));
                    pushState(RESOURCE);
                } else if (name == "realm") {
                    String realmName = atts.getValue("name");
                    if (realmName == null) {
                        String message = Server.L10N.getString("err.missing_attribute");
                        message = MessageFormat.format(message, "name");
                        throw new SAXParseException(message, loc);
                    }
                    container.addRealm(realmName, createRealm(atts));
                    pushState(REALM);
                } else {
                    String message = Server.L10N.getString("err.expected_child");
                    message = MessageFormat.format(message, "container");
                    throw new SAXParseException(message, loc);
                }
                break;
            case CONTEXT:
                if (name == "resource") {
                    context.addResource(createResourceDef(atts));
                    pushState(RESOURCE);
                } else if (name == "parameter") {
                    String paramName = atts.getValue("name");
                    String paramValue = atts.getValue("value");
                    if (paramName == null) {
                        String message = Server.L10N.getString("err.missing_attribute");
                        message = MessageFormat.format(message, "name");
                        throw new SAXParseException(message, loc);
                    }
                    context.setInitParameter(paramName, paramValue);
                    pushState(PARAMETER);
                } else if (name == "realm") {
                    String realmName = atts.getValue("name");
                    if (realmName == null) {
                        String message = Server.L10N.getString("err.missing_attribute");
                        message = MessageFormat.format(message, "name");
                        throw new SAXParseException(message, loc);
                    }
                    container.addRealm(realmName, createRealm(atts));
                    pushState(REALM);
                } else {
                    String message = Server.L10N.getString("err.expected_child");
                    message = MessageFormat.format(message, "context");
                    throw new SAXParseException(message, loc);
                }
                break;
            case CONNECTOR:
            default:
                String message = Server.L10N.getString("err.unexpected");
                message = MessageFormat.format(message, name);
                throw new SAXParseException(message, loc);
        }
    }

    public void endElement(String uri, String localName, String qName) throws SAXException {
        String name = (localName == null) ? qName : localName;
        name = name.intern();
        int state = popState();
        switch (state) {
            case CONTAINER:
                container = null;
                break;
            case CONTEXT:
                context = null;
                break;
        }
    }

}
