/*
 * WebDAVRequestHandler.java
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

package org.bluezoo.gumdrop.webdav.server;

import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.http.Headers;
import org.bluezoo.gumdrop.http.server.HttpRequestHandler;
import org.bluezoo.gumdrop.http.server.HttpResponseState;
import org.bluezoo.gumdrop.http.server.HttpStreamHandler;
import org.bluezoo.gumdrop.webdav.DeadPropertyStore;
import org.bluezoo.gumdrop.webdav.FileRequestRouter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.MessageFormat;
import java.util.ResourceBundle;
import java.util.logging.Logger;

/**
 * Filesystem HTTP handler with optional RFC 4918 WebDAV authoring.
 *
 * <p>Install on {@link org.bluezoo.gumdrop.http.HttpServer} via
 * {@link org.bluezoo.gumdrop.http.HttpServer.Composer#streamHandler(HttpStreamHandler)}:
 *
 * <pre>{@code
 * HttpServer server = HttpServer.compose()
 *         .secureEndpoint(443, TlsConfig.pem(Path.of("cert.pem"), Path.of("key.pem")))
 *         .streamHandler(WebDAVRequestHandler.builder()
 *                 .rootPath(Path.of("/var/www/html"))
 *                 .webdavEnabled(true)
 *                 .build())
 *         .server();
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see web/configuration.html
 */
public final class WebDAVRequestHandler implements HttpStreamHandler {

    private static final Logger LOGGER =
            Logger.getLogger(WebDAVRequestHandler.class.getName());
    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.webdav.L10N");

    private final FileRequestRouter fileRouter;

    private WebDAVRequestHandler(FileRequestRouter fileRouter) {
        this.fileRouter = fileRouter;
    }

    @Override
    public HttpRequestHandler openStream(HttpResponseState stream) {
        return fileRouter.openStream(stream);
    }

