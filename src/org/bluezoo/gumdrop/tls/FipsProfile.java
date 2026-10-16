/*
 * FipsProfile.java
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

import java.util.ArrayList;
import java.util.List;

import org.bluezoo.gumdrop.crypto.NamedGroup;
import org.bluezoo.gumdrop.crypto.SignatureScheme;

/**
 * The profile a runtime in FIPS mode limits every TLS 1.2, TLS 1.3, DTLS
 * and QUIC handshake to: AES-GCM with SHA-256 or SHA-384, ECDHE on the
 * NIST curves, and ECDSA or RSA signatures. ChaCha20-Poly1305, X25519,
 * Ed25519 and the hybrid ML-KEM groups are outside it, as is Encrypted
 * Client Hello, whose key encapsulation is X25519. ML-DSA is outside it
 * too: whether a module's certificate covers it cannot be told from here.
 *
 * <p>The profile is applied by {@link HandshakeConfig} and
 * {@link Tls12HandshakeConfig} to their defaults and to every list a
 * caller sets, so a configuration under a FIPS runtime can narrow the
 * profile but never widen it, and by the handshake engines to the
 * signature schemes they offer and accept.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class FipsProfile {

    private FipsProfile() {
    }

    /**
     * Whether a TLS 1.3 cipher suite is in the profile: the AES-GCM suites.
     *
     * @param suite the suite
     * @return true if permitted
     */
    static boolean permits(CipherSuite suite) {
        return suite == CipherSuite.TLS_AES_128_GCM_SHA256 || suite == CipherSuite.TLS_AES_256_GCM_SHA384;
    }

    /**
     * Whether a TLS 1.2 cipher suite is in the profile: the ECDHE AES-GCM
     * suites.
     *
     * @param suite the suite
     * @return true if permitted
     */
    static boolean permits(Tls12CipherSuite suite) {
        return suite == Tls12CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256
                || suite == Tls12CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256
                || suite == Tls12CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384
                || suite == Tls12CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384;
    }

    /**
     * Whether a key exchange group is in the profile: secp256r1 and
     * secp384r1. The hybrid groups are not, independently of ML-KEM's own
     * standing: their shared secret is combined outside the module.
     *
     * @param group the group
     * @return true if permitted
     */
    static boolean permits(NamedGroup group) {
        return group == NamedGroup.SECP256R1 || group == NamedGroup.SECP384R1;
    }

    /**
     * Whether a signature scheme is in the profile: ECDSA on the NIST
     * curves and RSA, with RSASSA-PSS for TLS 1.3 and also PKCS#1 v1.5 for
     * TLS 1.2 where the module permits it. Ed25519 and ML-DSA are not.
     *
     * @param scheme the scheme
     * @return true if permitted
     */
    static boolean permits(SignatureScheme scheme) {
        switch (scheme) {
            case ED25519:
            case MLDSA44:
            case MLDSA65:
            case MLDSA87:
                return false;
            default:
                return true;
        }
    }

    /**
     * The permitted suites of a list, in its order.
     *
     * @param suites the suites a caller configured
     * @return the permitted ones, possibly empty
     */
    static List<CipherSuite> filterSuites(List<CipherSuite> suites) {
        List<CipherSuite> permitted = new ArrayList<CipherSuite>(suites.size());
        for (int i = 0; i < suites.size(); i++) {
            if (permits(suites.get(i))) {
                permitted.add(suites.get(i));
            }
        }
        return permitted;
    }

    /**
     * The permitted TLS 1.2 suites of a list, in its order.
     *
     * @param suites the suites a caller configured
     * @return the permitted ones, possibly empty
     */
    static List<Tls12CipherSuite> filterTls12Suites(List<Tls12CipherSuite> suites) {
        List<Tls12CipherSuite> permitted = new ArrayList<Tls12CipherSuite>(suites.size());
        for (int i = 0; i < suites.size(); i++) {
            if (permits(suites.get(i))) {
                permitted.add(suites.get(i));
            }
        }
        return permitted;
    }

    /**
     * The permitted groups of a list, in its order.
     *
     * @param groups the groups a caller configured
     * @return the permitted ones, possibly empty
     */
    static List<NamedGroup> filterGroups(List<NamedGroup> groups) {
        List<NamedGroup> permitted = new ArrayList<NamedGroup>(groups.size());
        for (int i = 0; i < groups.size(); i++) {
            if (permits(groups.get(i))) {
                permitted.add(groups.get(i));
            }
        }
        return permitted;
    }

    /**
     * The permitted signature schemes of a list, in its order.
     *
     * @param schemes the schemes
     * @return the permitted ones, possibly empty
     */
    static List<SignatureScheme> filterSchemes(List<SignatureScheme> schemes) {
        List<SignatureScheme> permitted = new ArrayList<SignatureScheme>(schemes.size());
        for (int i = 0; i < schemes.size(); i++) {
            if (permits(schemes.get(i))) {
                permitted.add(schemes.get(i));
            }
        }
        return permitted;
    }

}
