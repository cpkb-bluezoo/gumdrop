/*
 * KeyLogTest.java
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.bluezoo.gumdrop.testsupport.memfs.MemoryTemp;
import org.junit.Test;

/**
 * The file key log must write exactly the NSS key log format.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class KeyLogTest {

    private static byte[] sequence(int length, int first) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = (byte) (first + i);
        }
        return bytes;
    }

    @Test
    public void fileWritesOneNssLinePerSecret() throws Exception {
        Path dir = MemoryTemp.createTempDirectory("keylog");
        Path file = dir.resolve("keys.log");
        KeyLog log = KeyLog.file(file);
        log.log(KeyLog.CLIENT_HANDSHAKE_TRAFFIC_SECRET, sequence(32, 0), sequence(4, 0xA0));
        log.log(KeyLog.serverTrafficSecret(0), sequence(32, 0), new byte[] {(byte) 0xff, 0x00});
        List<String> lines = Files.readAllLines(file, StandardCharsets.US_ASCII);
        assertEquals(2, lines.size());
        assertEquals("CLIENT_HANDSHAKE_TRAFFIC_SECRET"
                + " 000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
                + " a0a1a2a3", lines.get(0));
        assertEquals("SERVER_TRAFFIC_SECRET_0"
                + " 000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
                + " ff00", lines.get(1));
    }

    @Test
    public void fileAppendsToAnExistingLog() throws Exception {
        Path dir = MemoryTemp.createTempDirectory("keylog");
        Path file = dir.resolve("keys.log");
        KeyLog.file(file).log(KeyLog.CLIENT_RANDOM, sequence(32, 1), sequence(48, 2));
        KeyLog.file(file).log(KeyLog.EXPORTER_SECRET, sequence(32, 3), sequence(32, 4));
        List<String> lines = Files.readAllLines(file, StandardCharsets.US_ASCII);
        assertEquals(2, lines.size());
        assertEquals("CLIENT_RANDOM", lines.get(0).substring(0, 13));
        assertEquals("EXPORTER_SECRET", lines.get(1).substring(0, 15));
    }

    @Test
    public void trafficSecretLabelsCarryTheirGeneration() {
        assertEquals("CLIENT_TRAFFIC_SECRET_0", KeyLog.clientTrafficSecret(0));
        assertEquals("SERVER_TRAFFIC_SECRET_3", KeyLog.serverTrafficSecret(3));
    }

    @Test
    public void defaultIsUnsetUntilAnApplicationSetsIt() throws Exception {
        assertNull(KeyLog.getDefault());
        KeyLog log = KeyLog.file(MemoryTemp.createTempDirectory("keylog").resolve("k"));
        try {
            KeyLog.setDefault(log);
            assertSame(log, KeyLog.getDefault());
        } finally {
            KeyLog.setDefault(null);
        }
        assertNull(KeyLog.getDefault());
    }

}
