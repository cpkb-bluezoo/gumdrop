/*
 * DeadPropertyStore.java
 * Copyright (C) 2026 Chris Burdess
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

package org.bluezoo.gumdrop.webdav;

import org.bluezoo.gonzalez.XMLWriter;
import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.StorageExecutor;
import org.bluezoo.gumdrop.util.AsyncFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.CompletionHandler;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.UserDefinedFileAttributeView;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.MessageFormat;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Dead property storage for WebDAV (RFC 4918 section 4).
 *
 * <p>Uses extended attributes (xattr) as the primary backend with
 * automatic fallback to XML sidecar files when xattrs are unavailable
 * or a value exceeds the xattr size limit.
 *
 * <p>Sidecar I/O is non-blocking via {@link AsyncFile}
 * with {@link CompletionHandler}. Sidecar XML is parsed with the
 * Gonzalez push parser and serialized with {@link XMLWriter}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc4918#section-4">RFC 4918 section 4</a>
 */
public final class DeadPropertyStore {

    private static final Logger LOGGER =
            Logger.getLogger(DeadPropertyStore.class.getName());
    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.webdav.L10N");

    /** xattr name prefix for dead properties. */
    private static final String XATTR_PREFIX = "user.webdav.";

    /** Sidecar file prefix. */
    static final String SIDECAR_PREFIX = ".webdav_";

    private static final long MAX_SIDECAR_SIZE = 1024 * 1024L;

    /** Sidecar XML namespace. */
    static final String PROPS_NAMESPACE = "urn:gumdrop:webdav-props";

    static final String PROPS_ELEM_PROPERTIES = "properties";
    static final String PROPS_ELEM_PROPERTY = "property";
    static final String PROPS_ATTR_NS = "ns";
    static final String PROPS_ATTR_NAME = "name";
    static final String PROPS_ATTR_XML = "xml";

    /** Storage mode. */
    public enum Mode {
        /** Try xattr first, fall back to sidecar. */
        AUTO,
        /** Only use extended attributes. */
        XATTR,
        /** Only use sidecar files. */
        SIDECAR,
        /** Dead properties disabled. */
        NONE
    }

    private Mode mode = Mode.AUTO;
    private volatile Gumdrop gumdrop;

    /** Where sidecars go instead of next to their resources, or null for siblings. */
    private Path sidecarRoot;
    private Path contentRoot;
    private Path configuredRoot;

    public DeadPropertyStore() {
    }

    /**
     * Sets the storage mode.
     *
     * @param mode the storage mode
     */
    public void setMode(Mode mode) {
        this.mode = mode;
    }

    /**
     * Keeps sidecars in their own directory tree instead of next to their
     * resources. A resource's sidecar is the file at its path relative to
     * {@code contentRoot} under {@code sidecarRoot}, so servers that mount
     * the same tree at different absolute paths read the same properties. A
     * collection's own properties are the {@code .webdav_.} file in its
     * directory there, as they are inside the collection itself in the
     * sibling layout.
     *
     * <p>Nothing is written into the content tree, and nothing in it is a
     * sidecar: a content file named {@code .webdav_report.pdf} is an
     * ordinary file. Extended attributes ({@link Mode#XATTR}, or the first
     * choice of {@link Mode#AUTO}) are unaffected.
     *
     * @param contentRoot the content tree the resources are in
     * @param sidecarRoot the directory for sidecars, or {@code null} for
     *        the sibling layout
     */
    public void setSidecarRoot(Path contentRoot, Path sidecarRoot) {
        this.sidecarRoot = sidecarRoot;
        this.configuredRoot = contentRoot;
        this.contentRoot = contentRoot == null ? null : canonicalRoot(contentRoot);
    }

    private static Path canonicalRoot(Path root) {
        try {
            return root.toRealPath();
        } catch (IOException e) {
            return root.toAbsolutePath().normalize();
        }
    }

    /**
     * Sets the runtime whose {@link StorageExecutor} backs blocking
     * dead-property work. Called by {@link FileHandler} on every request
     * (this store is shared across the connections a single {@code
     * FileHandler} configuration serves, so it has no owning connection
     * of its own to read this from).
     *
     * @param gumdrop the owning runtime, or null if none is running
     */
    void setGumdrop(Gumdrop gumdrop) {
        this.gumdrop = gumdrop;
    }

