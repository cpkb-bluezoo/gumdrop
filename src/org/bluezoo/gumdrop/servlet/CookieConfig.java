/*
 * CookieConfig.java
 * Copyright (C) 2025 Chris Burdess
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

import javax.servlet.SessionCookieConfig;

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

}

