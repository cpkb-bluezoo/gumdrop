/*
 * TaglibRegistry.java
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

package org.bluezoo.gumdrop.servlet.jsp;

import javax.servlet.ServletContext;
import javax.servlet.descriptor.JspConfigDescriptor;
import javax.servlet.descriptor.TaglibDescriptor;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Registry for resolving taglib URIs to Tag Library Descriptor (TLD) files.
 * 
 * <p>This class handles the resolution of JSP taglib URIs to their corresponding
 * TLD files, supporting various lookup mechanisms:
 * <ul>
 *   <li>Explicit mappings from JSP configuration in web.xml</li>
 *   <li>TLD files in WEB-INF/ and subdirectories</li>
 *   <li>TLD files in JAR files within WEB-INF/lib/</li>
 *   <li>Standard JSP tag library locations</li>
 * </ul>
 * 
 * <p>Example usage:
 * <pre>
 * TaglibRegistry registry = new TaglibRegistry(servletContext);
 * TagLibraryDescriptor tld = registry.resolveTaglib("http://java.sun.com/jsp/jstl/core");
 * </pre>
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class TaglibRegistry {

    private static final Logger LOGGER = Logger.getLogger(TaglibRegistry.class.getName());

    private final ServletContext servletContext;
    private final Map<String, TagLibraryDescriptor> taglibCache = new ConcurrentHashMap<>();
    private final Map<String, String> uriToLocationMap = new ConcurrentHashMap<>();
    
    /**
     * Creates a new TaglibRegistry for the given servlet context.
     * 
     * @param servletContext the servlet context to use for resource resolution
     */
    public TaglibRegistry(ServletContext servletContext) {
        this.servletContext = servletContext;
        initialize();
    }

    /**
     * Resolves a taglib URI to its Tag Library Descriptor.
     * 
     * @param uri the taglib URI to resolve
     * @return the TagLibraryDescriptor, or null if not found
     * @throws IOException if an I/O error occurs during resolution
     */
    public TagLibraryDescriptor resolveTaglib(String uri) throws IOException {
        if (uri == null || uri.isEmpty()) {
            return null;
        }

        // Check cache first
        TagLibraryDescriptor cached = taglibCache.get(uri);
        if (cached != null) {
            return cached;
        }

        // Try to resolve using various methods
        TagLibraryDescriptor tld = tryResolveTaglib(uri);
        
        if (tld != null) {
            // Cache the resolved TLD
            taglibCache.put(uri, tld);
            LOGGER.fine("Resolved taglib URI '" + uri + "' to TLD: " + tld.getShortName());
        } else {
            LOGGER.warning("Failed to resolve taglib URI: " + uri);
        }

        return tld;
    }

    /**
     * Initializes the registry by loading configuration mappings.
     */
    private void initialize() {
        try {
            loadJspConfigMappings();
            scanForTldFiles();
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Error initializing taglib registry", e);
        }
    }

    /**
     * Loads explicit taglib mappings from JSP configuration.
     */
    private void loadJspConfigMappings() {
        JspConfigDescriptor jspConfig = servletContext.getJspConfigDescriptor();
        if (jspConfig != null) {
            Collection<TaglibDescriptor> taglibs = jspConfig.getTaglibs();
            for (TaglibDescriptor taglib : taglibs) {
                String uri = taglib.getTaglibURI();
                String location = taglib.getTaglibLocation();
                if (uri != null && location != null) {
                    uriToLocationMap.put(uri, location);
                    LOGGER.fine("Mapped taglib URI '" + uri + "' to location: " + location);
                }
            }
        }
    }

    /**
     * Scans WEB-INF directory and JARs for TLD files.
     */
    private void scanForTldFiles() throws IOException {
        // Scan WEB-INF directory for .tld files
        scanWebInfDirectory();
        
        // Scan WEB-INF/lib JAR files for TLD files
        scanWebInfLibJars();
    }

    /**
     * Scans the WEB-INF directory for TLD files.
     */
    private void scanWebInfDirectory() {
        String webInfPath = "/WEB-INF/";
        scanDirectoryForTlds(webInfPath);
        
        // Also scan common subdirectories
        scanDirectoryForTlds("/WEB-INF/tlds/");
        scanDirectoryForTlds("/WEB-INF/tags/");
    }

    /**
     * Scans a directory for TLD files.
     */
    private void scanDirectoryForTlds(String directoryPath) {
        try {
            java.util.Set<String> resourcePaths = servletContext.getResourcePaths(directoryPath);
            if (resourcePaths != null) {
                for (String resourcePath : resourcePaths) {
                    if (resourcePath.endsWith(".tld")) {
                        processTldFile(resourcePath);
                    } else if (resourcePath.endsWith("/")) {
                        // Recursively scan subdirectories
                        scanDirectoryForTlds(resourcePath);
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Error scanning directory " + directoryPath + " for TLD files", e);
        }
    }

    /**
     * Scans WEB-INF/lib JAR files for TLD files in META-INF.
     */
    private void scanWebInfLibJars() {
        try {
            java.util.Set<String> resourcePaths = servletContext.getResourcePaths("/WEB-INF/lib/");
            if (resourcePaths != null) {
                for (String resourcePath : resourcePaths) {
                    if (resourcePath.endsWith(".jar")) {
                        scanJarResourceForTlds(resourcePath);
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Error scanning /WEB-INF/lib/ for JAR files", e);
        }
    }

    /**
     * Scans a JAR resource for TLD files in META-INF using resource-based access.
     * 
     * @param jarResourcePath the resource path to the JAR file (e.g., "/WEB-INF/lib/example.jar")
     */
    private void scanJarResourceForTlds(String jarResourcePath) {
        try (InputStream jarStream = servletContext.getResourceAsStream(jarResourcePath);
             JarInputStream jar = new JarInputStream(jarStream)) {
            
            if (jar == null) {
                return; // JAR resource not accessible
            }
            
            JarEntry entry;
            while ((entry = jar.getNextJarEntry()) != null) {
                String entryName = entry.getName();
                
                if (entryName.startsWith("META-INF/") && entryName.endsWith(".tld")) {
                    try {
                        // Create a TLD location URI using the resource path
                        String tldLocation = "jar:resource:" + jarResourcePath + "!/" + entryName;
                        
                        // Read the TLD content from the JAR entry
                        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                        byte[] data = new byte[8192];
                        int bytesRead;
                        while ((bytesRead = jar.read(data)) != -1) {
                            buffer.write(data, 0, bytesRead);
                        }
                        
                        // Parse the TLD from the buffered content
                        try (ByteArrayInputStream tldStream = new ByteArrayInputStream(buffer.toByteArray())) {
                            TagLibraryDescriptor tld = TldParser.parseTld(tldStream, tldLocation);
                            if (tld != null && tld.getUri() != null) {
                                uriToLocationMap.put(tld.getUri(), tldLocation);
                                LOGGER.fine("Found TLD in JAR resource: " + tld.getUri() + " -> " + entryName);
                            }
                        }
                    } catch (Exception e) {
                        LOGGER.log(Level.FINE, "Error processing TLD entry " + entryName + " in " + jarResourcePath, e);
                    }
                }
                jar.closeEntry();
            }
        } catch (IOException e) {
            LOGGER.log(Level.FINE, "Error scanning JAR resource for TLDs: " + jarResourcePath, e);
        }
    }

    /**
     * Scans a JAR file for TLD files in META-INF.
     */
    private void scanJarForTlds(File jarFile) {
        try (JarFile jar = new JarFile(jarFile)) {
            java.util.Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String entryName = entry.getName();
                
                if (entryName.startsWith("META-INF/") && entryName.endsWith(".tld")) {
                    try (InputStream tldStream = jar.getInputStream(entry)) {
                        TagLibraryDescriptor tld = TldParser.parseTld(tldStream, "jar:" + jarFile.toURI() + "!/" + entryName);
                        if (tld != null && tld.getUri() != null) {
                            uriToLocationMap.put(tld.getUri(), "jar:" + jarFile.toURI() + "!/" + entryName);
                            LOGGER.fine("Found TLD in JAR: " + tld.getUri() + " -> " + entryName);
                        }
                    } catch (Exception e) {
                        LOGGER.log(Level.FINE, "Error processing TLD entry " + entryName + " in " + jarFile.getName(), e);
                    }
                }
            }
        } catch (IOException e) {
            LOGGER.log(Level.FINE, "Error scanning JAR file for TLDs: " + jarFile.getName(), e);
        }
    }

    /**
     * Processes a TLD file to extract URI mapping.
     */
    private void processTldFile(String tldPath) {
        try (InputStream tldStream = servletContext.getResourceAsStream(tldPath)) {
            if (tldStream != null) {
                TagLibraryDescriptor tld = TldParser.parseTld(tldStream, tldPath);
                if (tld != null && tld.getUri() != null) {
                    uriToLocationMap.put(tld.getUri(), tldPath);
                    LOGGER.fine("Found TLD: " + tld.getUri() + " -> " + tldPath);
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Error processing TLD file: " + tldPath, e);
        }
    }

    /**
     * Attempts to resolve a taglib URI using various methods.
     */
    private TagLibraryDescriptor tryResolveTaglib(String uri) throws IOException {
        // 1. Try explicit mapping from JSP config
        String location = uriToLocationMap.get(uri);
        if (location != null) {
            return loadTldFromLocation(location);
        }

        // 2. Try direct URI as resource path (relative to WEB-INF)
        if (uri.startsWith("/")) {
            TagLibraryDescriptor tld = loadTldFromLocation(uri);
            if (tld != null) {
                return tld;
            }
        }

        // 3. Try URI as path in WEB-INF
        String webInfPath = "/WEB-INF/" + (uri.startsWith("/") ? uri.substring(1) : uri);
        TagLibraryDescriptor tld = loadTldFromLocation(webInfPath);
        if (tld != null) {
            return tld;
        }

        // 4. Try adding .tld extension if not present
        if (!uri.endsWith(".tld")) {
            tld = loadTldFromLocation(webInfPath + ".tld");
            if (tld != null) {
                return tld;
            }
        }

        return null;
    }

    /**
     * Loads a TLD from a specific location.
     */
    private TagLibraryDescriptor loadTldFromLocation(String location) throws IOException {
        InputStream tldStream = null;
        
        try {
            if (location.startsWith("jar:")) {
                // Handle JAR URLs - this is more complex and would require URL handling
                // For now, return null and let the scanning handle JAR-based TLDs
                return null;
            } else {
                // Regular servlet context resource
                tldStream = servletContext.getResourceAsStream(location);
            }

            if (tldStream != null) {
                return TldParser.parseTld(tldStream, location);
            }
        } finally {
            if (tldStream != null) {
                try {
                    tldStream.close();
                } catch (IOException e) {
                    // Log but don't fail
                    LOGGER.fine("Error closing TLD stream for: " + location);
                }
            }
        }

        return null;
    }

    /**
     * Clears the taglib cache.
     */
    public void clearCache() {
        taglibCache.clear();
        LOGGER.fine("Cleared taglib cache");
    }

    /**
     * Gets all cached taglib URIs.
     * 
     * @return a collection of cached taglib URIs
     */
    public java.util.Set<String> getCachedTaglibUris() {
        return java.util.Collections.unmodifiableSet(taglibCache.keySet());
    }
}