    /**
     * Returns the storage mode.
     *
     * @return the storage mode
     */
    public Mode getMode() {
        return mode;
    }

    // -- Public API (async) --

    /**
     * Loads all dead properties for a resource.
     *
     * @param resource the resource path
     * @param callback receives the properties
     */
    void getProperties(Path resource, DeadPropertyCallback callback) {
        getProperties(resource, null, callback);
    }

    /**
     * Loads all dead properties for a resource.
     *
     * @param resource the resource path
     * @param isDirectory whether {@code resource} is a directory (avoids a
     *        re-stat when the caller already knows); {@code null} to detect
     * @param callback receives the properties
     */
    void getProperties(Path resource, Boolean isDirectory,
                       DeadPropertyCallback callback) {
        if (mode == Mode.NONE) {
            callback.onProperties(new HashMap<String, DeadProperty>());
            return;
        }
        runOnStorage(callback, new Runnable() {
            @Override
            public void run() {
                getPropertiesBlocking(resource, isDirectory, callback);
            }
        });
    }

    private void getPropertiesBlocking(Path resource, Boolean isDirectory,
                                       DeadPropertyCallback callback) {
        boolean isDir = isDirectory != null
                ? isDirectory.booleanValue()
                : Files.isDirectory(resource);

        Map<String, DeadProperty> xattrProps = new HashMap<String, DeadProperty>();
        if (useXattr(resource)) {
            xattrProps = loadXattrProperties(resource);
        }

        if (useSidecar()) {
            final Map<String, DeadProperty> merged = xattrProps;
            Path sidecar = sidecarFor(resource, isDir);
            if (sidecarExists(sidecar)) {
                readSidecar(sidecar, new DeadPropertyCallback() {
                    @Override
                    public void onProperties(
                            Map<String, DeadProperty> sidecarProps) {
                        for (Map.Entry<String, DeadProperty> entry
                                : sidecarProps.entrySet()) {
                            if (!merged.containsKey(entry.getKey())) {
                                merged.put(entry.getKey(),
                                        entry.getValue());
                            }
                        }
                        callback.onProperties(merged);
                    }

                    @Override
                    public void onError(String error) {
                        LOGGER.warning(MessageFormat.format(
                                L10N.getString("warn.sidecar_read_failed"), error));
                        callback.onProperties(merged);
                    }
                });
                return;
            }
        }

        callback.onProperties(xattrProps);
    }

    /**
     * Sets a dead property on a resource.
     *
     * @param resource the resource path
     * @param ns the property namespace URI
     * @param name the property local name
     * @param value the property value
     * @param isXML true if the value contains XML
     * @param callback receives the result
     */
    void setProperty(Path resource, String ns, String name,
                     String value, boolean isXML,
                     DeadPropertyCallback callback) {
        setProperty(resource, null, ns, name, value, isXML, callback);
    }

    /**
     * Sets a dead property on a resource.
     *
     * @param resource the resource path
     * @param isDirectory whether {@code resource} is a directory, or
     *        {@code null} to detect
     * @param ns the property namespace URI
     * @param name the property local name
     * @param value the property value
     * @param isXML true if the value contains XML
     * @param callback receives the result
     */
    void setProperty(Path resource, Boolean isDirectory,
                     String ns, String name,
                     String value, boolean isXML,
                     DeadPropertyCallback callback) {
        if (mode == Mode.NONE) {
            callback.onError("Dead property storage disabled");
            return;
        }

        runOnStorage(callback, new Runnable() {
            @Override
            public void run() {
                setPropertyBlocking(resource, isDirectory, ns, name,
                        value, isXML, callback);
            }
        });
    }

    private void setPropertyBlocking(Path resource, Boolean isDirectory,
                                     String ns, String name,
                                     String value, boolean isXML,
                                     DeadPropertyCallback callback) {
        if (useXattr(resource)) {
            try {
                writeXattrProperty(resource, ns, name, value, isXML);
                callback.onProperties(null);
                return;
            } catch (IOException e) {
                if (mode == Mode.XATTR) {
                    callback.onError("xattr write failed: "
                            + e.getMessage());
                    return;
                }
                // The value does not fit an xattr and goes to the sidecar; an
                // older xattr for this property would shadow it when read.
                try {
                    removeXattrProperty(resource, ns, name);
                } catch (IOException ignored) {
                    // none was stored
                }
            }
        }

        if (useSidecar()) {
            boolean isDir = isDirectory != null
                    ? isDirectory.booleanValue()
                    : Files.isDirectory(resource);
            updateSidecar(resource, isDir, ns, name, value, isXML, false,
                    callback);
        } else {
            callback.onError("No storage backend available");
        }
    }

