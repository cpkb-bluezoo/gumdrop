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
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.List;
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.crypto.Hpke;

/**
 * Loads ECH key material onto a {@link HandshakeConfig} for server listeners.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class EchDeployment {

    private static final Logger LOGGER = Logger.getLogger(EchDeployment.class.getName());
    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.L10N");

    private EchDeployment() {
    }

    /**
     * Applies server ECH settings from optional deployment files.
     *
     * <p>The config list file is the full published {@code ECHConfigList},
     * sent as {@code retry_configs} on rejection. Each private key in the
     * key file (see {@link EchKeyMaterial#readPrivateKeys}) is paired with the
     * config whose public key it matches, so a rotation lists the old and new
     * configs together with both keys. A key matching no config is logged and
     * ignored; a config without a key is published but cannot be decrypted.
     *
     * @param config target handshake configuration
     * @param echConfigListFile {@code ECHConfigList} file, or null
     * @param echPrivateKeyFile file holding one or more X25519 private keys, or null
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
            EchConfig[] published = EchConfig.parseList(listBytes);
            List<byte[]> privateKeys = EchKeyMaterial.readPrivateKeys(echPrivateKeyFile);
            int paired = 0;
            for (int i = 0; i < privateKeys.size(); i++) {
                byte[] privateKey = privateKeys.get(i);
                byte[] publicKey = Hpke.deriveX25519PublicKey(privateKey);
                boolean matched = false;
                for (int j = 0; j < published.length; j++) {
                    if (Arrays.equals(published[j].getPublicKey(), publicKey)) {
                        config.addEchServerKey(published[j], privateKey);
                        matched = true;
                        paired++;
                    }
                }
                if (!matched) {
                    LOGGER.log(Level.WARNING, L10N.getString("warn.ech_private_key_without_config"),
                            echPrivateKeyFile);
                }
            }
            if (paired > 0) {
                config.setEchRetryConfigList(listBytes);
            }
        } catch (IOException | HandshakeFormatException | GeneralSecurityException e) {
            LOGGER.log(Level.WARNING,
                    L10N.getString("warn.ech_server_material_load_failed"), e);
        }
    }
}
