/*
 * InitialSecretsTest.java
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

package org.bluezoo.gumdrop.quic.tls;

import org.junit.Test;

import org.bluezoo.gumdrop.quic.packet.QuicVersion;
import org.bluezoo.util.ByteArrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/**
 * Verifies {@link InitialSecrets} against the worked example in
 * RFC 9001 Appendix A.1, using the exact Destination Connection ID and
 * expected secrets given there.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9001#appendix-A.1">RFC 9001 Appendix A.1</a>
 */
public class InitialSecretsTest {

    private static final byte[] DCID =
            ByteArrays.toByteArray("8394c8f03e515708");

    private static final String CLIENT_INITIAL_SECRET =
            "c00cf151ca5be075ed0ebfb5c80323c42d6b7db67881289af4008f1f6c357aea";

    private static final String SERVER_INITIAL_SECRET =
            "3c199828fd139efd216c155ad844cc81fb82fa8d7446fa7d78be803acdda951b";

    @Test
    public void testClientInitialSecretV1() {
        byte[] secret = InitialSecrets.clientSecret(QuicVersion.V1, DCID);
        assertEquals(CLIENT_INITIAL_SECRET, ByteArrays.toHexString(secret));
    }

    @Test
    public void testServerInitialSecretV1() {
        byte[] secret = InitialSecrets.serverSecret(QuicVersion.V1, DCID);
        assertEquals(SERVER_INITIAL_SECRET, ByteArrays.toHexString(secret));
    }

    // RFC 9369 appendix A.1 (QUIC version 2)
    @Test
    public void testClientInitialSecretV2() {
        byte[] secret = InitialSecrets.clientSecret(QuicVersion.V2, DCID);
        assertEquals("14ec9d6eb9fd7af83bf5a668bc17a7e283766aade7ecd0891f70f9ff7f4bf47b",
                ByteArrays.toHexString(secret));
    }

    @Test
    public void testServerInitialSecretV2() {
        byte[] secret = InitialSecrets.serverSecret(QuicVersion.V2, DCID);
        assertEquals("0263db1782731bf4588e7e4d93b7463907cb8cd8200b5da55a8bd488eafc37c1",
                ByteArrays.toHexString(secret));
    }

    @Test
    public void testDifferentConnectionIdChangesSecret() {
        byte[] other = ByteArrays.toByteArray("8394c8f03e515709");
        assertFalse(java.util.Arrays.equals(InitialSecrets.clientSecret(QuicVersion.V1, DCID),
                InitialSecrets.clientSecret(QuicVersion.V1, other)));
    }

}
