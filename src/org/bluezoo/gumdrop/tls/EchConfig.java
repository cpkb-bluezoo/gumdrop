/*
 * EchConfig.java
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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.bluezoo.gumdrop.crypto.Hpke;

/**
 * RFC 9849 {@code ECHConfig} and {@code ECHConfigList} parsing and
 * serialization for TLS Encrypted Client Hello.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9849">RFC 9849</a>
 */
public final class EchConfig {

    /** {@code encrypted_client_hello} extension type and {@code ECHConfig.version}. */
    public static final int VERSION_ECH13 = 0xfe0d;

    private static final byte[] HPKE_SETUP_PREFIX = "tls ech".getBytes(StandardCharsets.US_ASCII);

    private final int configId;
    private final int kemId;
    private final byte[] publicKey;
    private final int[][] cipherSuites;
    private final int maximumNameLength;
    private final String publicName;
    private final EchConfigExtension[] extensions;

    private EchConfig(int configId, int kemId, byte[] publicKey, int[][] cipherSuites,
            int maximumNameLength, String publicName, EchConfigExtension[] extensions) {
        this.configId = configId;
        this.kemId = kemId;
        this.publicKey = publicKey;
        this.cipherSuites = cipherSuites;
        this.maximumNameLength = maximumNameLength;
        this.publicName = publicName;
        this.extensions = extensions;
    }

    /**
     * Parses an {@code ECHConfigList} (RFC 9849 section 4).
     *
     * @param listBytes length-prefixed list body (4..65535 bytes total)
     * @return parsed configs in list order
     */
    public static EchConfig[] parseList(byte[] listBytes) throws HandshakeFormatException {
        if (listBytes.length < 4 || listBytes.length > 65535) {
            throw new HandshakeFormatException("ECHConfigList length out of range");
        }
        List<EchConfig> configs = new ArrayList<EchConfig>();
        WireReader r = new WireReader(listBytes);
        while (r.hasRemaining()) {
            configs.add(parse(r));
        }
        if (configs.isEmpty()) {
            throw new HandshakeFormatException("ECHConfigList is empty");
        }
        return configs.toArray(new EchConfig[configs.size()]);
    }

    /**
     * Parses one {@code ECHConfig} from the current position of {@code reader}.
     */
    public static EchConfig parse(WireReader reader) throws HandshakeFormatException {
        int version = reader.u16();
        if (version != VERSION_ECH13) {
            throw new HandshakeFormatException("Unsupported ECHConfig version: " + version);
        }
        int length = reader.u16();
        WireReader contents = reader.slice(length);
        return parseContentsV13(contents);
    }

    /**
     * Parses a standalone encoded {@code ECHConfig} (version, length, contents).
     */
    public static EchConfig parse(byte[] encoded) throws HandshakeFormatException {
        return parse(new WireReader(encoded));
    }

    private static EchConfig parseContentsV13(WireReader contents) throws HandshakeFormatException {
        int configId = contents.u8();
        int kemId = contents.u16();
        byte[] publicKey = contents.opaque16();
        if (publicKey.length < 1) {
            throw new HandshakeFormatException("HpkePublicKey is empty");
        }
        byte[] suiteBytes = contents.opaque16();
        if (suiteBytes.length < 4 || (suiteBytes.length % 4) != 0) {
            throw new HandshakeFormatException("HpkeKeyConfig cipher_suites length invalid");
        }
        int suiteCount = suiteBytes.length / 4;
        int[][] cipherSuites = new int[suiteCount][2];
        WireReader suites = new WireReader(suiteBytes);
        for (int i = 0; i < suiteCount; i++) {
            cipherSuites[i][0] = suites.u16();
            cipherSuites[i][1] = suites.u16();
        }
        int maximumNameLength = contents.u8();
        String publicName = contents.opaque8Ascii();
        if (publicName.length() < 1) {
            throw new HandshakeFormatException("public_name is empty");
        }
        EchConfigExtension[] extensions = parseExtensions(contents);
        if (contents.hasRemaining()) {
            throw new HandshakeFormatException("Trailing bytes in ECHConfigContents");
        }
        return new EchConfig(configId, kemId, publicKey, cipherSuites, maximumNameLength, publicName, extensions);
    }

