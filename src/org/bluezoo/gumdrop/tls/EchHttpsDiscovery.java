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
     * offers an HPKE suite gumdrop implements ({@link EchConfig#selectHpkeCipherSuite}),
     * or null if none apply.
     *
     * @param echConfigListBytes wire {@code ECHConfigList}, or null
     * @return a usable config, or null
     */
    public static EchConfig selectClientConfig(byte[] echConfigListBytes) {
        if (echConfigListBytes == null || echConfigListBytes.length == 0) {
            return null;
        }
        try {
            return firstSelectable(EchConfig.parseList(echConfigListBytes));
        } catch (HandshakeFormatException e) {
            return null;
        }
    }

    /**
     * Returns the first config in {@code configs} that offers an HPKE suite
     * gumdrop implements and is not a GREASE placeholder, or null if none.
     *
     * @param configs parsed configs, in the publisher's order
     * @return a usable config, or null
     */
    public static EchConfig firstSelectable(EchConfig[] configs) {
        for (int i = 0; i < configs.length; i++) {
            if (isClientSelectable(configs[i])) {
                return configs[i];
            }
        }
        return null;
    }

    private static boolean isClientSelectable(EchConfig config) {
        if (config.selectHpkeCipherSuite() == null) {
            return false;
        }
        return !"grease.invalid".equals(config.getPublicName());
    }
}
