/*
 * JspHotReloader.java
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

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.MessageFormat;
import java.util.HashMap;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.servlet.Context;

/**
 * Monitors JSP files for changes and invalidates cached servlets when modifications
 * are detected, enabling instant reload during development.
 * 
 * <p>This class uses Java NIO's {@link WatchService} to efficiently monitor the
 * filesystem for changes without polling.</p>
 * 
 * <p>When a JSP file is modified:</p>
 * <ol>
 *   <li>The dependency tracker is notified</li>
 *   <li>All dependent JSP files are also invalidated</li>
 *   <li>The JSP servlet cache is cleared for affected files</li>
 *   <li>Next request will trigger recompilation</li>
 * </ol>
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class JspHotReloader extends Thread {

    private static final Logger LOGGER = Logger.getLogger(JspHotReloader.class.getName());
    private static final ResourceBundle L10N = 
        ResourceBundle.getBundle("org.bluezoo.gumdrop.servlet.jsp.L10N");
    
    private final Context context;
    private final Path webappRoot;
    private final WatchService watchService;
    final Map<WatchKey, Path> watchKeys;
    private final JspReloadCallback callback;
    
    private volatile boolean running = true;
    
    /**
     * Callback interface for JSP reload events.
     */
    public interface JspReloadCallback {
        /**
         * Called when a JSP file has been modified.
         * 
         * @param jspPath the path of the modified JSP file (relative to webapp root)
         * @param affectedPaths set of JSP paths that need recompilation (including dependents)
         */
        void onJSPModified(String jspPath, Set<String> affectedPaths);
    }
    
    /**
     * Creates a new JSP hot reloader.
     * 
     * @param context the servlet context
     * @param webappRoot the root directory of the web application
     * @param callback callback for reload events
     * @throws IOException if the watch service cannot be created
     */
    public JspHotReloader(Context context, Path webappRoot, JspReloadCallback callback) 
            throws IOException {
        this(context, webappRoot, callback, FileSystems.getDefault().newWatchService());
    }

    /**
     * Creates a hot reloader over the given watch service (a seam for tests
     * that supply a mock, since not every file system has a watch
     * service).
     */
    JspHotReloader(Context context, Path webappRoot, JspReloadCallback callback,
            WatchService watchService) {
        super("jsp-hot-reload-" + context.getContextPath());
        this.context = context;
        this.webappRoot = webappRoot;
        this.callback = callback;
        this.watchService = watchService;
        this.watchKeys = new HashMap<WatchKey, Path>();
        
        setDaemon(true);
        setPriority(Thread.MIN_PRIORITY);
    }
    
    /**
     * Starts monitoring JSP directories.
     * 
     * @throws IOException if directory registration fails
     */
    public void startMonitoring() throws IOException {
        // Register all directories containing JSP files
        registerJSPDirectories(webappRoot);
        start();
        
        if (LOGGER.isLoggable(Level.INFO)) {
            String msg = MessageFormat.format(
                L10N.getString("hotreload.started"), 
                context.getContextPath(), watchKeys.size());
            LOGGER.info(msg);
        }
    }
    
    /**
     * Stops monitoring JSP files.
     */
    public void stopMonitoring() {
        running = false;
        interrupt();
        
        try {
            watchService.close();
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, L10N.getString("hotreload.close_error"), e);
        }
    }
    
    @Override
    public void run() {
        while (running && !isInterrupted()) {
            try {
                WatchKey key = watchService.poll(500, TimeUnit.MILLISECONDS);
                if (key == null) {
                    continue;
                }
                
                processKey(key);
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
    
    /**
     * Processes the pending events of a signalled watch key. Package-private
     * so that tests can drive it without a live file system watcher.
     */
    void processKey(WatchKey key) {
        Path dir = watchKeys.get(key);
        if (dir == null) {
            key.reset();
            return;
        }
        
        for (WatchEvent<?> event : key.pollEvents()) {
            WatchEvent.Kind<?> kind = event.kind();
            
            if (kind == StandardWatchEventKinds.OVERFLOW) {
                continue;
            }
            
            Path changed = (Path) event.context();
            Path changedPath = dir.resolve(changed);
            String fileName = changed.toString().toLowerCase();
            
            // Check if it's a JSP-related file
            if (isJSPFile(fileName) || isTLDFile(fileName) || isTagFile(fileName)) {
                handleFileChange(changedPath, kind);
            }
            
            // If a new directory is created, register it
            if (kind == StandardWatchEventKinds.ENTRY_CREATE && 
                Files.isDirectory(changedPath)) {
                try {
                    registerDirectory(changedPath);
                } catch (IOException e) {
                    String msg = MessageFormat.format(
                        L10N.getString("hotreload.watch_failed"), changedPath);
                    LOGGER.log(Level.WARNING, msg, e);
                }
            }
        }
        
        if (!key.reset()) {
            watchKeys.remove(key);
        }
    }
    
    /**
     * Handles a file change event.
     */
    private void handleFileChange(Path changedPath, WatchEvent.Kind<?> kind) {
        String jspPath = getRelativePath(changedPath);
        
        if (LOGGER.isLoggable(Level.FINE)) {
            String msg = MessageFormat.format(
                L10N.getString("hotreload.change_detected"), kind.name(), jspPath);
            LOGGER.fine(msg);
        }
        
        // Invalidate the JSP and get affected files
        Set<String> affected = context.invalidateJSP(jspPath);
        
        // Notify callback
        if (callback != null) {
            callback.onJSPModified(jspPath, affected);
        }
    }
    
    /**
     * Registers all directories that may contain JSP files.
     */
    private void registerJSPDirectories(Path start) throws IOException {
        Files.walkFileTree(start, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) 
                    throws IOException {
                String name = dir.getFileName().toString();
                
                // Skip WEB-INF/classes and META-INF
                if ("classes".equals(name) || "META-INF".equals(name)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                
                registerDirectory(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
    
    /**
     * Registers a directory with the watch service. Overridable so that
     * tests can hand back a mock key for a path on a file system that
     * cannot be watched.
     */
    WatchKey watch(Path dir, WatchEvent.Kind<?>... kinds) throws IOException {
        return dir.register(watchService, kinds);
    }

    /**
     * Registers a single directory for watching.
     */
    private void registerDirectory(Path dir) throws IOException {
        WatchKey key = watch(dir,
            StandardWatchEventKinds.ENTRY_CREATE,
            StandardWatchEventKinds.ENTRY_DELETE,
            StandardWatchEventKinds.ENTRY_MODIFY);
        watchKeys.put(key, dir);
        
        if (LOGGER.isLoggable(Level.FINE)) {
            String msg = MessageFormat.format(
                L10N.getString("hotreload.watching"), dir);
            LOGGER.fine(msg);
        }
    }
    
    /**
     * Gets the path relative to the webapp root.
     */
    private String getRelativePath(Path path) {
        Path relativePath = webappRoot.relativize(path);
        StringBuilder buf = new StringBuilder();
        for (Path name : relativePath) {
            buf.append('/');
            buf.append(name.toString());
        }
        return buf.toString();
    }
    
    /**
     * Checks if a file is a JSP file.
     */
    private boolean isJSPFile(String fileName) {
        return fileName.endsWith(".jsp") || 
               fileName.endsWith(".jspx") || 
               fileName.endsWith(".jspf");
    }
    
    /**
     * Checks if a file is a TLD file.
     */
    private boolean isTLDFile(String fileName) {
        return fileName.endsWith(".tld");
    }
    
    /**
     * Checks if a file is a tag file.
     */
    private boolean isTagFile(String fileName) {
        return fileName.endsWith(".tag") || 
               fileName.endsWith(".tagx");
    }
}

