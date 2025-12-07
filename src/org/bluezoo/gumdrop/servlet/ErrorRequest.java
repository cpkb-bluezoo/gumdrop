/*
 * ErrorRequest.java
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
