/*
 * EchServerKey.java
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
 * A server's ECH decryption key: a published {@link EchConfig} paired with
 * the X25519 private key matching its public key (RFC 9849 section 7).
 * A server holds one per config it can still decrypt for, so that clients
 * using a superseded config keep working across a key rotation.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class EchServerKey {

    private final EchConfig config;
    private final byte[] privateKey;

    /**
     * Creates a key pairing.
     *
     * @param config the published config (includes {@code config_id})
     * @param privateKey the 32-byte X25519 private key matching the config
     */
    public EchServerKey(EchConfig config, byte[] privateKey) {
        this.config = config;
        this.privateKey = privateKey;
    }

    /**
     * Returns the published config.
     *
     * @return the config
     */
    public EchConfig getConfig() {
        return config;
    }

    /**
     * Returns the X25519 private key.
     *
     * @return the raw 32-byte private key
     */
    public byte[] getPrivateKey() {
        return privateKey;
    }
}
