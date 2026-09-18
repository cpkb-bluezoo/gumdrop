/*
 * EchDeployment.java
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
 * Loads ECH key material onto a {@link HandshakeConfig} for server listeners.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class EchDeployment {

    private static final Logger LOGGER = Logger.getLogger(EchDeployment.class.getName());

    private EchDeployment() {
    }

    /**
     * Applies server ECH settings from optional deployment files.
     *
     * @param config target handshake configuration
     * @param echConfigListFile {@code ECHConfigList} file, or null
     * @param echPrivateKeyFile X25519 private key file, or null
     * @param echServerRequired whether to require client ECH offers
     */
    public static void applyServer(HandshakeConfig config, Path echConfigListFile, Path echPrivateKeyFile,
            boolean echServerRequired) {
        config.setEchServerRequired(echServerRequired);
        if (echConfigListFile == null || echPrivateKeyFile == null) {
            return;
        }
        try {
            byte[] listBytes = EchKeyMaterial.readConfigListFile(echConfigListFile);
            byte[] privateKey = EchKeyMaterial.readPrivateKeyFile(echPrivateKeyFile);
            EchConfig ech = EchKeyMaterial.parseFirstConfig(echConfigListFile);
            config.setEchServerKeys(ech, privateKey);
            config.setEchRetryConfigList(listBytes);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Could not load ECH server material", e);
        }
    }
}
