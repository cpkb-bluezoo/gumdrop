/*
 * JSPPage.java
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Represents a parsed JSP page as an abstract syntax tree (AST).
 * This is the root node of the JSP structure containing all page directives,
 * elements, and content.
 * 
 * <p>A JSPPage contains:
 * <ul>
 * <li>Page directives (imports, content type, etc.)</li>
 * <li>Tag library directives</li>
 * <li>JSP elements (scriptlets, expressions, declarations)</li>
 * <li>HTML/XML content</li>
 * <li>Custom tag usage</li>
 * </ul>
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class JSPPage {
    
    private final String uri;
    private final String encoding;
    
    // Page directives and settings
    private String contentType = "text/html; charset=UTF-8";
    private boolean isErrorPage = false;
    private String errorPage = null;
    private boolean session = true;
    private boolean autoFlush = true;
    private int buffer = 8192;
    private boolean isThreadSafe = true;
    
    // Imports and class information
    private final List<String> imports = new ArrayList<>();
    private final Map<String, String> tagLibraries = new HashMap<>();
    
    // JSP content elements in document order
    private final List<JSPElement> elements = new ArrayList<>();
    
    // Static include file paths (for dependency tracking)
    private final List<String> includes = new ArrayList<>();
    
    // Generated servlet information
    private String packageName = null;
    private String className = null;
    
    /**
     * Creates a new JSP page representation.
     * 
     * @param uri the URI/path of the JSP file
     * @param encoding the character encoding of the JSP source
     */
    public JSPPage(String uri, String encoding) {
        this.uri = uri;
        this.encoding = encoding;
        
        // Add default imports as per JSP specification
        addDefaultImports();
    }
    
    /**
     * Adds default imports required by JSP specification.
     */
    private void addDefaultImports() {
        imports.add("java.lang.*");
        imports.add("javax.servlet.*");
        imports.add("javax.servlet.http.*");
        imports.add("javax.servlet.jsp.*");
    }
    
    /**
     * Gets the URI/path of this JSP page.
     * 
     * @return the JSP URI
     */
    public String getUri() {
        return uri;
    }
    
    /**
     * Gets the character encoding of this JSP page.
     * 
     * @return the character encoding
     */
    public String getEncoding() {
        return encoding;
    }
    
    /**
     * Gets the content type for this JSP page.
     * 
     * @return the content type (default: "text/html; charset=UTF-8")
     */
    public String getContentType() {
        return contentType;
    }
    
    /**
     * Sets the content type for this JSP page.
     * 
     * @param contentType the content type
     */
    public void setContentType(String contentType) {
        this.contentType = contentType;
    }
    
    /**
     * Checks if this is an error page.
     * 
     * @return {@code true} if this is an error page
     */
    public boolean isErrorPage() {
        return isErrorPage;
    }
    
    /**
     * Sets whether this is an error page.
     * 
     * @param isErrorPage {@code true} if this is an error page
     */
    public void setErrorPage(boolean isErrorPage) {
        this.isErrorPage = isErrorPage;
    }
    
    /**
     * Gets the error page URI for this JSP page.
     * 
     * @return the error page URI, or {@code null} if none specified
     */
    public String getErrorPage() {
        return errorPage;
    }
    
    /**
     * Sets the error page URI for this JSP page.
     * 
     * @param errorPage the error page URI
     */
    public void setErrorPage(String errorPage) {
        this.errorPage = errorPage;
    }
    
    /**
     * Checks if session is enabled for this JSP page.
     * 
     * @return {@code true} if session is enabled (default)
     */
    public boolean isSession() {
        return session;
    }
    
    /**
     * Sets whether session is enabled for this JSP page.
     * 
     * @param session {@code true} to enable session
     */
    public void setSession(boolean session) {
        this.session = session;
    }
    
    /**
     * Alias for isSession() - checks if session is enabled for this JSP page.
     * 
     * @return {@code true} if session is enabled (default)
     */
    public boolean isSessionEnabled() {
        return session;
    }
    
    /**
     * Checks if auto-flush is enabled for the output buffer.
     * 
     * @return {@code true} if auto-flush is enabled (default)
     */
    public boolean isAutoFlush() {
        return autoFlush;
    }
    
    /**
     * Sets whether auto-flush is enabled for the output buffer.
     * 
     * @param autoFlush {@code true} to enable auto-flush
     */
    public void setAutoFlush(boolean autoFlush) {
        this.autoFlush = autoFlush;
    }
    
    /**
     * Gets the buffer size for the output buffer.
     * 
     * @return the buffer size in bytes (default: 8192)
     */
    public int getBuffer() {
        return buffer;
    }
    
    /**
     * Sets the buffer size for the output buffer.
     * 
     * @param buffer the buffer size in bytes, or 0 for no buffering
     */
    public void setBuffer(int buffer) {
        this.buffer = buffer;
    }
    
    /**
     * Checks if this JSP page is thread-safe.
     * 
     * @return {@code true} if thread-safe (default)
     */
    public boolean isThreadSafe() {
        return isThreadSafe;
    }
    
    /**
     * Sets whether this JSP page is thread-safe.
     * 
     * @param isThreadSafe {@code true} if thread-safe
     */
    public void setThreadSafe(boolean isThreadSafe) {
        this.isThreadSafe = isThreadSafe;
    }
    
    /**
     * Adds an import statement to this JSP page.
     * 
     * @param importStatement the import statement (e.g., "java.util.List")
     */
    public void addImport(String importStatement) {
        if (!imports.contains(importStatement)) {
            imports.add(importStatement);
        }
    }
    
    /**
     * Gets all import statements for this JSP page.
     * 
     * @return a list of import statements
     */
    public List<String> getImports() {
        return new ArrayList<>(imports);
    }
    
    /**
     * Adds a tag library declaration to this JSP page.
     * 
     * @param prefix the tag prefix (e.g., "c" for JSTL core)
     * @param uri the tag library URI
     */
    public void addTagLibrary(String prefix, String uri) {
        tagLibraries.put(prefix, uri);
    }
    
    
    /**
     * Adds a JSP element to this page.
     * 
     * @param element the JSP element to add
     */
    public void addElement(JSPElement element) {
        elements.add(element);
    }
    
    /**
     * Gets all JSP elements in this page in document order.
     * 
     * @return a list of JSP elements
     */
    public List<JSPElement> getElements() {
        return new ArrayList<>(elements);
    }
    
    /**
     * Gets the generated servlet package name.
     * 
     * @return the package name, or {@code null} if not set
     */
    public String getPackageName() {
        return packageName;
    }
    
    /**
     * Sets the generated servlet package name.
     * 
     * @param packageName the package name
     */
    public void setPackageName(String packageName) {
        this.packageName = packageName;
    }
    
    /**
     * Gets the generated servlet class name.
     * 
     * @return the class name, or {@code null} if not set
     */
    public String getClassName() {
        return className;
    }
    
    /**
     * Sets the generated servlet class name.
     * 
     * @param className the class name
     */
    public void setClassName(String className) {
        this.className = className;
    }
    
    /**
     * Adds a taglib directive mapping.
     * 
     * @param prefix the tag prefix (e.g., "c", "fn")
     * @param uri the taglib URI (e.g., "http://java.sun.com/jsp/jstl/core")
     */
    public void addTaglibDirective(String prefix, String uri) {
        if (prefix != null && uri != null) {
            tagLibraries.put(prefix, uri);
        }
    }
    
    /**
     * Gets all taglib directive mappings.
     * 
     * @return an unmodifiable map of prefix to URI mappings
     */
    public Map<String, String> getTagLibraries() {
        return Collections.unmodifiableMap(tagLibraries);
    }
    
    /**
     * Gets the URI for a specific taglib prefix.
     * 
     * @param prefix the tag prefix
     * @return the taglib URI, or null if not found
     */
    public String getTaglibUri(String prefix) {
        return tagLibraries.get(prefix);
    }
    
    /**
     * Adds a static include file path (for dependency tracking).
     * 
     * @param includePath the path of the included file
     */
    public void addInclude(String includePath) {
        if (includePath != null && !includes.contains(includePath)) {
            includes.add(includePath);
        }
    }
    
    /**
     * Gets all static include file paths.
     * 
     * @return a list of included file paths
     */
    public List<String> getIncludes() {
        return new ArrayList<String>(includes);
    }
    
    @Override
    public String toString() {
        return "JSPPage{" +
                "uri='" + uri + '\'' +
                ", encoding='" + encoding + '\'' +
                ", elements=" + elements.size() +
                ", imports=" + imports.size() +
                ", tagLibraries=" + tagLibraries.size() +
                '}';
    }
}
