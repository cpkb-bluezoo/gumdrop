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
 * JSP 2.3 compilation and execution, with Expression Language (EL) 3.0
 * and tag library (JSTL) support.
 *
 * <p>{@link org.bluezoo.gumdrop.servlet.jsp.JSPParser} parses a
 * {@code .jsp} file into an element tree; {@link
 * org.bluezoo.gumdrop.servlet.jsp.ELEvaluator} evaluates EL expressions
 * within it; {@link org.bluezoo.gumdrop.servlet.jsp.InMemoryJavaCompiler}
 * compiles the generated servlet source at runtime. Pages are compiled
 * on first access and recompiled automatically when the source file
 * changes, with compiled classes cached for subsequent requests.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see org.bluezoo.gumdrop.servlet.jsp.JSPParser
 * @see org.bluezoo.gumdrop.servlet
 */
package org.bluezoo.gumdrop.servlet.jsp;