    /**
     * Removes a dead property from a resource.
     *
     * @param resource the resource path
     * @param ns the property namespace URI
     * @param name the property local name
     * @param callback receives the result
     */
    void removeProperty(Path resource, String ns, String name,
                        DeadPropertyCallback callback) {
        removeProperty(resource, null, ns, name, callback);
    }

    /**
     * Removes a dead property from a resource.
     *
     * @param resource the resource path
     * @param isDirectory whether {@code resource} is a directory, or
     *        {@code null} to detect
     * @param ns the property namespace URI
     * @param name the property local name
     * @param callback receives the result
     */
    void removeProperty(Path resource, Boolean isDirectory,
                        String ns, String name,
                        DeadPropertyCallback callback) {
        if (mode == Mode.NONE) {
            callback.onError("Dead property storage disabled");
            return;
        }

        runOnStorage(callback, new Runnable() {
            @Override
            public void run() {
                removePropertyBlocking(resource, isDirectory, ns, name,
                        callback);
            }
        });
    }

    private void removePropertyBlocking(Path resource, Boolean isDirectory,
                                        String ns, String name,
                                        DeadPropertyCallback callback) {
        boolean removed = false;
        if (useXattr(resource)) {
            try {
                removeXattrProperty(resource, ns, name);
                removed = true;
            } catch (IOException e) {
                // May be stored in sidecar
            }
        }

        if (useSidecar()) {
            boolean isDir = isDirectory != null
                    ? isDirectory.booleanValue()
                    : Files.isDirectory(resource);
            updateSidecar(resource, isDir, ns, name, null, false, true,
                    callback);
        } else if (removed) {
            callback.onProperties(null);
        } else {
            callback.onError("Property not found");
        }
    }

