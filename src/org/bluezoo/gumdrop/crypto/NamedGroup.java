/*
 * NamedGroup.java
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

package org.bluezoo.gumdrop.crypto;

/**
 * TLS 1.3 key-exchange groups (the {@code NamedGroup} enum of RFC 8446
 * section 4.2.7), extended with the three post-quantum/classical hybrid
 * groups of RFC 10024. Each constant carries its IANA codepoint and the
 * wire lengths of a client and server key share.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc8446#section-4.2.7">RFC 8446 section 4.2.7</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc10024">RFC 10024</a>
 */
public enum NamedGroup {

    X25519(0x001d, 32, 32),
    SECP256R1(0x0017, 65, 65),
    SECP384R1(0x0018, 97, 97),

    /**
     * The hybrid post-quantum/classical group of RFC 10024 section 2.2.
     * The client's key share is its ML-KEM-768 encapsulation key (1184
     * bytes) concatenated with its X25519 share (32 bytes); the server's
     * response is the ML-KEM-768 ciphertext (1088 bytes) concatenated
     * with its own X25519 share (32 bytes). ML-KEM component
     * <em>first</em> in both -- RFC 10024 documents this as a deliberate
     * historical exception to the classical-first convention the other
     * two hybrid groups below follow.
     */
    X25519_MLKEM768(0x11ec, 1184 + 32, 1088 + 32, true),

    /**
     * RFC 10024 section 2.3: classical P-256 (uncompressed point, 65
     * bytes) concatenated with ML-KEM-768, classical component
     * <em>first</em> -- the RFC's normal (non-exceptional) ordering.
     */
    SECP256R1_MLKEM768(0x11eb, 65 + 1184, 65 + 1088, false),

    /**
     * RFC 10024 section 2.4: classical P-384 (uncompressed point, 97
     * bytes) concatenated with ML-KEM-1024, classical component first.
     * Unlike ML-KEM-768 (whose 1184-byte encapsulation key and
     * 1088-byte ciphertext differ), ML-KEM-1024's two sizes coincide at
     * 1568 bytes, so this group's client and server share lengths are
     * equal.
     */
    SECP384R1_MLKEM1024(0x11ed, 97 + 1568, 97 + 1568, false);

    private final int code;
    private final int clientShareLength;
    private final int serverShareLength;
    private final boolean pqFirst;

    NamedGroup(int code, int clientShareLength, int serverShareLength) {
        this(code, clientShareLength, serverShareLength, false);
    }

    NamedGroup(int code, int clientShareLength, int serverShareLength, boolean pqFirst) {
        this.code = code;
        this.clientShareLength = clientShareLength;
        this.serverShareLength = serverShareLength;
        this.pqFirst = pqFirst;
    }

    /**
     * Returns the IANA codepoint for this group, as carried on the wire
     * in {@code key_share} and {@code supported_groups}.
     *
     * @return the two-octet codepoint
     */
    public int getCode() {
        return code;
    }

    /**
     * Returns the wire length in bytes of a client key share for this
     * group.
     *
     * @return the client key share length
     */
    public int getClientShareLength() {
        return clientShareLength;
    }

    /**
     * Returns the wire length in bytes of a server key share (the
     * response half of the exchange) for this group. Equal to
     * {@link #getClientShareLength()} for the classical groups (and, by
     * coincidence, for {@link #SECP384R1_MLKEM1024}), but different for
     * the other hybrids since ML-KEM's ciphertext is a different size
     * from its encapsulation key.
     *
     * @return the server key share length
     */
    public int getServerShareLength() {
        return serverShareLength;
    }

    /**
     * Returns whether this is a post-quantum/classical hybrid group.
     *
     * @return true if this group combines a classical and a PQ component
     */
    public boolean isHybrid() {
        return this == X25519_MLKEM768 || this == SECP256R1_MLKEM768 || this == SECP384R1_MLKEM1024;
    }

    /**
     * For a hybrid group, returns whether its wire key share and combined
     * shared secret concatenate the ML-KEM component before the classical
     * component. Meaningless for a non-hybrid group. Only
     * {@link #X25519_MLKEM768} is PQ-first -- RFC 10024 documents this as
     * a deliberate historical exception; every other hybrid group
     * (including the other two here) is classical-first.
     *
     * @return true if the ML-KEM component comes first on the wire
     */
    public boolean isPqFirst() {
        return pqFirst;
    }

    /**
     * Looks up a group by its IANA codepoint.
     *
     * @param code the codepoint
     * @return the matching group, or null if unrecognised
     */
    public static NamedGroup fromCode(int code) {
        NamedGroup[] values = values();
        for (int i = 0; i < values.length; i++) {
            if (values[i].code == code) {
                return values[i];
            }
        }
        return null;
    }

}
