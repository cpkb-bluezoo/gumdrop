/*
 * package-info.java
 * Copyright (C) 2025 Chris Burdess
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
 * JNDI resource management for the Gumdrop servlet container.
 *
 * <p>This package provides the JNDI (Java Naming and Directory Interface)
 * implementation for web applications, including:
 *
 * <ul>
 * <li><strong>Resource definitions</strong> - DataSource, MailSession, JMS,
 *     JCA connection factories and administered objects</li>
 * <li><strong>Resource references</strong> - Injectable references to
 *     environment entries, EJBs, persistence units, and web services</li>
 * <li><strong>JNDI context</strong> - Implementation of javax.naming.Context
 *     for the java:comp/env namespace</li>
 * <li><strong>Resource injection</strong> - Support for @Resource annotation
 *     on servlet fields and methods</li>
 * </ul>
 *
 * <h2>Key Classes</h2>
 * <ul>
 * <li>{@link org.bluezoo.gumdrop.servlet.jndi.Resource} - Base class for
 *     JNDI-bound resource definitions</li>
 * <li>{@link org.bluezoo.gumdrop.servlet.jndi.Injectable} - Interface for
 *     resources that can be injected into servlets</li>
 * <li>{@link org.bluezoo.gumdrop.servlet.jndi.ServletInitialContext} -
 *     JNDI context implementation for web applications</li>
 * <li>{@link org.bluezoo.gumdrop.servlet.jndi.ResourceInjector} -
 *     Processes @Resource annotations for dependency injection</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
package org.bluezoo.gumdrop.servlet.jndi;

