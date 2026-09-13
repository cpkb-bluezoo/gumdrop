/*
 * CookieConfig.java
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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.SessionCookieConfig;
import jakarta.servlet.http.Cookie;

/**
 * Definition of a cookie-config.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class CookieConfig implements SessionCookieConfig {

    enum SameSite {
        Strict,
        Lax,
        None;
    }

    String name = "JSESSIONID";
    String domain;
    String path;
    String comment;
    boolean httpOnly = false;
    boolean secure = false;
    int maxAge = -1;
    SameSite sameSite = SameSite.Lax;
    boolean partitioned;
    final Map<String,String> attributes = new LinkedHashMap<>();

    /** Parsed {@code <attribute>} element under {@code cookie-config}. */
    static final class AttributePair {
        String name;
        String value = "";
    }

    /**
     * Builds a session {@code Set-Cookie} from this descriptor and a session id.
     */
    Cookie createSessionCookie(String sessionId, String contextPath) {
        Cookie cookie = new Cookie(name, sessionId);
        if (domain != null) {
            cookie.setDomain(domain);
        }
        String cookiePath = path;
        if (cookiePath == null && contextPath != null && !contextPath.isEmpty()) {
            cookiePath = contextPath;
        }
        if (cookiePath != null) {
            cookie.setPath(cookiePath);
        }
        if (maxAge != -1) {
            cookie.setMaxAge(maxAge);
        }
        cookie.setHttpOnly(httpOnly);
        cookie.setSecure(secure);
        if (comment != null) {
            cookie.setComment(comment);
        }
        if (sameSite != null) {
            cookie.setAttribute("SameSite", sameSite.name());
        }
        if (partitioned) {
            cookie.setAttribute("Partitioned", "");
        }
        for (Map.Entry<String,String> entry : attributes.entrySet()) {
            cookie.setAttribute(entry.getKey(), entry.getValue());
        }
        return cookie;
    }

    // -- SessionCookieConfig --

    @Override public void setName(String name) {
        this.name = name;
    }

    @Override public String getName() {
        return name;
    }

    @Override public void setDomain(String domain) {
        this.domain = domain;
    }

    @Override public String getDomain() {
        return domain;
    }

    @Override public void setPath(String path) {
        this.path = path;
    }

    @Override public String getPath() {
        return path;
    }

    @Override public void setComment(String comment) {
        this.comment = comment;
    }

    @Override public String getComment() {
        return comment;
    }

    @Override public void setHttpOnly(boolean httpOnly) {
        this.httpOnly = httpOnly;
    }

    @Override public boolean isHttpOnly() {
        return httpOnly;
    }

    @Override public void setSecure(boolean secure) {
        this.secure = secure;
    }

    @Override public boolean isSecure() {
        return secure;
    }

    @Override public void setMaxAge(int maxAge) {
        this.maxAge = maxAge;
    }

    @Override public int getMaxAge() {
        return maxAge;
    }

    @Override public void setAttribute(String name, String value) {
        attributes.put(name, value);
    }

    @Override public String getAttribute(String name) {
        return attributes.get(name);
    }

    @Override public Map<String, String> getAttributes() {
        return Collections.unmodifiableMap(attributes);
    }

}

