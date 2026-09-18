/*
 * EchHttpsDiscovery.java
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

package org.bluezoo.gumdrop.tls;

import org.bluezoo.gumdrop.dns.DnsResourceRecord;
import org.bluezoo.gumdrop.dns.DnsType;

/**
 * Selects a client-usable {@link EchConfig} from DNS HTTPS {@code ech} SvcParam
 * values (RFC 9460 section 7.2.2 / RFC 9849).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class EchHttpsDiscovery {

    private EchHttpsDiscovery() {
    }

    /**
     * Returns the first {@link EchConfig} in {@code echConfigListBytes} that
     * matches gumdrop's HPKE profile, or null if none apply.
     *
     * @param echConfigListBytes wire {@code ECHConfigList}, or null
     * @return a usable config, or null
     */
    public static EchConfig selectClientConfig(byte[] echConfigListBytes) {
        if (echConfigListBytes == null || echConfigListBytes.length == 0) {
            return null;
        }
        try {
            EchConfig[] configs = EchConfig.parseList(echConfigListBytes);
            for (int i = 0; i < configs.length; i++) {
                if (isClientSelectable(configs[i])) {
                    return configs[i];
                }
            }
        } catch (HandshakeFormatException e) {
            return null;
        }
        return null;
    }

    private static boolean isClientSelectable(EchConfig config) {
        if (!config.supportsGumdropHpkeProfile()) {
            return false;
        }
        return !"grease.invalid".equals(config.getPublicName());
    }

    /**
     * Returns the first {@code ech} SvcParam from HTTPS answers (any ALPN), or null.
     */
    public static byte[] firstEchConfigListFromHttpsAnswers(Iterable<DnsResourceRecord> answers) {
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
