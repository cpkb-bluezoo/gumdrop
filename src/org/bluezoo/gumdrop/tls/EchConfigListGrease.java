/*
 * EchConfigListGrease.java
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

import java.security.SecureRandom;

/**
 * Prepends a GREASE {@code ECHConfig} to an {@code ECHConfigList} (RFC 9849 section 6.2.2).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class EchConfigListGrease {

    private EchConfigListGrease() {
    }

    /**
     * Returns {@code listBytes} prefixed with one GREASE config clients must ignore.
     */
    public static byte[] withServerGrease(byte[] listBytes) {
        if (listBytes == null || listBytes.length == 0) {
            return listBytes;
        }
        try {
            EchConfig[] configs = EchConfig.parseList(listBytes);
            SecureRandom random = new SecureRandom();
            byte[] publicKey = new byte[32];
            random.nextBytes(publicKey);
            int configId = random.nextInt(256);
            EchConfig grease = EchConfig.createV13(configId, publicKey, "grease.invalid", 0);
            EchConfig[] out = new EchConfig[configs.length + 1];
            out[0] = grease;
            System.arraycopy(configs, 0, out, 1, configs.length);
            return EchConfig.encodeList(out);
        } catch (HandshakeFormatException e) {
            return listBytes;
        }
    }
}
