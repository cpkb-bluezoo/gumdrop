/*
 * JSPDependencyTracker.java
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

import java.io.File;
import java.text.MessageFormat;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.ServletContext;

/**
 * Tracks dependencies between JSP files to enable incremental compilation.
 * 
 * <p>JSP files can include other files via:</p>
 * <ul>
 *   <li>{@code <%@ include file="..." %>} - Static includes (compile-time)</li>
 *   <li>{@code <jsp:include page="..." />} - Dynamic includes (runtime)</li>
 *   <li>Taglib references</li>
 * </ul>
 * 
 * <p>This tracker monitors:</p>
 * <ul>
 *   <li>Last modification times of JSP files</li>
 *   <li>Dependencies between JSP files</li>
 *   <li>Taglib TLD files</li>
 * </ul>
 * 
 * <p>When a dependency changes, all dependent JSP files are marked for recompilation.</p>
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class JSPDependencyTracker {

    private static final Logger LOGGER = Logger.getLogger(JSPDependencyTracker.class.getName());
    private static final ResourceBundle L10N = 
        ResourceBundle.getBundle("org.bluezoo.gumdrop.servlet.jsp.L10N");
    
    private final ServletContext servletContext;
    private final File webappRoot;
    
    // JSP path -> last modification time when compiled
    private final Map<String, Long> compilationTimes = new ConcurrentHashMap<String, Long>();
    
    // JSP path -> set of included file paths (includes, taglibs)
    private final Map<String, Set<String>> dependencies = new ConcurrentHashMap<String, Set<String>>();
    
    // Reverse mapping: included file -> set of JSP files that include it
    private final Map<String, Set<String>> dependents = new ConcurrentHashMap<String, Set<String>>();
    
    /**
     * Creates a new dependency tracker for the given servlet context.
     * 
     * @param servletContext the servlet context
     * @param webappRoot the root directory of the web application
     */
    public JSPDependencyTracker(ServletContext servletContext, File webappRoot) {
        this.servletContext = servletContext;
        this.webappRoot = webappRoot;
    }
    
    /**
     * Records that a JSP file was successfully compiled.
     * 
     * @param jspPath the JSP file path
     * @param includedFiles the set of files included by this JSP
     */
    public void recordCompilation(String jspPath, Set<String> includedFiles) {
        long now = System.currentTimeMillis();
        compilationTimes.put(jspPath, now);
        
        // Clear old dependencies
        Set<String> oldDeps = dependencies.get(jspPath);
        if (oldDeps != null) {
            for (String oldDep : oldDeps) {
                Set<String> depSet = dependents.get(oldDep);
                if (depSet != null) {
                    depSet.remove(jspPath);
                }
            }
        }
        
        // Record new dependencies
        dependencies.put(jspPath, new HashSet<String>(includedFiles));
        
        // Update reverse mapping
        for (String includedFile : includedFiles) {
            Set<String> depSet = dependents.get(includedFile);
            if (depSet == null) {
                depSet = new HashSet<String>();
                dependents.put(includedFile, depSet);
            }
            depSet.add(jspPath);
        }
        
        if (LOGGER.isLoggable(Level.FINE)) {
            String msg = MessageFormat.format(
                L10N.getString("dependency.recorded"), jspPath, includedFiles.size());
            LOGGER.fine(msg);
        }
    }
    
    /**
     * Checks if a JSP file needs recompilation.
     * 
     * <p>A JSP needs recompilation if:</p>
     * <ul>
     *   <li>It has never been compiled</li>
     *   <li>The JSP file has been modified since last compilation</li>
     *   <li>Any of its dependencies have been modified since last compilation</li>
     * </ul>
     * 
     * @param jspPath the JSP file path
     * @return true if the JSP needs recompilation
     */
    public boolean needsRecompilation(String jspPath) {
        Long lastCompiled = compilationTimes.get(jspPath);
        
        // Never compiled
        if (lastCompiled == null) {
            if (LOGGER.isLoggable(Level.FINE)) {
                String msg = MessageFormat.format(
                    L10N.getString("dependency.needs_compilation"), jspPath);
                LOGGER.fine(msg);
            }
            return true;
        }
        
        // Check JSP file itself
        if (hasChanged(jspPath, lastCompiled)) {
            if (LOGGER.isLoggable(Level.FINE)) {
                String msg = MessageFormat.format(
                    L10N.getString("dependency.needs_recompilation_modified"), jspPath);
                LOGGER.fine(msg);
            }
            return true;
        }
        
        // Check dependencies
        Set<String> deps = dependencies.get(jspPath);
        if (deps != null) {
            for (String dep : deps) {
                if (hasChanged(dep, lastCompiled)) {
                    if (LOGGER.isLoggable(Level.FINE)) {
                        String msg = MessageFormat.format(
                            L10N.getString("dependency.needs_recompilation_dependency"),
                            dep, jspPath);
                        LOGGER.fine(msg);
                    }
                    return true;
                }
            }
        }
        
        return false;
    }
    
    /**
     * Checks if a file has been modified since the given time.
     * 
     * @param path the file path (relative to webapp root)
     * @param since the timestamp to check against
     * @return true if the file has been modified since the given time
     */
    private boolean hasChanged(String path, long since) {
        File file = resolveFile(path);
        if (file == null || !file.exists()) {
            // File doesn't exist - consider it changed (will cause error on compile)
            return true;
        }
        return file.lastModified() > since;
    }
    
    /**
     * Resolves a path to a File object.
     * 
     * @param path the path (may be relative to webapp root)
     * @return the File, or null if the path cannot be resolved
     */
    private File resolveFile(String path) {
        if (path == null) {
            return null;
        }
        
        // Handle absolute paths
        if (path.startsWith("/")) {
            return new File(webappRoot, path.substring(1));
        }
        
        return new File(webappRoot, path);
    }
    
    /**
     * Invalidates a JSP file and all files that depend on it.
     * 
     * @param path the path that was modified
     * @return the set of JSP files that need recompilation
     */
    public Set<String> invalidate(String path) {
        Set<String> affected = new HashSet<String>();
        
        // If this is a JSP file itself, add it
        if (compilationTimes.containsKey(path)) {
            compilationTimes.remove(path);
            affected.add(path);
        }
        
        // Find all JSP files that depend on this path
        Set<String> deps = dependents.get(path);
        if (deps != null) {
            for (String dep : deps) {
                compilationTimes.remove(dep);
                affected.add(dep);
            }
        }
        
        if (LOGGER.isLoggable(Level.FINE) && !affected.isEmpty()) {
            String msg = MessageFormat.format(
                L10N.getString("dependency.invalidated"), affected.size(), path);
            LOGGER.fine(msg);
        }
        
        return affected;
    }
    
    /**
     * Clears all tracked compilation state.
     * This forces all JSP files to be recompiled on next access.
     */
    public void clear() {
        compilationTimes.clear();
        dependencies.clear();
        dependents.clear();
        
        if (LOGGER.isLoggable(Level.INFO)) {
            LOGGER.info(L10N.getString("dependency.cleared"));
        }
    }
    
    /**
     * Returns the set of dependencies for a JSP file.
     * 
     * @param jspPath the JSP file path
     * @return the set of dependencies, or an empty set if none
     */
    public Set<String> getDependencies(String jspPath) {
        Set<String> deps = dependencies.get(jspPath);
        if (deps == null) {
            return new HashSet<String>();
        }
        return new HashSet<String>(deps);
    }
    
    /**
     * Returns the set of JSP files that depend on the given path.
     * 
     * @param path the included file path
     * @return the set of dependent JSP files, or an empty set if none
     */
    public Set<String> getDependents(String path) {
        Set<String> deps = dependents.get(path);
        if (deps == null) {
            return new HashSet<String>();
        }
        return new HashSet<String>(deps);
    }
    
    /**
     * Returns the last compilation time for a JSP file.
     * 
     * @param jspPath the JSP file path
     * @return the last compilation time, or -1 if never compiled
     */
    public long getLastCompilationTime(String jspPath) {
        Long time = compilationTimes.get(jspPath);
        return time != null ? time : -1L;
    }
}

