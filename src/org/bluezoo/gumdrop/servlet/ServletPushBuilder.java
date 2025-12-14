/*
 * ServletPushBuilder.java
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

package org.bluezoo.gumdrop.servlet;

import org.bluezoo.gumdrop.http.Headers;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import javax.servlet.http.PushBuilder;

import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;

/**
 * Implementation of Servlet 4.0 PushBuilder for HTTP/2 server push.
 *
 * <p>This allows servlets to push resources to clients before they are
 * requested, improving page load times for HTTP/2 connections.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class ServletPushBuilder implements PushBuilder {

    // Headers that must not be included in push requests (per Servlet 4.0 spec)
    private static final Set<String> EXCLUDED_HEADERS = new HashSet<String>();
    static {
        // Conditional headers
        EXCLUDED_HEADERS.add("if-match");
        EXCLUDED_HEADERS.add("if-none-match");
        EXCLUDED_HEADERS.add("if-modified-since");
        EXCLUDED_HEADERS.add("if-unmodified-since");
        EXCLUDED_HEADERS.add("if-range");
        // Range header
        EXCLUDED_HEADERS.add("range");
        // Expect header
        EXCLUDED_HEADERS.add("expect");
        // Authorization handled separately
        EXCLUDED_HEADERS.add("authorization");
        // Referer set automatically
        EXCLUDED_HEADERS.add("referer");
    }

    private final ServletHandler handler;
    private final Request request;
    private final Headers headers;

    private String method = "GET";
    private String path;
    private String queryString;
    private String sessionId;

    /**
     * Creates a new PushBuilder for the given request.
     *
     * @param handler the servlet handler
     * @param request the originating request
     */
    ServletPushBuilder(ServletHandler handler, Request request) {
        this.handler = handler;
        this.request = request;
        this.headers = new Headers();

        // Copy headers from original request (excluding certain headers per spec)
        Enumeration<String> headerNames = request.getHeaderNames();
        while (headerNames.hasMoreElements()) {
            String name = headerNames.nextElement();
            String lowerName = name.toLowerCase();
            if (!EXCLUDED_HEADERS.contains(lowerName)) {
                Enumeration<String> values = request.getHeaders(name);
                while (values.hasMoreElements()) {
                    headers.add(name, values.nextElement());
                }
            }
        }

        // Set Referer to the original request URL
        StringBuffer requestUrl = request.getRequestURL();
        String qs = request.getQueryString();
        if (qs != null) {
            requestUrl.append('?');
            requestUrl.append(qs);
        }
        headers.set("Referer", requestUrl.toString());

        // Copy session ID if using URL rewriting
        HttpSession session = request.getSession(false);
        if (session != null && !request.isRequestedSessionIdFromCookie()) {
            this.sessionId = session.getId();
        }
    }

    @Override
    public PushBuilder method(String method) {
        if (method == null || method.isEmpty()) {
            throw new NullPointerException("method must not be null or empty");
        }
        // Only safe methods allowed for push (per HTTP/2 spec)
        String upper = method.toUpperCase();
        if (!"GET".equals(upper) && !"HEAD".equals(upper)) {
            throw new IllegalArgumentException("Only GET and HEAD methods are allowed for push");
        }
        this.method = upper;
        return this;
    }

    @Override
    public PushBuilder queryString(String queryString) {
        this.queryString = queryString;
        return this;
    }

    @Override
    public PushBuilder sessionId(String sessionId) {
        this.sessionId = sessionId;
        return this;
    }

    @Override
    public PushBuilder setHeader(String name, String value) {
        if (name == null) {
            throw new NullPointerException("header name must not be null");
        }
        headers.set(name, value);
        return this;
    }

    @Override
    public PushBuilder addHeader(String name, String value) {
        if (name == null) {
            throw new NullPointerException("header name must not be null");
        }
        headers.add(name, value);
        return this;
    }

    @Override
    public PushBuilder removeHeader(String name) {
        if (name != null) {
            headers.remove(name);
        }
        return this;
    }

    @Override
    public PushBuilder path(String path) {
        this.path = path;
        return this;
    }

    @Override
    public void push() {
        if (path == null) {
            throw new IllegalStateException("Path must be set before calling push()");
        }

        // Build the full URI
        String uri = path;
        
        // Add query string if present
        String qs = queryString;
        if (sessionId != null && !request.isRequestedSessionIdFromCookie()) {
            // Append session ID to query string for URL rewriting
            String sessionParam = "jsessionid=" + sessionId;
            if (qs == null || qs.isEmpty()) {
                qs = sessionParam;
            } else {
                qs = qs + "&" + sessionParam;
            }
        }
        if (qs != null && !qs.isEmpty()) {
            uri = uri + "?" + qs;
        }

        // Execute the push
        handler.executePush(method, uri, headers);

        // Clear path for reuse (per Servlet 4.0 spec)
        this.path = null;
    }

    @Override
    public String getMethod() {
        return method;
    }

    @Override
    public String getQueryString() {
        return queryString;
    }

    @Override
    public String getSessionId() {
        return sessionId;
    }

    @Override
    public Set<String> getHeaderNames() {
        Set<String> names = new HashSet<String>();
        for (int i = 0; i < headers.size(); i++) {
            names.add(headers.get(i).getName());
        }
        return names;
    }

    @Override
    public String getHeader(String name) {
        return headers.getValue(name);
    }

    @Override
    public String getPath() {
        return path;
    }

}