    private static EchConfigExtension[] parseExtensions(WireReader contents) throws HandshakeFormatException {
        if (!contents.hasRemaining()) {
            return new EchConfigExtension[0];
        }
        byte[] extBlock = contents.opaque16();
        List<EchConfigExtension> list = new ArrayList<EchConfigExtension>();
        WireReader er = new WireReader(extBlock);
        while (er.hasRemaining()) {
            int type = er.u16();
            byte[] data = er.opaque16();
            list.add(new EchConfigExtension(type, data));
        }
        return list.toArray(new EchConfigExtension[list.size()]);
    }

    /**
     * Encodes this config as a single {@code ECHConfig} structure.
     */
    public byte[] encode() {
        WireWriter contents = new WireWriter();
        contents.u8(configId);
        contents.u16(kemId);
        contents.opaque16(publicKey);
        WireWriter suites = new WireWriter();
        for (int i = 0; i < cipherSuites.length; i++) {
            suites.u16(cipherSuites[i][0]);
            suites.u16(cipherSuites[i][1]);
        }
        contents.opaque16(suites.toByteArray());
        contents.u8(maximumNameLength);
        contents.opaque8Ascii(publicName);
        WireWriter ext = new WireWriter();
        for (int i = 0; i < extensions.length; i++) {
            ext.u16(extensions[i].type);
            ext.opaque16(extensions[i].data);
        }
        contents.opaque16(ext.toByteArray());

        byte[] body = contents.toByteArray();
        WireWriter out = new WireWriter();
        out.u16(VERSION_ECH13);
        out.u16(body.length);
        out.bytes(body);
        return out.toByteArray();
    }

    /**
     * Encodes a list of configs as {@code ECHConfigList}.
     */
    public static byte[] encodeList(EchConfig[] configs) {
        WireWriter out = new WireWriter();
        for (int i = 0; i < configs.length; i++) {
            out.bytes(configs[i].encode());
        }
        return out.toByteArray();
    }

    /**
     * HPKE {@code info} for ECH base setup (RFC 9849 section 6.1):
     * {@code "tls ech" || 0x00 || ECHConfig}.
     */
    public byte[] hpkeSetupInfo() {
        byte[] encoded = encode();
        byte[] info = new byte[HPKE_SETUP_PREFIX.length + 1 + encoded.length];
        System.arraycopy(HPKE_SETUP_PREFIX, 0, info, 0, HPKE_SETUP_PREFIX.length);
        info[HPKE_SETUP_PREFIX.length] = 0;
        System.arraycopy(encoded, 0, info, HPKE_SETUP_PREFIX.length + 1, encoded.length);
        return info;
    }

    /**
     * Returns whether this config advertises the gumdrop HPKE profile
     * ({@link Hpke#KEM_X25519_HKDF_SHA256} with HKDF-SHA256 + AES-128-GCM).
     */
    public boolean supportsGumdropHpkeProfile() {
        if (kemId != Hpke.KEM_X25519_HKDF_SHA256) {
            return false;
        }
        for (int i = 0; i < cipherSuites.length; i++) {
            if (cipherSuites[i][0] == Hpke.KDF_HKDF_SHA256
                    && cipherSuites[i][1] == Hpke.AEAD_AES_128_GCM) {
                return true;
            }
        }
        return false;
    }

    public int getConfigId() {
        return configId;
    }

    public int getKemId() {
        return kemId;
    }

    public byte[] getPublicKey() {
        return publicKey;
    }

    public int[][] getCipherSuites() {
        return cipherSuites;
    }

    public int getMaximumNameLength() {
        return maximumNameLength;
    }

    public String getPublicName() {
        return publicName;
    }

    public EchConfigExtension[] getExtensions() {
        return extensions;
    }

    /**
     * Builds a v13 {@code ECHConfig} for tests and static deployment.
     */
    public static EchConfig createV13(int configId, byte[] x25519PublicKey, String publicName,
            int maximumNameLength) {
        int[][] suites = new int[][] {
                { Hpke.KDF_HKDF_SHA256, Hpke.AEAD_AES_128_GCM }
        };
        return new EchConfig(configId, Hpke.KEM_X25519_HKDF_SHA256, x25519PublicKey, suites,
                maximumNameLength, publicName, new EchConfigExtension[0]);
    }

    /**
     * One {@code ECHConfigExtension} (type + opaque data).
     */
    public static final class EchConfigExtension {
        public final int type;
        public final byte[] data;

        public EchConfigExtension(int type, byte[] data) {
            this.type = type;
            this.data = data;
        }
    }
}