    /**
     * Copies dead properties from source to target.
     * For xattr mode, properties travel with {@code Files.copy(COPY_ATTRIBUTES)}.
     * For sidecar mode, copies the sidecar file.
     *
     * <p>Caller must already be on a StorageExecutor thread (e.g. COPY/MOVE
     * offload); this method performs blocking I/O inline.
     *
     * @param source the source resource
     * @param target the target resource
     */
    void copyProperties(Path source, Path target) {
        if (mode == Mode.NONE) {
            return;
        }
        if (useXattr(source)) {
            // Files.copy without COPY_ATTRIBUTES leaves extended attributes
            // behind, so carry the properties across explicitly.
            try {
                for (DeadProperty prop : loadXattrProperties(source).values()) {
                    writeXattrProperty(target, prop.getNamespaceURI(),
                            prop.getLocalName(), prop.getValue(), prop.isXML());
                }
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, MessageFormat.format(
                        L10N.getString("warn.xattr_copy_failed"), source), e);
            }
        }
        boolean srcIsDir = Files.isDirectory(source);
        Path srcSidecar = sidecarFor(source, srcIsDir);
        Path dstSidecar = sidecarFor(target, Files.isDirectory(target));
        try {
            if (sidecarExists(srcSidecar)) {
                Files.createDirectories(dstSidecar.getParent());
                Files.copy(srcSidecar, dstSidecar,
                        StandardCopyOption.REPLACE_EXISTING);
            } else {
                // A resource overwritten by COPY keeps none of the
                // properties it had before.
                Files.deleteIfExists(dstSidecar);
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, MessageFormat.format(
                    L10N.getString("warn.sidecar_copy_failed"), srcSidecar), e);
        }
    }

    /**
     * Deletes all dead properties for a resource.
     *
     * <p>Caller must already be on a StorageExecutor thread; this method
     * performs blocking I/O inline.
     *
     * @param resource the resource path
     */
    void deleteProperties(Path resource) {
        if (mode == Mode.NONE) {
            return;
        }
        Path sidecar = sidecarFor(resource, Files.isDirectory(resource));
        try {
            Files.deleteIfExists(sidecar);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, MessageFormat.format(
                    L10N.getString("warn.sidecar_delete_failed"), sidecar), e);
        }
    }

    /**
     * Returns the shared {@link StorageExecutor}, or null when no runtime is
     * set (a unit-test harness, say).
     */
    private StorageExecutor storageExecutor() {
        Gumdrop gumdrop = this.gumdrop;
        return (gumdrop != null) ? gumdrop.getStorageExecutor() : null;
    }

    /**
     * Runs blocking dead-property setup on the shared {@link StorageExecutor}
     * when available. With no executor (unit-test harness), runs inline.
     * Callbacks from the work itself are invoked on the storage thread (or
     * inline); AFC completions remain on the JDK async-file pool.
     */
    private void runOnStorage(final DeadPropertyCallback callback,
                              final Runnable work) {
        StorageExecutor exec = storageExecutor();
        if (exec == null) {
            try {
                work.run();
            } catch (Throwable t) {
                callback.onError(t.getMessage() != null
                        ? t.getMessage() : t.toString());
            }
            return;
        }
        // Dispatch callbacks on the storage thread: this class has no
        // HttpResponseState; FileHandler already tolerates dead-property
        // callbacks off the SelectorLoop (same as AFC completions today).
        Executor inline = new Executor() {
            @Override
            public void execute(Runnable command) {
                command.run();
            }
        };
        exec.submit(inline, new Callable<Void>() {
            @Override
            public Void call() {
                work.run();
                return null;
            }
        }, new StorageExecutor.Callback<Void>() {
            @Override
            public void completed(Void result) {
                // User callback already invoked inside work when sync.
            }

            @Override
            public void failed(Throwable error) {
                callback.onError(error.getMessage() != null
                        ? error.getMessage() : error.toString());
            }
        });
    }

    /**
     * Returns true if the path is a dead property sidecar file.
     *
     * @param path the path to check
     * @return true if this is a sidecar file
     */
    boolean isSidecar(Path path) {
        return sidecarRoot == null && isSidecarFile(path);
    }

    /**
     * Returns true if the filename is a sidecar of this store: never with a
     * sidecar root, where the content tree holds none.
     */
    boolean isSidecarEntry(String name) {
        return sidecarRoot == null && isSidecarName(name);
    }

    static boolean isSidecarFile(Path path) {
        if (path == null) {
            return false;
        }
        Path fileName = path.getFileName();
        return fileName != null
                && fileName.toString().startsWith(SIDECAR_PREFIX);
    }

    /**
     * Returns true if the filename is a dead property sidecar.
     *
     * @param name the filename
     * @return true if this is a sidecar filename
     */
    static boolean isSidecarName(String name) {
        return name != null && name.startsWith(SIDECAR_PREFIX);
    }

    // -- xattr backend --

    /**
     * Returns true if extended attributes are to be tried for a resource.
     * Whether they work is a property of where the resource lives, and a
     * tree can span file systems, so this is not decided once for the store:
     * the attempt itself fails on a file system without them, and callers
     * fall back to a sidecar (or report the failure in {@link Mode#XATTR}).
     */
    private boolean useXattr(Path resource) {
        return mode == Mode.AUTO || mode == Mode.XATTR;
    }

    private boolean useSidecar() {
        return mode == Mode.AUTO || mode == Mode.SIDECAR;
    }

    private Map<String, DeadProperty> loadXattrProperties(Path resource) {
        Map<String, DeadProperty> props =
                new HashMap<String, DeadProperty>();
        try {
            UserDefinedFileAttributeView view = Files.getFileAttributeView(
                    resource, UserDefinedFileAttributeView.class);
            if (view == null) {
                return props;
            }
            List<String> names = view.list();
            for (int i = 0; i < names.size(); i++) {
                String attrName = names.get(i);
                if (!attrName.startsWith(XATTR_PREFIX)) {
                    continue;
                }
                int size = view.size(attrName);
                ByteBuffer buf = ByteBuffer.allocate(size);
                view.read(attrName, buf);
                buf.flip();
                String raw = StandardCharsets.UTF_8.decode(buf).toString();

                DeadProperty prop = decodeXattrValue(raw);
                if (prop != null) {
                    props.put(prop.getKey(), prop);
                }
            }
        } catch (IOException e) {
            LOGGER.log(Level.FINE, MessageFormat.format(
                    L10N.getString("fine.xattr_read_failed"), resource), e);
        }
        return props;
    }

    private void writeXattrProperty(Path resource, String ns,
                                    String name, String value,
                                    boolean isXML)
            throws IOException {
        UserDefinedFileAttributeView view = Files.getFileAttributeView(
                resource, UserDefinedFileAttributeView.class);
        if (view == null) {
            throw new IOException("xattr not supported");
        }
        String attrName = xattrName(ns, name);
        String encoded = encodeXattrValue(ns, name, value, isXML);
        byte[] bytes = encoded.getBytes(StandardCharsets.UTF_8);
        view.write(attrName, ByteBuffer.wrap(bytes));
    }

    private void removeXattrProperty(Path resource, String ns,
                                     String name) throws IOException {
        UserDefinedFileAttributeView view = Files.getFileAttributeView(
                resource, UserDefinedFileAttributeView.class);
        if (view == null) {
            throw new IOException("xattr not supported");
        }
        String attrName = xattrName(ns, name);
        view.delete(attrName);
    }

    /**
     * xattr name: {@code user.webdav.{nsHash}.{localName}}.
     * Namespace URI is SHA-256-truncated to 8 hex chars.
     */
    private static String xattrName(String ns, String name) {
        return XATTR_PREFIX + namespaceHash(ns) + "." + name;
    }

    /**
     * xattr value format: {@code ns\nname\nisXML\nvalue}.
     * The full namespace URI is stored in the value so we can
     * reconstruct it during reads.
     */
    private static String encodeXattrValue(String ns, String name,
                                           String value, boolean isXML) {
        return ns + "\n" + name + "\n" + (isXML ? "1" : "0")
                + "\n" + (value != null ? value : "");
    }

    private static DeadProperty decodeXattrValue(String raw) {
        int nl1 = raw.indexOf('\n');
        if (nl1 < 0) {
            return null;
        }
        int nl2 = raw.indexOf('\n', nl1 + 1);
        if (nl2 < 0) {
            return null;
        }
        int nl3 = raw.indexOf('\n', nl2 + 1);
        if (nl3 < 0) {
            return null;
        }
        String ns = raw.substring(0, nl1);
        String name = raw.substring(nl1 + 1, nl2);
        boolean isXML = "1".equals(raw.substring(nl2 + 1, nl3));
        String value = raw.substring(nl3 + 1);
        return new DeadProperty(ns, name, value, isXML);
    }

    static String namespaceHash(String ns) {
        if (ns == null || ns.isEmpty()) {
            return "00000000";
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(
                    ns.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(8);
            for (int i = 0; i < 4; i++) {
                int b = hash[i] & 0xFF;
                sb.append(Character.forDigit(b >> 4, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return "00000000";
        }
    }

    // -- Sidecar backend --

    /**
     * Returns true if there is a sidecar file at {@code sidecar}. A symbolic
     * link does not count: it is never read, copied or written through, so
     * a link planted where a sidecar belongs cannot expose or overwrite the
     * file it points to.
     */
    private static boolean sidecarExists(Path sidecar) {
        return Files.isRegularFile(sidecar, LinkOption.NOFOLLOW_LINKS);
    }

    /**
     * Computes the sidecar path for a resource.
     * Files: {@code dir/.webdav_filename}.
     * Directories (own properties): {@code dir/.webdav_.} inside
     * the directory itself.
     *
     * <p>Stats the resource; prefer {@link #sidecarPath(Path, boolean)} when
     * the directory flag is already known.
     */
    static Path sidecarPath(Path resource) {
        return sidecarPath(resource, Files.isDirectory(resource));
    }

    /**
     * Computes where this store keeps the sidecar of a resource: next to it,
     * or under the sidecar root when there is one.
     */
    Path sidecarFor(Path resource, boolean isDirectory) {
        if (sidecarRoot == null) {
            return sidecarPath(resource, isDirectory);
        }
        Path key = keyFor(resource);
        return isDirectory ? key.resolve(SIDECAR_PREFIX + ".") : key;
    }

    /** The resource's place under the sidecar root: its path relative to the content root. */
    private Path keyFor(Path resource) {
        Path relative;
        if (resource.startsWith(contentRoot)) {
            relative = contentRoot.relativize(resource);
        } else if (resource.startsWith(configuredRoot)) {
            relative = configuredRoot.relativize(resource);
        } else {
            throw new IllegalArgumentException("not under the content root: " + resource);
        }
        Path key = sidecarRoot;
        for (Path name : relative) {
            if (!name.toString().isEmpty()) {
                key = key.resolve(name.toString());
            }
        }
        return key;
    }

    /**
     * Moves a resource's properties after the resource itself has moved
     * from {@code source} to {@code target}. With the sibling layout a
     * file's sidecar is renamed alongside it, and a collection's ride along
     * inside it. With a sidecar root the whole subtree of keys is renamed,
     * and whatever the target had is replaced.
     *
     * <p>Caller must already be on a StorageExecutor thread.
     */
    void moveProperties(Path source, Path target, boolean wasDirectory) {
        if (mode == Mode.NONE) {
            return;
        }
        try {
            if (sidecarRoot == null) {
                if (wasDirectory) {
                    return;
                }
                Path from = sidecarPath(source, false);
                if (Files.exists(from)) {
                    Files.move(from, sidecarPath(target, false),
                            StandardCopyOption.REPLACE_EXISTING);
                }
                return;
            }
            Path to = keyFor(target);
            deleteKeyTree(to);
            Path from = keyFor(source);
            if (Files.exists(from, LinkOption.NOFOLLOW_LINKS)) {
                Files.createDirectories(to.getParent());
                Files.move(from, to);
            }
        } catch (IOException e) {
            LOGGER.log(Level.FINE, L10N.getString("fine.sidecar_move_failed"), e);
        }
    }

    /**
     * Removes the properties of a deleted collection and of everything in
     * it. With the sibling layout they went with the directory, so this does
     * nothing; with a sidecar root it removes the subtree of keys.
     *
     * <p>Caller must already be on a StorageExecutor thread.
     */
    void deleteTree(Path resource) {
        if (sidecarRoot == null || mode == Mode.NONE) {
            return;
        }
        deleteKeyTree(keyFor(resource));
    }

    private static void deleteKeyTree(Path key) {
        try {
            if (!Files.exists(key, LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            Files.walkFileTree(key, new java.nio.file.SimpleFileVisitor<Path>() {
                @Override
                public java.nio.file.FileVisitResult visitFile(Path file,
                        java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override
                public java.nio.file.FileVisitResult postVisitDirectory(Path dir, IOException exc)
                        throws IOException {
                    Files.delete(dir);
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, MessageFormat.format(
                    L10N.getString("warn.sidecar_delete_failed"), key), e);
        }
    }

    /**
     * Computes the sibling sidecar path for a resource without re-statting.
     *
     * @param resource the resource path
     * @param isDirectory true if {@code resource} is a collection
     */
    static Path sidecarPath(Path resource, boolean isDirectory) {
        if (isDirectory) {
            return resource.resolve(SIDECAR_PREFIX + ".");
        }
        Path parent = resource.getParent();
        String fileName = resource.getFileName().toString();
        return parent.resolve(SIDECAR_PREFIX + fileName);
    }

    /**
     * Reads a sidecar file asynchronously using the Gonzalez parser.
     * Blocking setup ({@code Files.size}, {@code AFC.open}) must run on a
     * StorageExecutor thread; only the byte transfer is AFC.
     */
    private void readSidecar(Path sidecar,
                             final DeadPropertyCallback callback) {
        try {
            long size = Files.size(sidecar);
            if (size == 0) {
                callback.onProperties(
                        new HashMap<String, DeadProperty>());
                return;
            }
            if (size > MAX_SIDECAR_SIZE) {
                callback.onError("Sidecar file exceeds maximum size");
                return;
            }
            final AsyncFile channel = AsyncFile.open(storageExecutor(),
                    sidecar, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
            final ByteBuffer buf = ByteBuffer.allocate((int) size);
            channel.read(buf, 0, buf,
                    new CompletionHandler<Integer, ByteBuffer>() {
                        @Override
                        public void completed(Integer result,
                                              ByteBuffer attachment) {
                            if (result.intValue() > 0
                                    && attachment.hasRemaining()) {
                                // A read may return fewer bytes than asked
                                // for: carry on from where it stopped.
                                channel.read(attachment,
                                        attachment.position(), attachment,
                                        this);
                                return;
                            }
                            closeChannel(channel);
                            attachment.flip();
                            parseSidecarBuffer(attachment, callback);
                        }

                        @Override
                        public void failed(Throwable exc,
                                           ByteBuffer attachment) {
                            closeChannel(channel);
                            callback.onError(exc.getMessage());
                        }
                    });
        } catch (IOException e) {
            callback.onError(e.getMessage());
        }
    }

    /**
     * Parses a sidecar ByteBuffer using the Gonzalez push parser.
     */
    private void parseSidecarBuffer(ByteBuffer data,
                                    DeadPropertyCallback callback) {
        DeadPropertyParser parser = new DeadPropertyParser();
        try {
            parser.receive(data);
            parser.close();
            callback.onProperties(parser.getProperties());
        } catch (IOException e) {
            callback.onError("Parse error: " + e.getMessage());
        }
    }

    /**
     * Updates a sidecar file: loads existing properties, applies the
     * change, then writes the full sidecar back asynchronously.
     *
     * <p>This is a read-modify-write, so updates of one sidecar are run one
     * at a time, in the order they arrive; otherwise two requests could read
     * the same properties and the second write would discard the first
     * request's change. Updates of different resources do not wait for each
     * other.
     */
    private void updateSidecar(final Path resource, final boolean isDirectory,
                               final String ns,
                               final String name, final String value,
                               final boolean isXML,
                               final boolean remove,
                               final DeadPropertyCallback callback) {
        final Path sidecar = sidecarFor(resource, isDirectory);
        final AtomicBoolean finished = new AtomicBoolean();
        // The next queued update starts once this one has reported, and does
        // so even if the caller's callback throws.
        final DeadPropertyCallback done = new DeadPropertyCallback() {
            @Override
            public void onProperties(Map<String, DeadProperty> properties) {
                try {
                    callback.onProperties(properties);
                } finally {
                    finishUpdate(sidecar, finished);
                }
            }

            @Override
            public void onError(String error) {
                try {
                    callback.onError(error);
                } finally {
                    finishUpdate(sidecar, finished);
                }
            }
        };
        queueUpdate(sidecar, new Runnable() {
            @Override
            public void run() {
                try {
                    readModifyWrite(resource, isDirectory, sidecar, ns, name,
                            value, isXML, remove, done);
                } catch (RuntimeException e) {
                    finishUpdate(sidecar, finished);
                    throw e;
                }
            }
        });
    }

    /** Sidecars being updated, each with the updates waiting behind it. */
    private final Map<Path, ArrayDeque<Runnable>> sidecarUpdates =
            new HashMap<Path, ArrayDeque<Runnable>>();

    private void queueUpdate(Path sidecar, Runnable update) {
        synchronized (sidecarUpdates) {
            ArrayDeque<Runnable> waiting = sidecarUpdates.get(sidecar);
            if (waiting != null) {
                waiting.add(update);
                return;
            }
            sidecarUpdates.put(sidecar, new ArrayDeque<Runnable>());
        }
        update.run();
    }

    private void finishUpdate(Path sidecar, AtomicBoolean finished) {
        if (!finished.compareAndSet(false, true)) {
            return;
        }
        Runnable next;
        synchronized (sidecarUpdates) {
            next = sidecarUpdates.get(sidecar).poll();
            if (next == null) {
                sidecarUpdates.remove(sidecar);
            }
        }
        if (next != null) {
            next.run();
        }
    }

    private void readModifyWrite(final Path resource, final boolean isDirectory,
                                 Path sidecar, final String ns,
                                 final String name, final String value,
                                 final boolean isXML, final boolean remove,
                                 final DeadPropertyCallback callback) {
        if (!sidecarExists(sidecar)) {
            applyAndWrite(resource, isDirectory,
                    new HashMap<String, DeadProperty>(), ns, name, value,
                    isXML, remove, callback);
            return;
        }
        readSidecar(sidecar, new DeadPropertyCallback() {
            @Override
            public void onProperties(Map<String, DeadProperty> existing) {
                applyAndWrite(resource, isDirectory, existing, ns, name,
                        value, isXML, remove, callback);
            }

            @Override
            public void onError(String error) {
                // Never write over properties that could not be read: that
                // would silently destroy them. The sidecar is left as it is.
                callback.onError("Existing properties could not be read: "
                        + error);
            }
        });
    }

    /**
     * Test-only hook run after a sidecar has been read and before the
     * change is applied and written back, so a test can start a second
     * update at exactly the point where a race would lose one. Production
     * code leaves this {@code null}.
     */
    static volatile Runnable afterSidecarRead;

    private void applyAndWrite(Path resource, boolean isDirectory,
                               Map<String, DeadProperty> props,
                               String ns, String name, String value,
                               boolean isXML, boolean remove,
                               DeadPropertyCallback callback) {
        Runnable hook = afterSidecarRead;
        if (hook != null) {
            hook.run();
        }
        String key = DeadProperty.makeKey(ns, name);
        if (remove) {
            props.remove(key);
        } else {
            props.put(key, new DeadProperty(ns, name, value, isXML));
        }
        writeSidecar(resource, isDirectory, props, callback);
    }

    /**
     * Serializes properties to XML using Gonzalez XMLWriter and
     * writes asynchronously via {@link AsyncFile}.
     */
    private void writeSidecar(Path resource, boolean isDirectory,
                              Map<String, DeadProperty> props,
                              final DeadPropertyCallback callback) {
        Path sidecar = sidecarFor(resource, isDirectory);

        if (props.isEmpty()) {
            try {
                Files.deleteIfExists(sidecar);
            } catch (IOException e) {
                // ignore
            }
            callback.onProperties(null);
            return;
        }

        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            XMLWriter xml = new XMLWriter(baos);

            xml.writeStartElement(PROPS_ELEM_PROPERTIES);
            xml.writeDefaultNamespace(PROPS_NAMESPACE);

            for (Map.Entry<String, DeadProperty> entry
                    : props.entrySet()) {
                DeadProperty prop = entry.getValue();
                xml.writeStartElement(PROPS_ELEM_PROPERTY);
                xml.writeAttribute(PROPS_ATTR_NS,
                        prop.getNamespaceURI());
                xml.writeAttribute(PROPS_ATTR_NAME,
                        prop.getLocalName());
                if (prop.isXML()) {
                    xml.writeAttribute(PROPS_ATTR_XML, "true");
                }
                if (prop.getValue() != null) {
                    xml.writeCharacters(prop.getValue());
                }
                xml.writeEndElement();
            }

            xml.writeEndElement();
            xml.close();

            byte[] data = baos.toByteArray();
            if (data.length > MAX_SIDECAR_SIZE) {
                // The reader refuses anything larger, so never create it.
                callback.onError("Sidecar file would exceed maximum size");
                return;
            }
            final ByteBuffer buf = ByteBuffer.wrap(data);

            if (sidecarRoot != null) {
                Files.createDirectories(sidecar.getParent());
            }
            final AsyncFile channel = AsyncFile.open(storageExecutor(),
                    sidecar,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    LinkOption.NOFOLLOW_LINKS);

            channel.write(buf, 0, buf,
                    new CompletionHandler<Integer, ByteBuffer>() {
                        private long written;

                        @Override
                        public void completed(Integer result,
                                              ByteBuffer attachment) {
                            if (result.intValue() <= 0) {
                                failed(new IOException(
                                        "Sidecar write made no progress"),
                                        attachment);
                                return;
                            }
                            written += result.intValue();
                            if (attachment.hasRemaining()) {
                                // A write may take fewer bytes than offered:
                                // carry on from where it stopped.
                                channel.write(attachment, written, attachment,
                                        this);
                                return;
                            }
                            closeChannel(channel);
                            callback.onProperties(null);
                        }

                        @Override
                        public void failed(Throwable exc,
                                           ByteBuffer attachment) {
                            closeChannel(channel);
                            callback.onError(exc.getMessage());
                        }
                    });
        } catch (IOException e) {
            callback.onError("Sidecar write failed: " + e.getMessage());
        }
    }

    private static void closeChannel(AsyncFile channel) {
        try {
            channel.close();
        } catch (IOException e) {
            // ignore
        }
    }

}
