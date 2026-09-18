/*
 * HttpsRecordEch.java
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

package org.bluezoo.gumdrop.dns;

/**
 * Reads {@code ech} SvcParam values from DNS HTTPS (SVCB) records (RFC 9460).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class HttpsRecordEch {

    private HttpsRecordEch() {
    }

    /**
     * Returns the first {@code ech} SvcParam from HTTPS answers (any ALPN), or null.
     */
    public static byte[] firstEchConfigListFromAnswers(Iterable<DnsResourceRecord> answers) {
        if (answers == null) {
            return null;
        }
        for (DnsResourceRecord rr : answers) {
            if (rr.getType() != DnsType.HTTPS || rr.isSVCBAliasForm()) {
                continue;
            }
            byte[] ech = rr.getSVCBEchConfigList();
            if (ech != null) {
                return ech;
            }
        }
        return null;
    }
}
