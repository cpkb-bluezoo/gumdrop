/*
 * DefaultEntityResolver.java
 * Copyright (C) 2025 Chris Burdess
 *
 * This file is part of Gonzalez, a streaming XML parser.
 * For more information please visit https://www.nongnu.org/gonzalez/
 *
 * Gonzalez is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Gonzalez is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Gonzalez.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.bluezoo.gonzalez;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import org.xml.sax.EntityResolver;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * Default entity resolver that resolves entities using standard URL mechanisms.
 *
 * <p>This resolver:
 * <ul>
 *   <li>Opens URLs using {@link URLConnection}</li>
 *   <li>Handles relative URLs by resolving against base URL</li>
 *   <li>Uses current directory as base for non-absolute systemIds</li>
 *   <li>Supports file:, http:, https:, and other URL protocols</li>
 * </ul>
 *
 * <p>This is used internally by Gonzalez when no user-specified
 * EntityResolver is set. It provides basic but functional entity resolution.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class DefaultEntityResolver implements EntityResolver {

    /**
     * Base URL for resolving relative system IDs.
     * If null, uses current directory as base.
     */
    private final URL baseURL;

    /**
     * Creates a DefaultEntityResolver with current directory as base.
     */
    public DefaultEntityResolver() {
        this(null);
    }

    /**
     * Creates a DefaultEntityResolver with the specified base URL.
     *
     * @param baseURL the base URL for resolving relative system IDs,
     *                or null to use current directory
     */
    public DefaultEntityResolver(URL baseURL) {
        this.baseURL = baseURL;
    }

    /**
     * Resolves an entity by opening its system ID as a URL.
     *
     * <p>Resolution process:
     * <ol>
     *   <li>If systemId is null, returns null</li>
     *   <li>If systemId is absolute URL, opens it directly</li>
     *   <li>If systemId is relative:
     *     <ul>
     *       <li>If baseURL is set, resolves against baseURL</li>
     *       <li>Otherwise, resolves against current directory (file: URL)</li>
     *     </ul>
     *   </li>
     *   <li>Opens URL using URLConnection</li>
     *   <li>Returns InputSource with InputStream and resolved systemId</li>
     * </ol>
     *
     * @param publicId the public identifier (ignored by this resolver)
     * @param systemId the system identifier (URL)
     * @return an InputSource for the entity, or null if systemId is null
     * @throws SAXException if the URL is malformed
     * @throws IOException if an I/O error occurs opening the URL
     */
    @Override
    public InputSource resolveEntity(String publicId, String systemId)
            throws SAXException, IOException {
        
        if (systemId == null) {
            return null;
        }

        // Try to parse systemId as URL
        URL url;
        try {
            url = new URL(systemId);
            // systemId is already an absolute URL
        } catch (MalformedURLException e) {
            // systemId is not absolute, resolve against base
            URL base = getBaseURL();
            try {
                url = new URL(base, systemId);
            } catch (MalformedURLException e2) {
                throw new SAXException("Invalid system ID: " + systemId, e2);
            }
        }

        // Open the URL
        URLConnection connection = url.openConnection();
        InputStream stream = connection.getInputStream();

        // Create InputSource
        InputSource source = new InputSource(stream);
        source.setSystemId(url.toString());
        source.setPublicId(publicId);

        return source;
    }

    /**
     * Gets the base URL for resolving relative system IDs.
     *
     * <p>If no base URL was specified in the constructor, this returns
     * a file: URL representing the current working directory.
     *
     * @return the base URL
     * @throws SAXException if the current directory cannot be determined
     */
    private URL getBaseURL() throws SAXException {
        if (baseURL != null) {
            return baseURL;
        }

        // Use current directory as base
        try {
            File currentDir = new File(System.getProperty("user.dir"));
            // Add trailing slash to make it a directory URL
            String path = currentDir.toURI().toString();
            if (!path.endsWith("/")) {
                path += "/";
            }
            return new URL(path);
        } catch (MalformedURLException e) {
            throw new SAXException("Cannot determine current directory URL", e);
        }
    }

    /**
     * Creates a new DefaultEntityResolver with the specified base URL.
     *
     * <p>This is used internally when creating nested entity resolvers
     * for tracking base URLs through entity resolution chains.
     *
     * @param baseURL the base URL for resolving relative system IDs
     * @return a new DefaultEntityResolver with the specified base
     */
    public DefaultEntityResolver withBase(URL baseURL) {
        return new DefaultEntityResolver(baseURL);
    }
}

