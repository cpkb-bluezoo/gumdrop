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
import java.util.ArrayList;
import java.util.List;

/**
 * Loads ECH deployment material from files (ECHConfigList, X25519 private keys).
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
     * Reads one or more X25519 private keys from a file. A file of exactly
     * 32 bytes is one raw binary key; otherwise the file is text with one
     * key per line as 64 hex digits, where blank lines and lines starting
     * with {@code #} are ignored. Listing an old and a new key allows both
     * an outgoing and an incoming {@code ECHConfig} to be decrypted during
     * a rotation.
     *
     * @param path the key file
     * @return the keys, in file order, at least one
     * @throws IOException if the file cannot be read or holds no valid key
     */
    public static List<byte[]> readPrivateKeys(Path path) throws IOException {
        byte[] raw = Files.readAllBytes(path);
        List<byte[]> keys = new ArrayList<byte[]>();
        if (raw.length == X25519_PRIVATE_KEY_LENGTH) {
            keys.add(raw);
            return keys;
        }
        String[] lines = new String(raw, StandardCharsets.US_ASCII).split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.length() != X25519_PRIVATE_KEY_LENGTH * 2 || !isHex(line)) {
                throw new IOException("ECH private key file must hold 32 raw bytes or lines of 64 hex digits: " + path);
            }
            keys.add(decodeHex(line));
        }
        if (keys.isEmpty()) {
            throw new IOException("ECH private key file holds no key: " + path);
        }
        return keys;
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
