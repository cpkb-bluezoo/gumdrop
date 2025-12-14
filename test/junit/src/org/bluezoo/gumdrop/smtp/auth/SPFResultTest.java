/*
 * SPFResultTest.java
 * Copyright (C) 2025 Chris Burdess
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

package org.bluezoo.gumdrop.smtp.auth;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Unit tests for SPFResult, DKIMResult, DMARCResult, and DMARCPolicy enums.
 */
public class SPFResultTest {

    // -- SPFResult Tests --

    @Test
    public void testSPFResultValues() {
        // Verify all expected values exist (PASS, FAIL, SOFTFAIL, NEUTRAL, NONE, TEMPERROR, PERMERROR)
        assertEquals(7, SPFResult.values().length);
        assertNotNull(SPFResult.NONE);
        assertNotNull(SPFResult.NEUTRAL);
        assertNotNull(SPFResult.PASS);
        assertNotNull(SPFResult.FAIL);
        assertNotNull(SPFResult.SOFTFAIL);
        assertNotNull(SPFResult.TEMPERROR);
        assertNotNull(SPFResult.PERMERROR);
    }

    @Test
    public void testSPFResultValueOf() {
        assertEquals(SPFResult.PASS, SPFResult.valueOf("PASS"));
        assertEquals(SPFResult.FAIL, SPFResult.valueOf("FAIL"));
        assertEquals(SPFResult.SOFTFAIL, SPFResult.valueOf("SOFTFAIL"));
        assertEquals(SPFResult.NEUTRAL, SPFResult.valueOf("NEUTRAL"));
        assertEquals(SPFResult.NONE, SPFResult.valueOf("NONE"));
    }

    // -- DKIMResult Tests --

    @Test
    public void testDKIMResultValues() {
        // Verify all expected values exist
        assertNotNull(DKIMResult.NONE);
        assertNotNull(DKIMResult.PASS);
        assertNotNull(DKIMResult.FAIL);
        assertNotNull(DKIMResult.TEMPERROR);
        assertNotNull(DKIMResult.PERMERROR);
    }

    @Test
    public void testDKIMResultValueOf() {
        assertEquals(DKIMResult.PASS, DKIMResult.valueOf("PASS"));
        assertEquals(DKIMResult.FAIL, DKIMResult.valueOf("FAIL"));
        assertEquals(DKIMResult.NONE, DKIMResult.valueOf("NONE"));
    }

    // -- DMARCResult Tests --

    @Test
    public void testDMARCResultValues() {
        assertNotNull(DMARCResult.NONE);
        assertNotNull(DMARCResult.PASS);
        assertNotNull(DMARCResult.FAIL);
        assertNotNull(DMARCResult.TEMPERROR);
        assertNotNull(DMARCResult.PERMERROR);
    }

    @Test
    public void testDMARCResultValueOf() {
        assertEquals(DMARCResult.PASS, DMARCResult.valueOf("PASS"));
        assertEquals(DMARCResult.FAIL, DMARCResult.valueOf("FAIL"));
        assertEquals(DMARCResult.NONE, DMARCResult.valueOf("NONE"));
    }

    // -- DMARCPolicy Tests --

    @Test
    public void testDMARCPolicyValues() {
        assertNotNull(DMARCPolicy.NONE);
        assertNotNull(DMARCPolicy.QUARANTINE);
        assertNotNull(DMARCPolicy.REJECT);
    }

    @Test
    public void testDMARCPolicyValueOf() {
        assertEquals(DMARCPolicy.NONE, DMARCPolicy.valueOf("NONE"));
        assertEquals(DMARCPolicy.QUARANTINE, DMARCPolicy.valueOf("QUARANTINE"));
        assertEquals(DMARCPolicy.REJECT, DMARCPolicy.valueOf("REJECT"));
    }

    // -- AuthVerdict Tests --

    @Test
    public void testAuthVerdictValues() {
        assertEquals(4, AuthVerdict.values().length);
        assertNotNull(AuthVerdict.PASS);
        assertNotNull(AuthVerdict.REJECT);
        assertNotNull(AuthVerdict.QUARANTINE);
        assertNotNull(AuthVerdict.NONE);
    }

    @Test
    public void testAuthVerdictValueOf() {
        assertEquals(AuthVerdict.PASS, AuthVerdict.valueOf("PASS"));
        assertEquals(AuthVerdict.REJECT, AuthVerdict.valueOf("REJECT"));
        assertEquals(AuthVerdict.QUARANTINE, AuthVerdict.valueOf("QUARANTINE"));
        assertEquals(AuthVerdict.NONE, AuthVerdict.valueOf("NONE"));
    }

}

