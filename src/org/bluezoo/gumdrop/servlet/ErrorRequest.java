/*
 * ErrorRequest.java
 * Copyright (C) 2005, 2025 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 *
 * This software is dual-licensed:
 *
 * 1. GNU General Public License v3 (or later) for open source use
 *    See LICENCE-GPL3 file for GPL terms and conditions.
 *
 * 2. Commercial License for proprietary use
 *    Contact Chris Burdess <dog@gnu.org> for commercial licensing terms.
 *    Mimecast Services Limited has been granted commercial usage rights under
 *    separate license agreement.
 *
 * gumdrop is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package org.bluezoo.gumdrop.servlet;

import java.util.HashMap;
import java.util.Map;

import javax.servlet.DispatcherType;
import javax.servlet.http.HttpServletRequestWrapper;

/**
 * Request wrapper for servicing error pages.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class ErrorRequest extends HttpServletRequestWrapper {

    final String servletPath;
    final String pathInfo;
    final Map<String,Object> attrs;

    ErrorRequest(
            Request request,
            String servletPath,
            String pathInfo,
            int sc,
            Throwable err,
            String servletName) {
        super(request);
        this.servletPath = servletPath;
        this.pathInfo = pathInfo;

        // Set error attributes
        attrs = new HashMap<>();
        String prefix = "javax.servlet.error.";
        attrs.put(prefix + "status_code", Integer.valueOf(sc));
        if (err != null) {
            attrs.put(prefix + "exception_type", err.getClass());
            attrs.put(prefix + "message", err.getMessage());
            attrs.put(prefix + "exception", err);
        }
        attrs.put(prefix + "request_uri", request.getRequestURI().toString());
        attrs.put(prefix + "servlet_name", servletName);
    }

    @Override public String getServletPath() {
        return servletPath;
    }

    @Override public String getPathInfo() {
        return pathInfo;
    }

    @Override public Object getAttribute(String name) {
        Object ret = super.getAttribute(name);
        if (ret == null) {
            ret = attrs.get(name);
        }
        return ret;
    }

    @Override public DispatcherType getDispatcherType() {
        return DispatcherType.ERROR;
    }

}
