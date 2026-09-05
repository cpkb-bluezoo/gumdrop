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
 * Web-based management interface for the servlet container: list,
 * deploy, undeploy, start, stop, and reload web applications, and view
 * active session information.
 *
 * <p>{@link org.bluezoo.gumdrop.servlet.manager.ManagerServlet} handles
 * the admin requests, delegating to {@link
 * org.bluezoo.gumdrop.servlet.manager.ManagerContainerService} and
 * {@link org.bluezoo.gumdrop.servlet.manager.ManagerContextService} for
 * the actual container/context operations. The manager application
 * itself deploys as an ordinary WAR file and is protected by standard
 * servlet security constraints, requiring authentication and membership
 * in the {@code manager} role -- it should only be exposed over HTTPS,
 * since it grants deployment control over every application in the
 * container.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see org.bluezoo.gumdrop.servlet.manager.ManagerServlet
 * @see org.bluezoo.gumdrop.servlet.Container
 */
package org.bluezoo.gumdrop.servlet.manager;
