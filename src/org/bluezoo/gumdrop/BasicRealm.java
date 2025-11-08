/*
 * BasicRealm.java
 * Copyright (C) 2005 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 *
 * gumdrop is free software: you can redistribute it and/or modify
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
 * along with gumdrop.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.bluezoo.gumdrop;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import java.io.File;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
    Map<String, String> passwords;

    /**
     * Users to set of groups.
     */
    Map<String, Set<String>> userGroups;

    private transient String group;

    public BasicRealm() {
        passwords = new LinkedHashMap<String, String>();
        userGroups = new LinkedHashMap<String, Set<String>>();
    }

    @Override
    public boolean passwordMatch(String username, String password) {
        String storedPassword = passwords.get(username);
        return storedPassword != null && storedPassword.equals(password);
    }

    @Override
    public String getDigestHA1(String username, String realmName) {
        String password = passwords.get(username);
        if (password == null) {
            return null; // User doesn't exist
        }

        try {
            // Compute MD5(username:realm:password)
            MessageDigest md = MessageDigest.getInstance("MD5");
            md.update(username.getBytes(StandardCharsets.US_ASCII));
            md.update((byte) ':');
            md.update(realmName.getBytes(StandardCharsets.US_ASCII));
            md.update((byte) ':');
            md.update(password.getBytes(StandardCharsets.US_ASCII));
            
            byte[] hash = md.digest();
            return toHexString(hash);
        } catch (NoSuchAlgorithmException e) {
            // MD5 should always be available
            throw new RuntimeException("MD5 algorithm not available", e);
        }
    }

    @Override
    @Deprecated
    public String getPassword(String username) {
        return passwords.get(username);
    }

    @Override
    public boolean isMember(String username, String role) {
        Set<String> roles = userGroups.get(username);
        return (roles != null && roles.contains(role));
    }

    /**
     * Converts a byte array to a lowercase hexadecimal string.
     */
    private static String toHexString(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
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
            Set<String> roles = userGroups.get(group);
            if (roles == null) {
                roles = new LinkedHashSet<String>();
                userGroups.put(group, roles);
            }
            roles.add(role);
        }
    }

}
