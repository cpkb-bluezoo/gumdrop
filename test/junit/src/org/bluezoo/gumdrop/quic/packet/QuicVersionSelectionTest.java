/*
 * QuicVersionSelectionTest.java
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

package org.bluezoo.gumdrop.quic.packet;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The version selection rules of RFC 9368: compatible version
 * negotiation (section 2.3), reaction to Version Negotiation packets
 * (section 2.1) and downgrade validation (section 4).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class QuicVersionSelectionTest {

    private static final int V1 = 1;
    private static final int V2 = 0x6b3343cf;
    private static final int GREASE = 0x1a2a3a4a;

    @Test
    public void testVersionsOneAndTwoAreMutuallyCompatible() {
        assertTrue(QuicVersion.V1.isCompatibleWith(QuicVersion.V2));
        assertTrue(QuicVersion.V2.isCompatibleWith(QuicVersion.V1));
        assertTrue(QuicVersion.V1.isCompatibleWith(QuicVersion.V1));
    }

    @Test
    public void testOriginalVersionIsTheOldestConfigured() {
        assertEquals(QuicVersion.V1, QuicVersion.originalVersion(new QuicVersion[] { QuicVersion.V2, QuicVersion.V1 }));
        assertEquals(QuicVersion.V2, QuicVersion.originalVersion(new QuicVersion[] { QuicVersion.V2 }));
    }

    @Test
    public void testAvailableVersionsListChosenAndCompatibleOnesInPreferenceOrder() {
        QuicVersion[] configured = { QuicVersion.V2, QuicVersion.V1 };
        assertArrayEquals(new int[] { V2, V1 }, QuicVersion.availableVersions(QuicVersion.V1, configured));
        // The Chosen Version is always present (RFC 9368 section 3).
        assertArrayEquals(new int[] { V1 },
                QuicVersion.availableVersions(QuicVersion.V1, new QuicVersion[] { QuicVersion.V1 }));
        assertArrayEquals(new int[] { V2, V1 },
                QuicVersion.availableVersions(QuicVersion.V1, new QuicVersion[] { QuicVersion.V2 }));
    }

    @Test
    public void testServerSelectsClientsMostPreferredAcceptableVersion() {
        QuicVersion[] acceptable = { QuicVersion.V1, QuicVersion.V2 };
        assertEquals(QuicVersion.V2, QuicVersion.selectCompatible(QuicVersion.V1, new int[] { GREASE, V2, V1 }, acceptable));
        assertEquals(QuicVersion.V1, QuicVersion.selectCompatible(QuicVersion.V1, new int[] { V1, V2 }, acceptable));
    }

    @Test
    public void testServerKeepsChosenVersionWhenNothingElseIsAcceptable() {
        QuicVersion[] v1Only = { QuicVersion.V1 };
        assertEquals(QuicVersion.V1, QuicVersion.selectCompatible(QuicVersion.V1, new int[] { V2, V1 }, v1Only));
        assertEquals(QuicVersion.V1, QuicVersion.selectCompatible(QuicVersion.V1, new int[0], v1Only));
    }

    @Test
    public void testClientReactsToVersionNegotiationWithItsOwnPreference() {
        QuicVersion[] preference = { QuicVersion.V2, QuicVersion.V1 };
        assertEquals(QuicVersion.V2, QuicVersion.selectFromOffer(preference, new int[] { V1, V2, GREASE }));
        assertEquals(QuicVersion.V1, QuicVersion.selectFromOffer(preference, new int[] { GREASE, V1 }));
        assertNull(QuicVersion.selectFromOffer(preference, new int[] { GREASE }));
    }

    @Test
    public void testDowngradeValidationAcceptsGenuineNegotiation() {
        QuicVersion[] preference = { QuicVersion.V2, QuicVersion.V1 };
        // Server only fully deploys v1: client would also have picked v1.
        assertTrue(QuicVersion.validatesNegotiation(preference, new int[] { V1 }, QuicVersion.V1));
        assertTrue(QuicVersion.validatesNegotiation(preference, new int[] { V2, V1 }, QuicVersion.V2));
    }

    @Test
    public void testDowngradeValidationRejectsForgedVersionNegotiation() {
        QuicVersion[] preference = { QuicVersion.V2, QuicVersion.V1 };
        // Attacker forged a VN listing only v1; the real server also offers v2.
        assertFalse(QuicVersion.validatesNegotiation(preference, new int[] { V2, V1 }, QuicVersion.V1));
        // RFC 9368 section 4: an empty Available Versions field after
        // reacting to Version Negotiation is always a failure.
        assertFalse(QuicVersion.validatesNegotiation(preference, new int[0], QuicVersion.V1));
    }
}
