/*
 * FilterRequest.java
 * Copyright (C) 2005 Chris Burdess
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

import java.util.Collections;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;

/**
 * A wrapper request used to wrap requests during forward and include.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class FilterRequest extends HttpServletRequestWrapper {

    final String uri;
    final String contextPath;
    final String servletPath;
    final String pathInfo;
    final String queryString;
    final Map<String,Object> attrs;
    Map parameters;
    boolean parametersParsed;

    FilterRequest(
            HttpServletRequest request,
            String uri,
            String contextPath,
            String servletPath,
            String pathInfo,
            String queryString,
            Map<String,Object> attrs) {
        super(request);
        this.uri = uri;
        this.contextPath = contextPath;
        this.servletPath = servletPath;
        this.pathInfo = pathInfo;
        this.queryString = queryString;
        this.attrs = attrs;
        parameters = new LinkedHashMap();
    }

    public String getRequestURI() {
        return uri;
    }

    public String getContextPath() {
        return contextPath;
    }

    public String getServletPath() {
        return servletPath;
    }

    public String getPathInfo() {
        return pathInfo;
    }

    public String getQueryString() {
        return queryString;
    }

    public Object getAttribute(String name) {
        Object ret = super.getAttribute(name);
        if (ret == null) {
            ret = attrs.get(name);
        }
        return ret;
    }

    public String getParameter(String name) {
        if (!parametersParsed) {
            parseParameters();
        }
        String[] values = (String[]) parameters.get(name);
        return (values == null || values.length == 0) ? null : values[0];
    }

    public Enumeration getParameterNames() {
        if (!parametersParsed) {
            parseParameters();
        }
        return new IteratorEnumeration(parameters.keySet());
    }

    public String[] getParameterValues(String name) {
        if (!parametersParsed) {
            parseParameters();
        }
        return (String[]) parameters.get(name);
    }

    public Map getParameterMap() {
        if (!parametersParsed) {
            parseParameters();
        }
        return parameters;
    }

    /**
     * Parse the request parameters (SRV.4.1, SRV.8.1.1).
     */
    private void parseParameters() {
        // Parameters specified in query-string
        if (queryString != null) {
            int start = 0;
            int end = queryString.indexOf('&', start);
            while (end > start) {
                Request.addParameter(parameters, queryString.substring(start, end));
                start = end + 1;
                end = queryString.indexOf('&', start);
            }
            Request.addParameter(parameters, queryString.substring(start));
        }
        // Parameters specified in original request
        Map originalParameters = super.getParameterMap();
        for (Iterator i = originalParameters.entrySet().iterator(); i.hasNext(); ) {
            Map.Entry entry = (Map.Entry) i.next();
            String key = (String) entry.getKey();
            String[] values = (String[]) entry.getValue();
            for (int j = 0; j < values.length; j++) {
                Request.addParameter(parameters, key, values[j]);
            }
        }
        parameters = Collections.unmodifiableMap(parameters);
        parametersParsed = true;
    }

}
