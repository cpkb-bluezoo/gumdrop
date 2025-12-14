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
 * Servlet 4.0 container implementation.
 *
 * <p>This package provides a full Servlet 4.0 (JSR 369) container for
 * deploying and running Java web applications. It integrates with the
 * Gumdrop HTTP server to handle servlet requests.
 *
 * <h2>Key Components</h2>
 *
 * <ul>
 *   <li>{@link org.bluezoo.gumdrop.servlet.Container} - The main servlet
 *       container managing multiple web applications</li>
 *   <li>{@link org.bluezoo.gumdrop.servlet.Context} - Represents a single
 *       web application (ServletContext)</li>
 *   <li>{@link org.bluezoo.gumdrop.servlet.Request} - HTTP request
 *       implementation (HttpServletRequest)</li>
 *   <li>{@link org.bluezoo.gumdrop.servlet.Response} - HTTP response
 *       implementation (HttpServletResponse)</li>
 * </ul>
 *
 * <h2>Servlet 4.0 Features</h2>
 *
 * <ul>
 *   <li>HTTP/2 server push</li>
 *   <li>Servlet mapping API</li>
 *   <li>Default methods in listener interfaces</li>
 *   <li>GenericFilter and HttpFilter</li>
 *   <li>Trailer headers for chunked responses</li>
 * </ul>
 *
 * <h2>Configuration Example</h2>
 *
 * <pre>{@code
 * <container id="mainContainer">
 *   <property name="contexts">
 *     <list>
 *       <context path="" root="web"/>
 *       <context path="/myapp" root="/var/webapps/myapp"/>
 *     </list>
 *   </property>
 * </container>
 *
 * <server id="http" class="org.bluezoo.gumdrop.servlet.ServletServer">
 *   <property name="port">8080</property>
 *   <property name="container" ref="#mainContainer"/>
 * </server>
 * }</pre>
 *
 * <h2>Web Application Deployment</h2>
 *
 * <p>Web applications can be deployed as:
 * <ul>
 *   <li>Exploded directories with WEB-INF/web.xml</li>
 *   <li>WAR files (automatically extracted)</li>
 *   <li>Programmatic registration via ServletContainerInitializer</li>
 * </ul>
 *
 * <h2>Session Management</h2>
 *
 * <p>Sessions are managed by the {@link org.bluezoo.gumdrop.servlet.session}
 * subpackage, with optional clustering support for session replication
 * across multiple nodes.
 *
 * <h2>JNDI Resources</h2>
 *
 * <p>The container provides a JNDI namespace for resource injection,
 * implemented in the {@link org.bluezoo.gumdrop.servlet.jndi} subpackage.
 *
 * <h2>Subpackages</h2>
 *
 * <ul>
 *   <li>{@link org.bluezoo.gumdrop.servlet.session} - Session management
 *       and clustering</li>
 *   <li>{@link org.bluezoo.gumdrop.servlet.jndi} - JNDI resource binding
 *       and injection</li>
 *   <li>{@link org.bluezoo.gumdrop.servlet.jsp} - JSP compilation support</li>
 *   <li>{@link org.bluezoo.gumdrop.servlet.manager} - Management interface</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see org.bluezoo.gumdrop.servlet.Container
 * @see org.bluezoo.gumdrop.servlet.Context
 * @see javax.servlet.http.HttpServlet
 */
package org.bluezoo.gumdrop.servlet;


