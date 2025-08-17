/*
 * ServletDef.java
 * Copyright (C) 2005, 2025 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 *
 * gumdrop is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * gumdrop is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with gumdrop.
 * If not, see <http://www.gnu.org/licenses/>.
 */

package org.bluezoo.gumdrop.servlet;

import org.bluezoo.gumdrop.util.IteratorEnumeration;

import java.text.MessageFormat;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.servlet.Servlet;
import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.SingleThreadModel;

/**
 * Servlet definition.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class ServletDef extends DescriptionGroup implements ServletConfig, Comparable {

    Context context;
    String name;
    String className;
    String jspFile;
    int loadOnStartup = -1;
    Map<String,InitParam> initParams = new LinkedHashMap<>();
    String runAs;
    SecurityRole securityRoleRef;
    MultipartConfigDef multipartConfig;

    /**
     * Compare for sorting according to the value of loadOnStartup
     */
    public int compareTo(Object other) {
        if (other instanceof ServletDef) {
            ServletDef servletDef = (ServletDef) other;
            return loadOnStartup - servletDef.loadOnStartup;
        }
        return 0;
    }

    /**
     * Create a new servlet instance.
     */
    Servlet newInstance() throws ServletException {
        if (jspFile != null) {
            Servlet servlet = context.parseJSPFile(jspFile);
            servlet.init(this);
            return servlet;
        }
        Thread thread = Thread.currentThread();
        ClassLoader loader = thread.getContextClassLoader();
        ClassLoader contextLoader = context.getContextClassLoader();
        try {
            thread.setContextClassLoader(contextLoader);
            Class t = contextLoader.loadClass(className);
            Servlet servlet = (Servlet) t.newInstance();
            servlet.init(this);
            return servlet;
        } catch (Exception e) {
            String message = Context.L10N.getString("err.init_servlet");
            message = MessageFormat.format(message, name);
            ServletException e2 = new ServletException(message);
            e2.initCause(e);
            throw e2;
        } finally {
            thread.setContextClassLoader(loader);
        }
    }

    public ServletContext getServletContext() {
        return context;
    }

    public String getServletName() {
        return name;
    }

    public String getInitParameter(String name) {
        InitParam initParam = initParams.get(name);
        return (initParam == null) ? null : initParam.value;
    }

    public Enumeration getInitParameterNames() {
        return new IteratorEnumeration(initParams.keySet().iterator());
    }

    public String toString() {
        StringBuffer buf = new StringBuffer(getClass().getName());
        buf.append("[name=");
        buf.append(name);
        buf.append(",className=");
        buf.append(className);
        buf.append("]");
        return buf.toString();
    }

}
