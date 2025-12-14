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
 * Low-level byte manipulation utilities.
 *
 * <p>This package provides fundamental byte array and buffer utilities
 * used throughout the Gumdrop codebase for efficient binary data handling.
 *
 * <h2>Key Components</h2>
 *
 * <ul>
 *   <li>{@link org.bluezoo.util.ByteArrays} - Static utility methods for
 *       byte array operations (searching, copying, comparison)</li>
 * </ul>
 *
 * <h2>ByteArrays</h2>
 *
 * <p>Provides efficient algorithms for:
 * <ul>
 *   <li>Pattern searching in byte arrays</li>
 *   <li>Array slicing and concatenation</li>
 *   <li>Comparison and equality checks</li>
 * </ul>
 *
 * <h2>Design</h2>
 *
 * <p>These utilities are designed for:
 * <ul>
 *   <li>Zero-copy operations where possible</li>
 *   <li>Minimal memory allocation</li>
 *   <li>Performance in hot paths</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
package org.bluezoo.util;


