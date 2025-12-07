/*
 * FilterResponse.java
 * Copyright (C) 2005 Chris Burdess
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

import java.io.IOException;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;

/**
 * Response wrapper to store the status code set on the underlying response.
 *
 * @author <a href='amilto:dog@gnu.org'>Chris Burdess</a>
 */
class FilterResponse extends HttpServletResponseWrapper {

    int code;
    final boolean ignoreHeaderMutators;

    FilterResponse(HttpServletResponse response) {
        super(response);
        ignoreHeaderMutators = false;
    }

    FilterResponse(HttpServletResponse response, boolean ignoreHeaderMutators) {
        super(response);
        this.ignoreHeaderMutators = ignoreHeaderMutators;
    }

    public void setStatus(int sc) {
        if (ignoreHeaderMutators) {
            return;
        }
        code = sc;
        super.setStatus(sc);
    }

    public void setStatus(int sc, String message) {
        if (ignoreHeaderMutators) {
            return;
        }
        code = sc;
        super.setStatus(sc, message);
    }

    public void addCookie(Cookie cookie) {
        if (ignoreHeaderMutators) {
            return;
        }
        super.addCookie(cookie);
    }

    public void addDateHeader(String name, long date) {
        if (ignoreHeaderMutators) {
            return;
        }
        super.addDateHeader(name, date);
    }

    public void addHeader(String name, String value) {
        if (ignoreHeaderMutators) {
            return;
        }
        super.addHeader(name, value);
    }

    public void addIntHeader(String name, int value) {
        if (ignoreHeaderMutators) {
            return;
        }
        super.addIntHeader(name, value);
    }

    public void sendError(int sc) throws IOException {
        if (ignoreHeaderMutators) {
            return;
        }
        super.sendError(sc);
    }

    public void sendError(int sc, String message) throws IOException {
        if (ignoreHeaderMutators) {
            return;
        }
        super.sendError(sc, message);
    }

    public void sendRedirect(String location) throws IOException {
        if (ignoreHeaderMutators) {
            return;
        }
        super.sendRedirect(location);
    }

    public void setDateHeader(String name, long date) {
        if (ignoreHeaderMutators) {
            return;
        }
        super.setDateHeader(name, date);
    }

    public void setHeader(String name, String value) {
        if (ignoreHeaderMutators) {
            return;
        }
        super.setHeader(name, value);
    }

    public void setIntHeader(String name, int value) {
        if (ignoreHeaderMutators) {
            return;
        }
        super.setIntHeader(name, value);
    }

}
