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
 * Servlet 4.0 (JSR 369) container, deploying and running Java web
 * applications on top of a gumdrop HTTP service.
 *
 * <p>{@link org.bluezoo.gumdrop.servlet.Container} manages the deployed
 * web applications; each is a {@link org.bluezoo.gumdrop.servlet.Context}
 * (the {@code ServletContext} implementation). {@link
 * org.bluezoo.gumdrop.servlet.Request}/{@link
 * org.bluezoo.gumdrop.servlet.Response} implement {@code
 * HttpServletRequest}/{@code HttpServletResponse} over gumdrop's HTTP
 * layer, so servlets get HTTP/2 server push and 1xx informational
 * responses without any servlet-side awareness of protocol version.
 * Applications deploy as exploded {@code WEB-INF} directories, WAR
 * files, or programmatic {@code ServletContainerInitializer} registration.
 *
 * <h2>Subpackages</h2>
 *
 * <ul>
 *   <li>{@link org.bluezoo.gumdrop.servlet.session} - session management and cluster replication</li>
 *   <li>{@link org.bluezoo.gumdrop.servlet.jndi} - JNDI resource binding and injection</li>
 *   <li>{@link org.bluezoo.gumdrop.servlet.jsp} - JSP compilation and execution</li>
 *   <li>{@link org.bluezoo.gumdrop.servlet.manager} - web-based deployment management</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see org.bluezoo.gumdrop.servlet.Container
 * @see org.bluezoo.gumdrop.servlet.Context
 * @see javax.servlet.http.HttpServlet
 */
package org.bluezoo.gumdrop.servlet;
