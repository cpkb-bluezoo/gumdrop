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
 * Unit tests for SpfResult, DkimResult, DmarcResult, and DmarcPolicy enums.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SPFResultTest {

    // -- SpfResult Tests --

    @Test
    public void testSPFResultValues() {
        // Verify all expected values exist (PASS, FAIL, SOFTFAIL, NEUTRAL, NONE, TEMPERROR, PERMERROR)
        assertEquals(7, SpfResult.values().length);
        assertNotNull(SpfResult.NONE);
        assertNotNull(SpfResult.NEUTRAL);
        assertNotNull(SpfResult.PASS);
        assertNotNull(SpfResult.FAIL);
        assertNotNull(SpfResult.SOFTFAIL);
        assertNotNull(SpfResult.TEMPERROR);
        assertNotNull(SpfResult.PERMERROR);
    }

    @Test
    public void testSPFResultValueOf() {
        assertEquals(SpfResult.PASS, SpfResult.valueOf("PASS"));
        assertEquals(SpfResult.FAIL, SpfResult.valueOf("FAIL"));
        assertEquals(SpfResult.SOFTFAIL, SpfResult.valueOf("SOFTFAIL"));
        assertEquals(SpfResult.NEUTRAL, SpfResult.valueOf("NEUTRAL"));
        assertEquals(SpfResult.NONE, SpfResult.valueOf("NONE"));
    }

    // -- DkimResult Tests --

    @Test
    public void testDKIMResultValues() {
        // Verify all expected values exist
        assertNotNull(DkimResult.NONE);
        assertNotNull(DkimResult.PASS);
        assertNotNull(DkimResult.FAIL);
        assertNotNull(DkimResult.TEMPERROR);
        assertNotNull(DkimResult.PERMERROR);
    }

    @Test
    public void testDKIMResultValueOf() {
        assertEquals(DkimResult.PASS, DkimResult.valueOf("PASS"));
        assertEquals(DkimResult.FAIL, DkimResult.valueOf("FAIL"));
        assertEquals(DkimResult.NONE, DkimResult.valueOf("NONE"));
    }

    // -- DmarcResult Tests --

    @Test
    public void testDMARCResultValues() {
        assertNotNull(DmarcResult.NONE);
        assertNotNull(DmarcResult.PASS);
        assertNotNull(DmarcResult.FAIL);
        assertNotNull(DmarcResult.TEMPERROR);
        assertNotNull(DmarcResult.PERMERROR);
    }

    @Test
    public void testDMARCResultValueOf() {
        assertEquals(DmarcResult.PASS, DmarcResult.valueOf("PASS"));
        assertEquals(DmarcResult.FAIL, DmarcResult.valueOf("FAIL"));
        assertEquals(DmarcResult.NONE, DmarcResult.valueOf("NONE"));
    }

    // -- DmarcPolicy Tests --

    @Test
    public void testDMARCPolicyValues() {
        assertNotNull(DmarcPolicy.NONE);
        assertNotNull(DmarcPolicy.QUARANTINE);
        assertNotNull(DmarcPolicy.REJECT);
    }

    @Test
    public void testDMARCPolicyValueOf() {
        assertEquals(DmarcPolicy.NONE, DmarcPolicy.valueOf("NONE"));
        assertEquals(DmarcPolicy.QUARANTINE, DmarcPolicy.valueOf("QUARANTINE"));
        assertEquals(DmarcPolicy.REJECT, DmarcPolicy.valueOf("REJECT"));
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

