/*
 * EchKeyMaterial.java
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loads ECH deployment material from files (ECHConfigList, X25519 private key).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class EchKeyMaterial {

    private static final int X25519_PRIVATE_KEY_LENGTH = 32;

    private EchKeyMaterial() {
    }

    /**
     * Reads an {@code ECHConfigList} from a file.
     *
     * @param path file containing the binary list
     * @return raw list bytes
     */
    public static byte[] readConfigListFile(Path path) throws IOException {
        return Files.readAllBytes(path);
    }

    /**
     * Parses the first {@link EchConfig} from a list file.
     */
    public static EchConfig parseFirstConfig(Path configListFile) throws IOException {
        try {
            EchConfig[] configs = EchConfig.parseList(readConfigListFile(configListFile));
            return configs[0];
        } catch (HandshakeFormatException e) {
            throw new IOException("Invalid ECHConfigList: " + e.getMessage(), e);
        }
    }

    /**
     * Reads a 32-byte X25519 private key from a file (raw binary or ASCII hex).
     */
    public static byte[] readPrivateKeyFile(Path path) throws IOException {
        byte[] raw = Files.readAllBytes(path);
        if (raw.length == X25519_PRIVATE_KEY_LENGTH) {
            return raw;
        }
        String text = new String(raw, StandardCharsets.US_ASCII).trim();
        if (text.length() == X25519_PRIVATE_KEY_LENGTH * 2 && isHex(text)) {
            return decodeHex(text);
        }
        throw new IOException("ECH private key file must be 32 bytes or 64 hex digits: " + path);
    }

    private static boolean isHex(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) {
                return false;
            }
        }
        return true;
    }

    private static byte[] decodeHex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