    /**
     * Creates a builder for a WebDAV / static file router.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Validates that {@code path} is safe for use as a file server root.
     */
    public static void validateRootPath(Path path, boolean allowWrite) {
        if (path == null) {
            throw new IllegalArgumentException(
                    "Root path cannot be null");
        }

        try {
            Path realPath = path.toRealPath();

            if (!Files.isDirectory(realPath)) {
                throw new IllegalArgumentException(
                        "Root path must be a directory: " + realPath);
            }

            if (!Files.isReadable(realPath)) {
                throw new IllegalArgumentException(
                        "Root path must be readable: " + realPath);
            }

            if (allowWrite && !Files.isWritable(realPath)) {
                LOGGER.warning(MessageFormat.format(
                        L10N.getString("warn.root_path_not_writable"), realPath));
            }

            LOGGER.info(MessageFormat.format(
                    L10N.getString("info.file_server_root_validated"),
                    realPath, allowWrite));

        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "Cannot access root path: " + path
                            + " - " + e.getMessage(), e);
        }
    }

    private static DeadPropertyStore createDeadPropertyStore(String mode) {
        DeadPropertyStore store = new DeadPropertyStore();
        String storage = (mode != null && !mode.trim().isEmpty())
                ? mode.trim().toLowerCase()
                : "auto";
        if ("xattr".equals(storage)) {
            store.setMode(DeadPropertyStore.Mode.XATTR);
        } else if ("sidecar".equals(storage)) {
            store.setMode(DeadPropertyStore.Mode.SIDECAR);
        } else if ("none".equals(storage)) {
            store.setMode(DeadPropertyStore.Mode.NONE);
        } else {
            store.setMode(DeadPropertyStore.Mode.AUTO);
        }
        LOGGER.info(MessageFormat.format(
                L10N.getString("info.dead_property_storage"), store.getMode()));
        return store;
    }

    /**
     * Builder for {@link WebDAVRequestHandler}.
     */
    public static final class Builder {

        private Path rootPath = Paths.get(".");
        private boolean allowWrite = false;
        private boolean webdavEnabled = false;
        private String welcomeFile = "index.html";
        private String deadPropertyStorage = "auto";
        private Path lockRoot;
        private Path sidecarRoot;
        private Realm realm;

        private Builder() {
        }

        public Builder rootPath(Path rootPath) {
            if (rootPath == null) {
                throw new NullPointerException("rootPath");
            }
            this.rootPath = rootPath;
            return this;
        }

        public Builder allowWrite(boolean allowWrite) {
            this.allowWrite = allowWrite;
            return this;
        }

        public Builder webdavEnabled(boolean webdavEnabled) {
            this.webdavEnabled = webdavEnabled;
            return this;
        }

        public Builder welcomeFile(String welcomeFile) {
            this.welcomeFile = (welcomeFile != null
                    && !welcomeFile.trim().isEmpty())
                    ? welcomeFile.trim()
                    : "index.html";
            return this;
        }

        /**
         * Dead property storage mode: {@code auto}, {@code xattr},
         * {@code sidecar}, or {@code none}.
         */
        public Builder deadPropertyStorage(String mode) {
            this.deadPropertyStorage = mode;
            return this;
        }

        /**
         * Keeps WebDAV locks as files under this directory instead of in
         * memory, so that servers sharing the content tree share their
         * locks. Unset by default: one server on a private tree needs no
         * lock volume.
         *
         * <p>Each lock is a record at the resource's path relative to
         * {@link #rootPath}, and a lock is granted by creating its record
         * and finding no conflicting one. That needs a file system where
         * creating a file exclusively is atomic and one server's record is
         * visible to the others at once: a local disk or a typical
         * ReadWriteOnce volume. On NFS, where attribute caching can hide a
         * record, two servers may both grant an exclusive lock; keep WebDAV
         * at one replica there.
         *
         * @param lockRoot the directory for lock records, or null for memory
         */
        public Builder lockRoot(Path lockRoot) {
            this.lockRoot = lockRoot;
            return this;
        }

        /**
         * Keeps dead properties that would be written as sidecar files
         * (mode {@code sidecar}, or {@code auto} where extended attributes
         * are unavailable) under this directory rather than beside the
         * resources, at each resource's path relative to {@link #rootPath}.
         * Nothing is then written into the content tree, which may be
         * read-only, and a content file named like a sidecar is an ordinary
         * file. Unset by default: sidecars are {@code .webdav_*} siblings.
         *
         * <p>This is not the lock root. Share it only between servers that
         * share the content tree and must see one set of properties.
         *
         * @param sidecarRoot the directory for sidecars, or null for siblings
         */
        public Builder sidecarRoot(Path sidecarRoot) {
            this.sidecarRoot = sidecarRoot;
            return this;
        }

        /**
         * Configures a {@link Realm} to check RFC 3744 privileges
         * against, enabling ACL support (the {@code acl-*}/{@code
         * *-privilege-set} DAV: properties and the {@code ACL} method).
         * Privileges are checked as roles prefixed {@code "webdav:"}
         * (e.g. {@code webdav:read}, {@code webdav:write}) via
         * {@link Realm#isUserInRole}; the
         * authenticated username itself comes from
         * {@link org.bluezoo.gumdrop.http.server.HttpResponseState#getPrincipal()},
         * which is populated by whatever HTTP authentication (Basic,
         * Digest, Bearer, mTLS) is configured on the listener this
         * handler is deployed behind -- WebDAV ACL support does not
         * configure or perform authentication itself.
         *
         * <p>Not called (or called with null): ACL support is disabled
         * entirely, regardless of {@link #webdavEnabled}.
         *
         * @param realm the realm to check privileges against
         */
        public Builder realm(Realm realm) {
            this.realm = realm;
            return this;
        }

        public WebDAVRequestHandler build() {
            validateRootPath(rootPath, allowWrite);
            DeadPropertyStore store = null;
            if (webdavEnabled) {
                store = createDeadPropertyStore(deadPropertyStorage);
                store.setSidecarRoot(rootPath, sidecarRoot);
            }
            FileRequestRouter fileRouter = new FileRequestRouter(
                    rootPath, allowWrite, welcomeFile, webdavEnabled, store, realm, lockRoot);
            return new WebDAVRequestHandler(fileRouter);
        }
    }

}
