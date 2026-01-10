/*
 * EntityResolutionHelper.java
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

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import org.xml.sax.EntityResolver;
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.ext.EntityResolver2;

/**
 * Helper class for resolving external entities.
 *
 * <p>This class encapsulates the logic for entity resolution, supporting both
 * {@link EntityResolver} and {@link EntityResolver2}. It handles:
 * <ul>
 *   <li>Relative URI resolution using base URI from Locator</li>
 *   <li>EntityResolver2 extended resolution (with entity name and base URI)</li>
 *   <li>Fallback to EntityResolver for compatibility</li>
 *   <li>External general entity resolution</li>
 *   <li>External parameter entity resolution</li>
 *   <li>External DTD subset resolution</li>
 * </ul>
 *
 * <p>This helper delegates to the ContentParser's Locator to get the base URI
 * for relative resolution.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class EntityResolutionHelper {

    private final EntityResolver entityResolver;
    private final Locator locator;
    private final boolean resolveDTDURIs;

    /**
     * Creates a new EntityResolutionHelper.
     *
     * @param entityResolver the entity resolver to use (may be null)
     * @param locator the locator for obtaining base URI (may be null)
     * @param resolveDTDURIs whether to resolve DTD URIs relative to base
     */
    public EntityResolutionHelper(EntityResolver entityResolver, Locator locator, boolean resolveDTDURIs) {
        this.entityResolver = entityResolver;
        this.locator = locator;
        this.resolveDTDURIs = resolveDTDURIs;
    }

    /**
     * Resolves an external entity.
     *
     * <p>This method handles:
     * <ol>
     *   <li>Obtaining base URI from locator</li>
     *   <li>Resolving relative systemId against base URI (if resolveDTDURIs is true)</li>
     *   <li>Trying EntityResolver2 resolution (if available)</li>
     *   <li>Falling back to EntityResolver resolution</li>
     * </ol>
     *
     * @param name the entity name (for EntityResolver2), may be null
     * @param publicId the public identifier, may be null
     * @param systemId the system identifier, may be null
     * @return an InputSource for reading the entity, or null to use default resolution
     * @throws SAXException if resolution fails
     * @throws IOException if an I/O error occurs
     */
    public InputSource resolveEntity(String name, String publicId, String systemId)
            throws SAXException, IOException {
        // Get base URI from locator (main document or current entity)
        String baseURI = (locator != null) ? locator.getSystemId() : null;
        return resolveEntity(name, publicId, systemId, baseURI);
    }

    /**
     * Resolves an external entity with an explicit base URI.
     *
     * <p>This overload allows specifying the base URI explicitly, which is needed
     * when resolving entities within external entities (where the base URI should
     * be the external entity's location, not the main document's location).
     *
     * @param name the entity name (for EntityResolver2), may be null
     * @param publicId the public identifier, may be null
     * @param systemId the system identifier, may be null
     * @param baseURI the base URI to use for resolving relative systemIds (may be null)
     * @return an InputSource for reading the entity, or null to use default resolution
     * @throws SAXException if resolution fails
     * @throws IOException if an I/O error occurs
     */
    public InputSource resolveEntity(String name, String publicId, String systemId, String baseURI)
            throws SAXException, IOException {
        if (entityResolver == null) {
            return null; // Use default resolution
        }

        // Use provided baseURI, or fall back to locator if not provided
        if (baseURI == null) {
            baseURI = (locator != null) ? locator.getSystemId() : null;
        }

        // Resolve relative systemId if requested and possible
        String resolvedSystemId = systemId;
        if (resolveDTDURIs && systemId != null && baseURI != null) {
            try {
                resolvedSystemId = resolveURI(baseURI, systemId);
            } catch (SAXException e) {
                // If URI resolution fails, use original systemId
                resolvedSystemId = systemId;
            }
        }

        // Try EntityResolver2 first (if available)
        if (entityResolver instanceof EntityResolver2) {
            EntityResolver2 resolver2 = (EntityResolver2) entityResolver;
            return resolver2.resolveEntity(name, publicId, baseURI, resolvedSystemId);
        }

        // Fall back to EntityResolver
        return entityResolver.resolveEntity(publicId, resolvedSystemId);
    }

    /**
     * Resolves the external subset for a document.
     *
     * <p>This method is called when a DOCTYPE declaration has no external ID
     * but the application wants to supply an external DTD subset via
     * EntityResolver2.getExternalSubset().
     *
     * @param name the root element name
     * @return an InputSource for the external subset, or null if none
     * @throws SAXException if resolution fails
     * @throws IOException if an I/O error occurs
     */
    public InputSource getExternalSubset(String name) throws SAXException, IOException {
        if (!(entityResolver instanceof EntityResolver2)) {
            return null;
        }

        EntityResolver2 resolver2 = (EntityResolver2) entityResolver;
        String baseURI = (locator != null) ? locator.getSystemId() : null;
        return resolver2.getExternalSubset(name, baseURI);
    }

    /**
     * Resolves a relative URI against a base URI.
     *
     * <p>If the systemId is already absolute, it is returned unchanged.
     * If the baseURI is null, the systemId is returned unchanged.
     * Otherwise, the systemId is resolved relative to the baseURI.
     *
     * @param baseURI the base URI (typically from document systemId)
     * @param systemId the system identifier to resolve
     * @return the resolved absolute URI
     * @throws SAXException if URI syntax is invalid
     */
    public static String resolveURI(String baseURI, String systemId) throws SAXException {
        if (systemId == null) {
            return null;
        }

        try {
            URI uri = new URI(systemId);
            if (uri.isAbsolute()) {
                return systemId; // Already absolute
            }

            if (baseURI == null) {
                return systemId; // No base, return as-is
            }

            URI base = new URI(baseURI);
            URI resolved = base.resolve(uri);
            return resolved.toString();
        } catch (URISyntaxException e) {
            throw new SAXException("Invalid URI: " + systemId, e);
        }
    }
}

