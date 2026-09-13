/*
 * WebdavServer.java
 * Copyright (C) 2005, 2013, 2025, 2026 Chris Burdess
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

import org.bluezoo.gumdrop.http.HttpServer;
import org.bluezoo.gumdrop.http.server.HttpRequestRouter;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * WebDAV protocol server — filesystem HTTP with optional RFC 4918 authoring.
 *
 * <p>When WebDAV is enabled, this server supports PROPFIND, PROPPATCH,
 * MKCOL, COPY, MOVE, LOCK, and UNLOCK methods in addition to the
 * standard HTTP methods.
 *
 * <p>New applications should prefer {@link org.bluezoo.gumdrop.http.HttpServer#builder()}
 * with {@link WebDAVRequestHandler} rather than this type. {@code WebdavServer}
 * remains for XML configuration and legacy wiring.
 *
 * <p>Transport endpoints (ports, TLS configuration) are defined by
 * adding listeners via {@link #addListener}. The service builds a
 * {@link WebDAVRequestHandler} during {@link #initService()} and wires
 * it into each listener.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see HttpServer
 * @see WebDAVRequestHandler
 * @see docs/COMPOSITION.md
 * @see docs/NAMING-TAXONOMY.md
 * @see <a href="https://www.rfc-editor.org/rfc/rfc4918">RFC 4918</a>
 */
public class WebdavServer extends HttpServer {

    private Path rootPath = Paths.get(".");
    private boolean allowWrite = false;
    private boolean webdavEnabled = false;
    private String welcomeFile = "index.html";
    private String deadPropertyStorage = "auto";

    private WebDAVRequestHandler requestHandler;

    // ── Configuration ──

    public Path getRootPath() {
        return rootPath;
    }

    public void setRootPath(Path rootPath) {
        WebDAVRequestHandler.validateRootPath(rootPath, allowWrite);
        this.rootPath = rootPath;
    }

    public void setRootPath(String rootPath) {
        setRootPath(Paths.get(rootPath));
    }

    public boolean isAllowWrite() {
        return allowWrite;
    }

    public void setAllowWrite(boolean allowWrite) {
        this.allowWrite = allowWrite;
    }

    /**
     * Returns whether WebDAV (RFC 4918) support is enabled.
     *
     * @return true if WebDAV is enabled
     */
    public boolean isWebdavEnabled() {
        return webdavEnabled;
    }

    /**
     * Enables or disables WebDAV (RFC 4918) support.
     *
     * <p>When enabled, the service supports PROPFIND, PROPPATCH, MKCOL,
     * COPY, MOVE, LOCK, and UNLOCK methods in addition to the standard
     * HTTP methods.
     *
     * @param webdavEnabled true to enable WebDAV
     */
    public void setWebdavEnabled(boolean webdavEnabled) {
        this.webdavEnabled = webdavEnabled;
    }

    public String getWelcomeFile() {
        return welcomeFile;
    }

    public void setWelcomeFile(String welcomeFile) {
        this.welcomeFile = (welcomeFile != null
                && !welcomeFile.trim().isEmpty())
                ? welcomeFile.trim()
                : "index.html";
    }

    /**
     * Returns the dead property storage mode.
     *
     * @return one of "auto", "xattr", "sidecar", or "none"
     */
    public String getDeadPropertyStorage() {
        return deadPropertyStorage;
    }

    /**
     * Sets the dead property storage mode.
     *
     * <p>Values:
     * <ul>
     *   <li>{@code "auto"} -- xattr primary with sidecar fallback
     *       (default)</li>
     *   <li>{@code "xattr"} -- extended attributes only</li>
     *   <li>{@code "sidecar"} -- sidecar files only</li>
     *   <li>{@code "none"} -- dead properties disabled</li>
     * </ul>
     *
     * @param mode the storage mode
     */
    public void setDeadPropertyStorage(String mode) {
        this.deadPropertyStorage = (mode != null
                && !mode.trim().isEmpty())
                ? mode.trim().toLowerCase()
                : "auto";
    }

    // ── HttpServer hooks ──

    /**
     * Builds the request handler on startup.
     */
    @Override
    protected void initService() {
        requestHandler = WebDAVRequestHandler.builder()
                .rootPath(rootPath)
                .allowWrite(allowWrite)
                .welcomeFile(welcomeFile)
                .webdavEnabled(webdavEnabled)
                .deadPropertyStorage(deadPropertyStorage)
                .build();
    }

    @Override
    protected HttpRequestRouter getRequestRouter() {
        return requestHandler;
    }

}
