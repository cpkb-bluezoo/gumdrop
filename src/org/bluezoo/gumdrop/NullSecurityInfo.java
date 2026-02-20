/*
 * NullSecurityInfo.java
 * Copyright (C) 2026 Chris Burdess
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

package org.bluezoo.gumdrop;

import java.security.cert.Certificate;

/**
 * SecurityInfo implementation for plaintext (unsecured) endpoints.
 *
 * <p>All methods return null or -1 as appropriate. Use the shared
 * {@link #INSTANCE} singleton rather than creating new instances.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see SecurityInfo
 */
public final class NullSecurityInfo implements SecurityInfo {

    /** Shared singleton instance. */
    public static final NullSecurityInfo INSTANCE = new NullSecurityInfo();

    private NullSecurityInfo() {
    }

    @Override
    public String getProtocol() {
        return null;
    }

    @Override
    public String getCipherSuite() {
        return null;
    }

    @Override
    public int getKeySize() {
        return -1;
    }

    @Override
    public Certificate[] getPeerCertificates() {
        return null;
    }

    @Override
    public Certificate[] getLocalCertificates() {
        return null;
    }

    @Override
    public String getApplicationProtocol() {
        return null;
    }

    @Override
    public long getHandshakeDurationMs() {
        return -1;
    }

    @Override
    public boolean isSessionResumed() {
        return false;
    }
}
