/*
 * HotDeploymentThread.java
 * Copyright (C) 2005, 2025 Chris Burdess
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

package org.bluezoo.gumdrop.servlet;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitor;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.MessageFormat;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import org.xml.sax.SAXException;

/**
 * This thread is used to monitor the state of various critical resources in
 * the web application (if the context uses file-based storage) or the WAR
 * file itself (if the context uses a WAR). When these resources are
 * updated, the hot-deployment thread redeploys the context.
 * <p>
 * The critical resources are defined as files under the WEB-INF directory.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class HotDeploymentThread extends Thread {

    final Container container;
    final Map<Context,Long> warLastModified;
    final WatchService watchService;
    final Map<WatchKey,Context> watchKeys;

    HotDeploymentThread(Container container) throws IOException {
        super("hot-deploy");
        this.container = container;
        setDaemon(true);
        setPriority(Thread.MIN_PRIORITY);
        warLastModified = new HashMap<>();
        watchService = FileSystems.getDefault().newWatchService();
        watchKeys = new HashMap<>();
    }

    public void run() {
        // Set up watch keys or warLastModified for contexts
        for (Context context : container.contexts) {
            init(context);
        }

        while (!isInterrupted()) {
            try {
                WatchKey key = watchService.poll(1000, TimeUnit.MILLISECONDS);
                if (key != null) { // filesystem change
                    Context context = watchKeys.get(key);
                    if (context != null) {
                        for (WatchEvent<?> event : key.pollEvents()) {
                            WatchEvent.Kind<?> kind = event.kind();
                            if (kind == StandardWatchEventKinds.OVERFLOW) {
                                continue;
                            }
                            Path changed = (Path) event.context();
                            if (key.watchable().equals(context.root.toPath())) {
                                // Only check for WEB-INF lifecycle events
                                if (!changed.getFileName().toString().equals("WEB-INF")) {
                                    break;
                                }
                            }
                            Path changedPath = ((Path) key.watchable()).resolve(changed);
                            if (kind == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(changedPath)) {
                                try {
                                    registerAll(changedPath, context);
                                } catch (IOException e) {
                                    String message = Context.L10N.getString("err.watch_new_directory");
                                    message = MessageFormat.format(message, context.getContextPath());
                                    Context.LOGGER.log(Level.SEVERE, message, e);
                                }
                            }
                            redeploy(context);
                            break;
                        }
                    }
                    if (!key.reset()) {
                        watchKeys.remove(key);
                    }
                }
                // Check contexts with WAR files for changes
                for (Context context : warLastModified.keySet()) {
                    long registeredLastModified = warLastModified.get(context);
                    long lastModified = context.root.lastModified();
                    if (lastModified != registeredLastModified) {
                        warLastModified.put(context, lastModified);
                        redeploy(context);
                    }
                }
            } catch (InterruptedException e) {
                try {
                    watchService.close();
                } catch (IOException e2) {
                    String message = Context.L10N.getString("err.watch");
                    Context.LOGGER.log(Level.SEVERE, message, e2);
                }
                return;
            }
        }
    }

    void init(Context context) {
        if (context.root.isDirectory()) { // file based, use watch key
            try {
                // Always watch the context root for WEB-INF
                // creation/deletion
                WatchKey key = context.root.toPath().register(watchService,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_DELETE);
                watchKeys.put(key, context);
                // If WEB-INF exists, also register it and all
                // of its subdirectories
                File webInf = new File(context.root, "WEB-INF");
                if (webInf.exists() && webInf.isDirectory()) {
                    registerAll(webInf.toPath(), context);
                }
            } catch (IOException e) {
                String message = Context.L10N.getString("err.watch_web_inf");
                message = MessageFormat.format(message, context.getContextPath());
                Context.LOGGER.log(Level.SEVERE, message, e);
            }
        } else { // WAR based, just store last-modified of war file
            long lastModified = context.root.lastModified();
            warLastModified.put(context, lastModified);
        }
    }

    void registerAll(Path start, final Context context) throws IOException {
        FileVisitor<Path> visitor = new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                WatchKey key = dir.register(watchService,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_DELETE,
                        StandardWatchEventKinds.ENTRY_MODIFY);
                watchKeys.put(key, context);
                return FileVisitResult.CONTINUE;
            }
        };
        Files.walkFileTree(start, visitor);
    }

    boolean redeploy(Context context) {
        try {
            synchronized (context) {
                context.reload();
            }
            return true;
        } catch (IOException | SAXException e) {
            String message = Context.L10N.getString("err.reload");
            message = MessageFormat.format(message, context.getContextPath());
            Context.LOGGER.log(Level.SEVERE, message, e);
            return false;
        }
    }

}
