/*
 * package-info.java
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

/**
 * Static file serving for the HTTP server.
 *
 * <p>This package provides an HTTP server for serving static files from
 * a filesystem directory, similar to traditional web servers like Apache
 * or Nginx.
 *
 * <h2>Key Components</h2>
 *
 * <ul>
 *   <li>{@link org.bluezoo.gumdrop.http.file.FileHTTPServer} - HTTP server
 *       configured to serve files from a document root</li>
 *   <li>{@link org.bluezoo.gumdrop.http.file.FileHTTPConnection} - Handles
 *       individual file serving connections</li>
 *   <li>{@link org.bluezoo.gumdrop.http.file.FileHandler} - Request handler
 *       implementing file serving logic</li>
 *   <li>{@link org.bluezoo.gumdrop.http.file.FileHandlerFactory} - Factory for
 *       creating file handler instances</li>
 * </ul>
 *
 * <h2>Features</h2>
 *
 * <ul>
 *   <li>Automatic MIME type detection</li>
 *   <li>Directory index files (index.html, index.htm)</li>
 *   <li>Conditional requests (If-Modified-Since, ETag)</li>
 *   <li>Range requests for partial content</li>
 *   <li>Directory listing (configurable)</li>
 *   <li>Virtual host support</li>
 *   <li>Custom error pages</li>
 * </ul>
 *
 * <h2>Configuration Example</h2>
 *
 * <pre>{@code
 * <file-http-server id="web" port="80">
 *   <property name="document-root">/var/www/html</property>
 *   <property name="directory-listing">false</property>
 *   <property name="welcome-files">index.html,index.htm</property>
 * </file-http-server>
 * }</pre>
 *
 * <h2>Security</h2>
 *
 * <p>The file server includes protection against path traversal attacks
 * and ensures that only files within the document root are accessible.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see org.bluezoo.gumdrop.http.file.FileHTTPServer
 * @see org.bluezoo.gumdrop.http.HTTPServer
 */
package org.bluezoo.gumdrop.http.file;
