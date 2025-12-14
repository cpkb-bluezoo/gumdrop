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
 * Utility classes for the Gumdrop server.
 *
 * <p>This package provides common utility classes used throughout the
 * Gumdrop codebase, including I/O helpers, SSL utilities, and
 * general-purpose algorithms.
 *
 * <h2>Key Components</h2>
 *
 * <ul>
 *   <li>{@link org.bluezoo.gumdrop.util.CIDRNetwork} - Represents a CIDR
 *       network block for IP address matching</li>
 *   <li>{@link org.bluezoo.gumdrop.util.SNIKeyManager} - SSL KeyManager
 *       with Server Name Indication (SNI) support</li>
 *   <li>{@link org.bluezoo.gumdrop.util.EmptyX509TrustManager} - Trust
 *       manager that accepts all certificates (for testing)</li>
 *   <li>{@link org.bluezoo.gumdrop.util.JarInputStream} - Extended JAR
 *       input stream with additional utilities</li>
 *   <li>{@link org.bluezoo.gumdrop.util.IteratorEnumeration} - Adapter
 *       from Iterator to Enumeration</li>
 *   <li>{@link org.bluezoo.gumdrop.util.LaconicFormatter} - Compact log
 *       formatter for java.util.logging</li>
 *   <li>{@link org.bluezoo.gumdrop.util.MessageFormatter} - Message
 *       formatting utilities</li>
 * </ul>
 *
 * <h2>Design Principles</h2>
 *
 * <ul>
 *   <li>No external dependencies</li>
 *   <li>Thread-safe where applicable</li>
 *   <li>Memory efficient</li>
 *   <li>Java 8 compatible</li>
 * </ul>
 *
 * <h2>Internal Use</h2>
 *
 * <p>While these utilities are used internally by Gumdrop, they may also
 * be useful for applications built on top of the framework.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
package org.bluezoo.gumdrop.util;
