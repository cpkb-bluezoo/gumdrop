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
 * Web application management interface.
 *
 * <p>This package provides a web-based management interface for the servlet
 * container, allowing administrators to deploy, undeploy, start, stop, and
 * monitor web applications.
 *
 * <h2>Key Components</h2>
 *
 * <ul>
 *   <li>{@link org.bluezoo.gumdrop.servlet.manager.ManagerServlet} - The
 *       main management servlet handling admin requests</li>
 *   <li>{@link org.bluezoo.gumdrop.servlet.manager.ManagerContextService} -
 *       Interface for context management operations</li>
 *   <li>{@link org.bluezoo.gumdrop.servlet.manager.ManagerContainerService} -
 *       Interface for container management operations</li>
 * </ul>
 *
 * <h2>Management Operations</h2>
 *
 * <ul>
 *   <li><b>list</b> - List all deployed web applications</li>
 *   <li><b>deploy</b> - Deploy a new web application</li>
 *   <li><b>undeploy</b> - Remove a deployed application</li>
 *   <li><b>start</b> - Start a stopped application</li>
 *   <li><b>stop</b> - Stop a running application</li>
 *   <li><b>reload</b> - Reload an application (hot deployment)</li>
 *   <li><b>sessions</b> - View active session information</li>
 * </ul>
 *
 * <h2>Deployment</h2>
 *
 * <p>The manager application is deployed as a standard WAR file within
 * the container's context list:
 *
 * <pre>{@code
 * <container id="mainContainer">
 *   <property name="contexts">
 *     <list>
 *       <context path="/manager" root="dist/manager.war"/>
 *     </list>
 *   </property>
 * </container>
 * }</pre>
 *
 * <h2>Security</h2>
 *
 * <p>The manager webapp is protected by standard servlet security constraints.
 * Access requires authentication and membership in the {@code manager} role.
 * The webapp's web.xml configures HTTP Basic authentication by default.
 *
 * <p>To grant access, configure a realm with users in the {@code manager}
 * role and associate it with the container:
 *
 * <pre>{@code
 * <realm id="mainRealm" class="org.bluezoo.gumdrop.BasicRealm">
 *   <property name="href">realm.xml</property>
 * </realm>
 *
 * <container id="mainContainer">
 *   <property name="realms">
 *     <map>
 *       <entry key="Gumdrop Manager" ref="#mainRealm"/>
 *     </map>
 *   </property>
 *   ...
 * </container>
 * }</pre>
 *
 * <p>The realm XML file defines groups (with id for linking and name for
 * the role) and users (referencing groups by id via IDREFS):
 *
 * <pre>{@code
 * <realm>
 *   <!-- id is for IDREFS linking, name is the role for authorization -->
 *   <group id="managerGroup" name="manager"/>
 *   
 *   <!-- users reference groups by id (space-separated for multiple) -->
 *   <user name="admin" password="secret" groups="managerGroup"/>
 * </realm>
 * }</pre>
 *
 * <p><b>Important:</b> In production, the manager should only be accessible
 * over HTTPS to protect credentials in transit.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see org.bluezoo.gumdrop.servlet.manager.ManagerServlet
 * @see org.bluezoo.gumdrop.servlet.Container
 */
package org.bluezoo.gumdrop.servlet.manager;
