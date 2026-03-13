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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.channels.CompletionHandler;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.UserDefinedFileAttributeView;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Dead property storage for WebDAV (RFC 4918 section 4).
 *
 * <p>Uses extended attributes (xattr) as the primary backend with
 * automatic fallback to XML sidecar files when xattrs are unavailable
 * or a value exceeds the xattr size limit.
 *
 * <p>Sidecar I/O is non-blocking via {@link AsynchronousFileChannel}
 * with {@link CompletionHandler}. Sidecar XML is parsed with the
 * Gonzalez push parser and serialized with {@link XMLWriter}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc4918#section-4">RFC 4918 section 4</a>
 */
final class DeadPropertyStore {

    private static final Logger LOGGER =
            Logger.getLogger(DeadPropertyStore.class.getName());

    /** xattr name prefix for dead properties. */
    private static final String XATTR_PREFIX = "user.webdav.";

    /** Sidecar file prefix. */
    static final String SIDECAR_PREFIX = ".webdav_";

    /** Sidecar XML namespace. */
    static final String PROPS_NAMESPACE = "urn:gumdrop:webdav-props";

    static final String PROPS_ELEM_PROPERTIES = "properties";
    static final String PROPS_ELEM_PROPERTY = "property";
    static final String PROPS_ATTR_NS = "ns";
    static final String PROPS_ATTR_NAME = "name";
    static final String PROPS_ATTR_XML = "xml";

    /** Storage mode. */
    enum Mode {
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
    private boolean xattrSupported;
    private boolean xattrChecked;

    DeadPropertyStore() {
    }

    /**
     * Sets the storage mode.
     *
     * @param mode the storage mode
     */
    void setMode(Mode mode) {
        this.mode = mode;
    }

    /**
     * Returns the storage mode.
     *
     * @return the storage mode
     */
    Mode getMode() {
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
        if (mode == Mode.NONE) {
            callback.onProperties(new HashMap<String, DeadProperty>());
            return;
        }

        Map<String, DeadProperty> xattrProps = new HashMap<String, DeadProperty>();
        if (useXattr(resource)) {
            xattrProps = loadXattrProperties(resource);
        }

        if (useSidecar()) {
            final Map<String, DeadProperty> merged = xattrProps;
            Path sidecar = sidecarPath(resource);
            if (Files.exists(sidecar)) {
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
                        LOGGER.warning("Sidecar read failed: " + error);
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
        if (mode == Mode.NONE) {
            callback.onError("Dead property storage disabled");
            return;
        }

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
                // Fall through to sidecar
            }
        }

        if (useSidecar()) {
            updateSidecar(resource, ns, name, value, isXML, false,
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
        if (mode == Mode.NONE) {
            callback.onError("Dead property storage disabled");
            return;
        }

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
            updateSidecar(resource, ns, name, null, false, true,
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
     * @param source the source resource
     * @param target the target resource
     */
    void copyProperties(Path source, Path target) {
        if (mode == Mode.NONE) {
            return;
        }
        Path srcSidecar = sidecarPath(source);
        if (Files.exists(srcSidecar)) {
            Path dstSidecar = sidecarPath(target);
            try {
                Files.copy(srcSidecar, dstSidecar,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING,
                        "Failed to copy sidecar: " + srcSidecar, e);
            }
        }
    }

    /**
     * Deletes all dead properties for a resource.
     *
     * @param resource the resource path
     */
    void deleteProperties(Path resource) {
        if (mode == Mode.NONE) {
            return;
        }
        Path sidecar = sidecarPath(resource);
        try {
            Files.deleteIfExists(sidecar);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING,
                    "Failed to delete sidecar: " + sidecar, e);
        }
    }

    /**
     * Returns true if the path is a dead property sidecar file.
     *
     * @param path the path to check
     * @return true if this is a sidecar file
     */
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

    private boolean useXattr(Path resource) {
        if (mode == Mode.SIDECAR || mode == Mode.NONE) {
            return false;
        }
        if (!xattrChecked) {
            xattrChecked = true;
            try {
                xattrSupported = Files.getFileStore(resource)
                        .supportsFileAttributeView("user");
            } catch (IOException e) {
                xattrSupported = false;
            }
        }
        return xattrSupported;
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
            LOGGER.log(Level.FINE, "xattr read failed for "
                    + resource, e);
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
     * Computes the sidecar path for a resource.
     * Files: {@code dir/.webdav_filename}.
     * Directories (own properties): {@code dir/.webdav_.} inside
     * the directory itself.
     */
    static Path sidecarPath(Path resource) {
        if (Files.isDirectory(resource)) {
            return resource.resolve(SIDECAR_PREFIX + ".");
        }
        Path parent = resource.getParent();
        String fileName = resource.getFileName().toString();
        return parent.resolve(SIDECAR_PREFIX + fileName);
    }

    /**
     * Reads a sidecar file asynchronously using the Gonzalez parser.
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
            final AsynchronousFileChannel channel =
                    AsynchronousFileChannel.open(sidecar,
                            StandardOpenOption.READ);
            final ByteBuffer buf = ByteBuffer.allocate((int) size);
            channel.read(buf, 0, buf,
                    new CompletionHandler<Integer, ByteBuffer>() {
                        @Override
                        public void completed(Integer result,
                                              ByteBuffer attachment) {
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
     */
    private void updateSidecar(final Path resource, final String ns,
                               final String name, final String value,
                               final boolean isXML,
                               final boolean remove,
                               final DeadPropertyCallback callback) {
        Path sidecar = sidecarPath(resource);
        if (Files.exists(sidecar)) {
            readSidecar(sidecar, new DeadPropertyCallback() {
                @Override
                public void onProperties(
                        Map<String, DeadProperty> existing) {
                    applyAndWrite(resource, existing, ns, name, value,
                            isXML, remove, callback);
                }

                @Override
                public void onError(String error) {
                    Map<String, DeadProperty> empty =
                            new HashMap<String, DeadProperty>();
                    applyAndWrite(resource, empty, ns, name, value,
                            isXML, remove, callback);
                }
            });
        } else {
            Map<String, DeadProperty> empty =
                    new HashMap<String, DeadProperty>();
            applyAndWrite(resource, empty, ns, name, value, isXML,
                    remove, callback);
        }
    }

    private void applyAndWrite(Path resource,
                               Map<String, DeadProperty> props,
                               String ns, String name, String value,
                               boolean isXML, boolean remove,
                               DeadPropertyCallback callback) {
        String key = DeadProperty.makeKey(ns, name);
        if (remove) {
            props.remove(key);
        } else {
            props.put(key, new DeadProperty(ns, name, value, isXML));
        }
        writeSidecar(resource, props, callback);
    }

    /**
     * Serializes properties to XML using Gonzalez XMLWriter and
     * writes asynchronously via AsynchronousFileChannel.
     */
    private void writeSidecar(Path resource,
                              Map<String, DeadProperty> props,
                              final DeadPropertyCallback callback) {
        Path sidecar = sidecarPath(resource);

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
            final ByteBuffer buf = ByteBuffer.wrap(data);

            final AsynchronousFileChannel channel =
                    AsynchronousFileChannel.open(sidecar,
                            StandardOpenOption.WRITE,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING);

            channel.write(buf, 0, buf,
                    new CompletionHandler<Integer, ByteBuffer>() {
                        @Override
                        public void completed(Integer result,
                                              ByteBuffer attachment) {
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

    private static void closeChannel(AsynchronousFileChannel channel) {
        try {
            channel.close();
        } catch (IOException e) {
            // ignore
        }
    }

}
