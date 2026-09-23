/*
 * EchKeyMaterialTest.java
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
import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Unit tests for {@link EchKeyMaterial} and {@link EchDeployment}: loading
 * one or several ECH private keys and pairing each with the published
 * {@code ECHConfig} whose public key it matches.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class EchKeyMaterialTest {

    /** RFC 9180 appendix A.1 recipient key pair. */
    private static final String SK1_HEX = "4612c550263fc8ad58375df3f557aac531d26850903e55a9f23f21d8534e8ac8";
    private static final String PK1_HEX = "3948cfe0ad1ddb695d780e59077195da6c56506b027329794ab02bca80815c4d";
    /** RFC 7748 section 6.1 Alice's key pair. */
    private static final String SK2_HEX = "77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a";
    private static final String PK2_HEX = "8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a";

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private Path write(String name, String text) throws IOException {
        Path p = folder.newFile(name).toPath();
        Files.write(p, text.getBytes(StandardCharsets.US_ASCII));
        return p;
    }

    private Path writeList(String name, EchConfig... configs) throws IOException {
        Path p = folder.newFile(name).toPath();
        Files.write(p, EchConfig.encodeList(configs));
        return p;
    }

    @Test
    public void singleHexKeyFileStillReadsAsOneKey() throws Exception {
        List<byte[]> keys = EchKeyMaterial.readPrivateKeys(write("one.hex", SK1_HEX + "\n"));
        assertEquals(1, keys.size());
        assertArrayEquals(hex(SK1_HEX), keys.get(0));
    }

    @Test
    public void rawBinaryKeyFileStillReadsAsOneKey() throws Exception {
        Path p = folder.newFile("raw.key").toPath();
        Files.write(p, hex(SK1_HEX));
        List<byte[]> keys = EchKeyMaterial.readPrivateKeys(p);
        assertEquals(1, keys.size());
        assertArrayEquals(hex(SK1_HEX), keys.get(0));
    }

    @Test
    public void multipleKeysOnePerLineWithCommentsAndBlankLines() throws Exception {
        String text = "# current key\n" + SK2_HEX + "\n\n   # previous key\n" + SK1_HEX + "  \n";
        List<byte[]> keys = EchKeyMaterial.readPrivateKeys(write("many.hex", text));
        assertEquals(2, keys.size());
        assertArrayEquals(hex(SK2_HEX), keys.get(0));
        assertArrayEquals(hex(SK1_HEX), keys.get(1));
    }

    @Test
    public void malformedKeyLineIsRejected() throws Exception {
        try {
            EchKeyMaterial.readPrivateKeys(write("bad.hex", SK1_HEX + "\nnot-a-key\n"));
            fail("expected IOException");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("64 hex"));
        }
    }

    @Test
    public void emptyKeyFileIsRejected() throws Exception {
        try {
            EchKeyMaterial.readPrivateKeys(write("empty.hex", "# nothing\n"));
            fail("expected IOException");
        } catch (IOException expected) {
            // no key present
        }
    }

    @Test
    public void deploymentPairsEachKeyWithTheConfigHoldingItsPublicKey() throws Exception {
        EchConfig first = EchConfig.createV13(1, hex(PK1_HEX), "public.example", 32);
        EchConfig second = EchConfig.createV13(2, hex(PK2_HEX), "public.example", 32);
        Path list = writeList("list.bin", first, second);
        // keys listed in the opposite order to the configs
        Path keys = write("keys.hex", SK2_HEX + "\n" + SK1_HEX + "\n");

        HandshakeConfig config = new HandshakeConfig(HandshakeRole.SERVER);
        EchDeployment.applyServer(config, list, keys, false);

        List<EchServerKey> loaded = config.getEchServerKeys();
        assertEquals(2, loaded.size());
        for (EchServerKey key : loaded) {
            if (key.getConfig().getConfigId() == 1) {
                assertArrayEquals(hex(SK1_HEX), key.getPrivateKey());
            } else {
                assertEquals(2, key.getConfig().getConfigId());
                assertArrayEquals(hex(SK2_HEX), key.getPrivateKey());
            }
        }
        assertArrayEquals("retry_configs advertises the whole published list",
                EchConfig.encodeList(new EchConfig[] { first, second }), config.getEchRetryConfigList());
    }

    @Test
    public void deploymentIgnoresAKeyMatchingNoPublishedConfig() throws Exception {
        EchConfig only = EchConfig.createV13(1, hex(PK1_HEX), "public.example", 32);
        Path list = writeList("list.bin", only);
        Path keys = write("keys.hex", SK1_HEX + "\n" + SK2_HEX + "\n");

        HandshakeConfig config = new HandshakeConfig(HandshakeRole.SERVER);
        EchDeployment.applyServer(config, list, keys, false);

        assertEquals(1, config.getEchServerKeys().size());
        assertEquals(1, config.getEchServerKeys().get(0).getConfig().getConfigId());
    }

    @Test
    public void deploymentWithNoMatchingKeyLoadsNothing() throws Exception {
        EchConfig only = EchConfig.createV13(1, hex(PK1_HEX), "public.example", 32);
        Path list = writeList("list.bin", only);
        Path keys = write("keys.hex", SK2_HEX + "\n");

        HandshakeConfig config = new HandshakeConfig(HandshakeRole.SERVER);
        EchDeployment.applyServer(config, list, keys, false);

        assertTrue(config.getEchServerKeys().isEmpty());
        assertNull(config.getEchRetryConfigList());
    }
}
