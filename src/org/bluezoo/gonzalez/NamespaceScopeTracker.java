/*
 * NamespaceScopeTracker.java
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * High-performance namespace scope tracker for streaming XML parsing.
 * 
 * <p>This class manages namespace prefix-to-URI mappings with proper scoping
 * for nested elements. It uses modern Java collections (HashMap, ArrayList)
 * for better performance than the legacy SAX2 NamespaceSupport class.
 * 
 * <p>Key design decisions:
 * <ul>
 * <li>No synchronization (single-threaded parser)</li>
 * <li>HashMap for O(1) prefix lookup</li>
 * <li>ArrayList for scope stack (typically shallow nesting)</li>
 * <li>Separate tracking for xmlns attributes vs. regular attributes</li>
 * </ul>
 * 
 * <p>Per XML Namespaces 1.0:
 * <ul>
 * <li>Default namespace: prefix="" maps to URI</li>
 * <li>Unbound prefix: prefix not in scope</li>
 * <li>No namespace: prefix="" maps to "" (empty string)</li>
 * <li>XML namespace: "xml" prefix pre-bound to http://www.w3.org/XML/1998/namespace</li>
 * <li>XMLNS namespace: "xmlns" prefix pre-bound to http://www.w3.org/2000/xmlns/</li>
 * </ul>
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class NamespaceScopeTracker {
    
    /** XML namespace URI (pre-bound to "xml" prefix) */
    public static final String XML_NAMESPACE_URI = "http://www.w3.org/XML/1998/namespace";
    
    /** XMLNS namespace URI (pre-bound to "xmlns" prefix) */
    public static final String XMLNS_NAMESPACE_URI = "http://www.w3.org/2000/xmlns/";
    
    /**
     * Represents a namespace scope level.
     * Each element introduces a new scope that may declare new namespace bindings.
     */
    private static class Scope {
        /** Prefix-to-URI mappings declared at this level */
        final Map<String, String> bindings;
        
        /** Number of declarations at this level (for efficient iteration) */
        int declarationCount;
        
        Scope() {
            this.bindings = new HashMap<>();
            this.declarationCount = 0;
        }
    }
    
    /**
     * Stack of namespace scopes.
     * Index 0 is the root scope (contains xml and xmlns pre-bindings).
     * Index scopeDepth is the current scope.
     */
    private final ArrayList<Scope> scopes;
    
    /**
     * Current scope depth (0 = root scope, increments with each element).
     */
    private int scopeDepth;
    
    /**
     * Flat map of all active prefix-to-URI bindings.
     * This is updated as we push/pop scopes for fast lookup.
     */
    private final Map<String, String> activeBindings;
    
    /**
     * Optional intern pool for namespace URIs and prefixes.
     * If set, all strings will be interned for better performance.
     */
    private InternedStringPool internPool;
    
    /**
     * Creates a new namespace scope tracker with pre-bound xml and xmlns prefixes.
     */
    public NamespaceScopeTracker() {
        this.scopes = new ArrayList<>();
        this.scopeDepth = -1; // Will become 0 on first pushContext()
        this.activeBindings = new HashMap<>();
        
        // Initialize root scope
        pushContext();
        
        // Pre-bind xml and xmlns prefixes (per XML Namespaces spec)
        declarePrefix("xml", XML_NAMESPACE_URI);
        declarePrefix("xmlns", XMLNS_NAMESPACE_URI);
    }
    
    /**
     * Sets the intern pool for namespace URIs and prefixes.
     * 
     * @param pool the intern pool (null to disable interning)
     */
    public void setInternPool(InternedStringPool pool) {
        this.internPool = pool;
    }
    
    /**
     * Pushes a new namespace context (called when entering an element).
     * Bindings declared in this context will be visible until popContext() is called.
     */
    public void pushContext() {
        scopeDepth++;
        
        // Reuse existing scope object if available, otherwise create new
        if (scopeDepth < scopes.size()) {
            Scope scope = scopes.get(scopeDepth);
            scope.bindings.clear();
            scope.declarationCount = 0;
        } else {
            scopes.add(new Scope());
        }
    }
    
    /**
     * Pops the current namespace context (called when leaving an element).
     * All bindings declared at this level are removed.
     */
    public void popContext() {
        if (scopeDepth < 0) {
            throw new IllegalStateException("Cannot pop root namespace context");
        }
        
        // Remove bindings declared at this level from activeBindings
        Scope scope = scopes.get(scopeDepth);
        for (String prefix : scope.bindings.keySet()) {
            // Restore previous binding from outer scope (if any)
            String outerBinding = findBindingInOuterScopes(prefix, scopeDepth - 1);
            if (outerBinding != null) {
                activeBindings.put(prefix, outerBinding);
            } else {
                activeBindings.remove(prefix);
            }
        }
        
        scopeDepth--;
    }
    
    /**
     * Declares a namespace prefix binding in the current scope.
     * 
     * @param prefix the namespace prefix (empty string for default namespace)
     * @param uri the namespace URI (empty string to undeclare default namespace)
     * @return true if this is a new binding, false if re-declaring same binding
     */
    public boolean declarePrefix(String prefix, String uri) {
        if (prefix == null || uri == null) {
            throw new IllegalArgumentException("Prefix and URI must not be null");
        }
        
        // Intern namespace URIs if pool is available.
        // While URIs are initially attribute values, they have very limited variety
        // (typically 5-10 per document) and are reused constantly during namespace
        // processing, so interning provides significant benefit.
        if (internPool != null) {
            uri = internPool.intern(uri);
        }
        
        Scope scope = scopes.get(scopeDepth);
        
        // Check if already declared at this level with same URI
        String existing = scope.bindings.get(prefix);
        if (uri.equals(existing)) {
            return false; // Same binding, not new
        }
        
        // Add/update binding
        scope.bindings.put(prefix, uri);
        scope.declarationCount++;
        activeBindings.put(prefix, uri);
        
        return true;
    }
    
    /**
     * Gets the namespace URI bound to a prefix in the current scope.
     * 
     * In XML Namespaces 1.1, an empty URI means the prefix is unbound.
     * 
     * @param prefix the namespace prefix (empty string for default namespace)
     * @return the namespace URI, or null if prefix is not bound or was unbound (empty URI)
     */
    public String getURI(String prefix) {
        if (prefix == null) {
            throw new IllegalArgumentException("Prefix must not be null");
        }
        String uri = activeBindings.get(prefix);
        // Empty URI means unbound (XML Namespaces 1.1)
        if (uri != null && uri.isEmpty()) {
            return null;
        }
        return uri;
    }
    
    /**
     * Gets a prefix bound to a namespace URI in the current scope.
     * If multiple prefixes are bound to the same URI, returns one arbitrarily.
     * 
     * @param uri the namespace URI
     * @return a prefix bound to the URI, or null if no binding exists
     */
    public String getPrefix(String uri) {
        if (uri == null) {
            throw new IllegalArgumentException("URI must not be null");
        }
        
        for (Map.Entry<String, String> entry : activeBindings.entrySet()) {
            if (uri.equals(entry.getValue())) {
                return entry.getKey();
            }
        }
        return null;
    }
    
    /**
     * Gets all prefixes bound to a namespace URI in the current scope.
     * 
     * @param uri the namespace URI
     * @return iterator over prefixes bound to the URI (may be empty)
     */
    public Iterator<String> getPrefixes(String uri) {
        if (uri == null) {
            throw new IllegalArgumentException("URI must not be null");
        }
        
        ArrayList<String> prefixes = new ArrayList<>();
        for (Map.Entry<String, String> entry : activeBindings.entrySet()) {
            if (uri.equals(entry.getValue())) {
                prefixes.add(entry.getKey());
            }
        }
        return prefixes.iterator();
    }
    
    /**
     * Gets all prefixes declared in the current scope.
     * 
     * @return iterator over all currently bound prefixes
     */
    public Iterator<String> getAllPrefixes() {
        return activeBindings.keySet().iterator();
    }
    
    /**
     * Gets all namespace declarations made at the current scope level only.
     * Does not include inherited bindings from outer scopes.
     * 
     * @return iterator over (prefix, URI) entries for current level
     */
    public Iterator<Map.Entry<String, String>> getCurrentScopeDeclarations() {
        if (scopeDepth < 0) {
            return new ArrayList<Map.Entry<String, String>>().iterator();
        }
        return scopes.get(scopeDepth).bindings.entrySet().iterator();
    }
    
    /**
     * Processes a raw XML qualified name into namespace components.
     * 
     * <p>Returns a QName object from the pool containing:
     * <ul>
     * <li>uri: the namespace URI (empty string if no namespace)</li>
     * <li>localName: the local part of the name (after colon)</li>
     * <li>qName: the original qualified name (prefix:localName or just localName)</li>
     * </ul>
     * 
     * @param rawQName the qualified name (may contain prefix)
     * @param isAttribute true if this is an attribute name (affects default namespace)
     * @param pool the QName pool to use for retrieving/caching QName objects
     * @return QName object from the pool
     * @throws NamespaceException if namespace well-formedness constraint is violated
     */
    public QName processName(String rawQName, boolean isAttribute, QNamePool pool) throws NamespaceException {
        if (rawQName == null || rawQName.isEmpty()) {
            throw new NamespaceException("QName must not be null or empty");
        }
        
        int colonIndex = rawQName.indexOf(':');
        
        // Checkout QName from pool
        QName qname = pool.checkout();
        
        if (colonIndex == -1) {
            // No prefix - validate no xmlns as element/attribute name
            if ("xmlns".equals(rawQName)) {
                pool.returnToPool(qname);
                throw new NamespaceException("Illegal QName: 'xmlns' cannot be used as " + 
                    (isAttribute ? "attribute" : "element") + " name");
            }
            
            // No prefix
            String namespaceURI;
            if (isAttribute) {
                // Attributes without prefix are not in any namespace (per XML Namespaces spec)
                namespaceURI = "";
            } else {
                // Elements without prefix use default namespace
                String defaultNS = getURI("");
                namespaceURI = (defaultNS != null) ? defaultNS : "";
            }
            qname.update(namespaceURI, rawQName, rawQName);
            return qname;
        } else {
            // Has prefix - validate QName syntax
            
            // Check for multiple colons
            int secondColon = rawQName.indexOf(':', colonIndex + 1);
            if (secondColon != -1) {
                pool.returnToPool(qname);
                throw new NamespaceException("Illegal QName '" + rawQName + 
                    "': QNames may contain at most one colon");
            }
            
            String prefix = rawQName.substring(0, colonIndex);
            String localName = rawQName.substring(colonIndex + 1);
            
            // Check for leading colon (empty prefix)
            if (prefix.isEmpty()) {
                pool.returnToPool(qname);
                throw new NamespaceException("Illegal QName '" + rawQName + 
                    "': QName cannot start with colon");
            }
            
            // Check for trailing colon (empty localName)
            if (localName.isEmpty()) {
                pool.returnToPool(qname);
                throw new NamespaceException("Illegal QName '" + rawQName + 
                    "': QName cannot end with colon");
            }
            
            // Check for xmlns: prefix in element/attribute names
            if ("xmlns".equals(prefix)) {
                pool.returnToPool(qname);
                throw new NamespaceException("Illegal QName '" + rawQName + 
                    "': 'xmlns:' prefix cannot be used in " + 
                    (isAttribute ? "non-namespace-declaration attributes" : "element names"));
            }
            
            // Look up prefix
            String namespaceURI = getURI(prefix);
            if (namespaceURI == null) {
                // Return QName to pool before throwing
                pool.returnToPool(qname);
                throw new NamespaceException("Unbound namespace prefix: " + prefix);
            }
            
            qname.update(namespaceURI, localName, rawQName);
            return qname;
        }
    }
    
    /**
     * Resets the tracker to initial state.
     * Removes all scopes except the root scope with pre-bound xml and xmlns.
     */
    public void reset() {
        scopes.clear();
        activeBindings.clear();
        scopeDepth = -1;
        
        // Re-initialize root scope with pre-bindings
        pushContext();
        declarePrefix("xml", XML_NAMESPACE_URI);
        declarePrefix("xmlns", XMLNS_NAMESPACE_URI);
    }
    
    /**
     * Helper: finds a prefix binding in outer scopes (not including current scope).
     * Used when popping context to restore previous bindings.
     */
    private String findBindingInOuterScopes(String prefix, int maxDepth) {
        for (int i = maxDepth; i >= 0; i--) {
            String uri = scopes.get(i).bindings.get(prefix);
            if (uri != null) {
                return uri;
            }
        }
        return null;
    }
}

