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
 * JNDI resource management for the servlet container's {@code
 * java:comp/env} namespace.
 *
 * <p>{@link org.bluezoo.gumdrop.servlet.jndi.ServletInitialContext}
 * implements {@code javax.naming.Context} for a web application's
 * environment; {@link org.bluezoo.gumdrop.servlet.jndi.Resource} is the
 * base class for resource definitions (DataSource, MailSession, JMS and
 * JCA connection factories); {@link
 * org.bluezoo.gumdrop.servlet.jndi.Injectable} marks resources that can
 * be looked up this way; {@link
 * org.bluezoo.gumdrop.servlet.jndi.ResourceInjector} processes
 * {@code @Resource} annotations on servlet fields and methods.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
package org.bluezoo.gumdrop.servlet.jndi;
