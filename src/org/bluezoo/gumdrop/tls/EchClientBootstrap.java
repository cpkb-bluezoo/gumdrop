/*
 * EchClientBootstrap.java
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

import java.io.IOException;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Selects outbound ECH settings from DNS HTTPS discovery and optional
 * {@link TlsConfig} client files.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class EchClientBootstrap {

    private static final Logger LOGGER = Logger.getLogger(EchClientBootstrap.class.getName());

    private EchClientBootstrap() {
    }

    /**
     * Picks a client-usable {@link EchConfig}: DNS list first, then an optional file.
     */
    public static EchConfig selectConfig(byte[] dnsDiscoveredEchConfigList, Path clientEchConfigListFile) {
        EchConfig ech = EchHttpsDiscovery.selectClientConfig(dnsDiscoveredEchConfigList);
        if (ech != null) {
            return ech;
        }
        if (clientEchConfigListFile == null) {
            return null;
        }
        try {
            return EchHttpsDiscovery.selectClientConfig(
                    EchKeyMaterial.readConfigListFile(clientEchConfigListFile));
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Could not load client ECHConfigList from " + clientEchConfigListFile, e);
        }
        return null;
    }

    /**
     * Enables client ECH and optional GREASE on a {@link HandshakeConfig}.
     */
    public static void applyToHandshakeConfig(HandshakeConfig config, EchConfig echConfig, boolean echGreaseEnabled) {
        if (echConfig != null) {
            config.setEchEnabled(true);
            config.setEchConfig(echConfig);
        }
        if (echGreaseEnabled) {
            config.setEchGreaseEnabled(true);
        }
    }
}
