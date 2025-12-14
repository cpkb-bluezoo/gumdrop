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
 * JSP (JavaServer Pages) compilation and execution support.
 *
 * <p>This package provides JSP support for the servlet container, enabling
 * dynamic page generation using JavaServer Pages technology.
 *
 * <h2>Key Components</h2>
 *
 * <ul>
 *   <li>{@link org.bluezoo.gumdrop.servlet.jsp.JSPParser} - Parses JSP
 *       files into element trees</li>
 *   <li>{@link org.bluezoo.gumdrop.servlet.jsp.ELEvaluator} - Evaluates
 *       Expression Language expressions</li>
 *   <li>{@link org.bluezoo.gumdrop.servlet.jsp.InMemoryJavaCompiler} - Compiles
 *       generated Java code at runtime</li>
 * </ul>
 *
 * <h2>JSP Features</h2>
 *
 * <ul>
 *   <li>JSP 2.3 specification support</li>
 *   <li>Expression Language (EL) 3.0</li>
 *   <li>Tag libraries (JSTL)</li>
 *   <li>JSP fragments and includes</li>
 *   <li>Automatic recompilation on change</li>
 *   <li>Precompilation support</li>
 * </ul>
 *
 * <h2>Configuration</h2>
 *
 * <p>JSP processing is automatically enabled for files with .jsp extension
 * in web applications. Configuration options can be set in web.xml:
 *
 * <pre>{@code
 * <servlet>
 *   <servlet-name>jsp</servlet-name>
 *   <servlet-class>org.bluezoo.gumdrop.servlet.jsp.JspServlet</servlet-class>
 *   <init-param>
 *     <param-name>development</param-name>
 *     <param-value>true</param-value>
 *   </init-param>
 * </servlet>
 * }</pre>
 *
 * <h2>Compilation</h2>
 *
 * <p>JSP files are compiled to Java servlet classes on first access or
 * when modified (in development mode). Compiled classes are cached for
 * subsequent requests.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see org.bluezoo.gumdrop.servlet.jsp.JSPParser
 * @see org.bluezoo.gumdrop.servlet
 */
package org.bluezoo.gumdrop.servlet.jsp;


