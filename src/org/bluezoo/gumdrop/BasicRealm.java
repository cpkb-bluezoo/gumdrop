/*
 * BasicRealm.java
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

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import java.io.File;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

/**
 * Simple realm composed of static principals declared in an XML
 * configuration resource.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class BasicRealm extends DefaultHandler implements Realm {

    /**
     * Users and their passwords.
     */
    Map passwords;

    /**
     * Users to set of groups.
     */
    Map userGroups;

    private transient String group;

    public BasicRealm() {
        passwords = new LinkedHashMap();
        userGroups = new LinkedHashMap();
    }

    public String getPassword(String username) {
        return (String) passwords.get(username);
    }

    public boolean isMember(String username, String role) {
        Set roles = (Set) userGroups.get(username);
        return (roles != null && roles.contains(role));
    }

    public void setHref(String href) {
        SAXParserFactory factory = SAXParserFactory.newInstance();
        try {
            URL cwd = new File(".").toURL();
            URL url = new URL(cwd, href);
            SAXParser parser = factory.newSAXParser();
            InputSource source = new InputSource(url.toString());
            parser.parse(source, this);
        } catch (Exception e) {
            if (e instanceof RuntimeException) {
                throw (RuntimeException) e;
            }
            RuntimeException e2 = new RuntimeException();
            e2.initCause(e);
            throw e2;
        } finally {
            group = null;
        }
    }

    public void startElement(String uri, String localName, String qName, Attributes atts)
            throws SAXException {
        if ("user".equals(qName)) {
            String username = atts.getValue("name");
            String password = atts.getValue("password");
            passwords.put(username, password);
        } else if ("group".equals(qName)) {
            group = atts.getValue("name");
        } else if ("member".equals(qName)) {
            String role = atts.getValue("name");
            Set roles = (Set) userGroups.get(group);
            if (roles == null) {
                roles = new LinkedHashSet();
                userGroups.put(group, roles);
            }
            roles.add(role);
        }
    }

}
