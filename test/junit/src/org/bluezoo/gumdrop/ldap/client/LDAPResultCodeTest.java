/*
 * LDAPResultCodeTest.java
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

package org.bluezoo.gumdrop.ldap.client;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Unit tests for LDAPResultCode.
 */
public class LDAPResultCodeTest {

    @Test
    public void testSuccessCode() {
        assertEquals(0, LDAPResultCode.SUCCESS.getCode());
        assertTrue(LDAPResultCode.SUCCESS.isSuccess());
        assertEquals("success", LDAPResultCode.SUCCESS.getName());
    }

    @Test
    public void testInvalidCredentialsCode() {
        assertEquals(49, LDAPResultCode.INVALID_CREDENTIALS.getCode());
        assertFalse(LDAPResultCode.INVALID_CREDENTIALS.isSuccess());
        assertEquals("invalidCredentials", LDAPResultCode.INVALID_CREDENTIALS.getName());
    }

    @Test
    public void testFromCodeSuccess() {
        LDAPResultCode code = LDAPResultCode.fromCode(0);
        assertEquals(LDAPResultCode.SUCCESS, code);
    }

    @Test
    public void testFromCodeOperationsError() {
        LDAPResultCode code = LDAPResultCode.fromCode(1);
        assertEquals(LDAPResultCode.OPERATIONS_ERROR, code);
    }

    @Test
    public void testFromCodeProtocolError() {
        LDAPResultCode code = LDAPResultCode.fromCode(2);
        assertEquals(LDAPResultCode.PROTOCOL_ERROR, code);
    }

    @Test
    public void testFromCodeTimeLimitExceeded() {
        LDAPResultCode code = LDAPResultCode.fromCode(3);
        assertEquals(LDAPResultCode.TIME_LIMIT_EXCEEDED, code);
    }

    @Test
    public void testFromCodeSizeLimitExceeded() {
        LDAPResultCode code = LDAPResultCode.fromCode(4);
        assertEquals(LDAPResultCode.SIZE_LIMIT_EXCEEDED, code);
    }

    @Test
    public void testFromCodeCompare() {
        assertEquals(LDAPResultCode.COMPARE_FALSE, LDAPResultCode.fromCode(5));
        assertEquals(LDAPResultCode.COMPARE_TRUE, LDAPResultCode.fromCode(6));
    }

    @Test
    public void testFromCodeAuthMethods() {
        assertEquals(LDAPResultCode.AUTH_METHOD_NOT_SUPPORTED, LDAPResultCode.fromCode(7));
        assertEquals(LDAPResultCode.STRONGER_AUTH_REQUIRED, LDAPResultCode.fromCode(8));
    }

    @Test
    public void testFromCodeReferral() {
        assertEquals(LDAPResultCode.REFERRAL, LDAPResultCode.fromCode(10));
    }

    @Test
    public void testFromCodeNoSuchAttribute() {
        assertEquals(LDAPResultCode.NO_SUCH_ATTRIBUTE, LDAPResultCode.fromCode(16));
    }

    @Test
    public void testFromCodeNoSuchObject() {
        assertEquals(LDAPResultCode.NO_SUCH_OBJECT, LDAPResultCode.fromCode(32));
    }

    @Test
    public void testFromCodeInvalidCredentials() {
        assertEquals(LDAPResultCode.INVALID_CREDENTIALS, LDAPResultCode.fromCode(49));
    }

    @Test
    public void testFromCodeInsufficientAccessRights() {
        assertEquals(LDAPResultCode.INSUFFICIENT_ACCESS_RIGHTS, LDAPResultCode.fromCode(50));
    }

    @Test
    public void testFromCodeBusy() {
        assertEquals(LDAPResultCode.BUSY, LDAPResultCode.fromCode(51));
    }

    @Test
    public void testFromCodeUnavailable() {
        assertEquals(LDAPResultCode.UNAVAILABLE, LDAPResultCode.fromCode(52));
    }

    @Test
    public void testFromCodeUnwillingToPerform() {
        assertEquals(LDAPResultCode.UNWILLING_TO_PERFORM, LDAPResultCode.fromCode(53));
    }

    @Test
    public void testFromCodeEntryAlreadyExists() {
        assertEquals(LDAPResultCode.ENTRY_ALREADY_EXISTS, LDAPResultCode.fromCode(68));
    }

    @Test
    public void testFromCodeOther() {
        assertEquals(LDAPResultCode.OTHER, LDAPResultCode.fromCode(80));
    }

    @Test
    public void testFromCodeUnknown() {
        // Unknown codes should return OTHER
        LDAPResultCode code = LDAPResultCode.fromCode(9999);
        assertEquals(LDAPResultCode.OTHER, code);
    }

    @Test
    public void testFromCodeNegative() {
        // Invalid negative code should return OTHER
        LDAPResultCode code = LDAPResultCode.fromCode(-1);
        assertEquals(LDAPResultCode.OTHER, code);
    }

    @Test
    public void testIsSuccessOnlyForZero() {
        for (LDAPResultCode code : LDAPResultCode.values()) {
            if (code.getCode() == 0) {
                assertTrue(code.isSuccess());
            } else {
                assertFalse(code.isSuccess());
            }
        }
    }

    @Test
    public void testToString() {
        String str = LDAPResultCode.SUCCESS.toString();
        assertTrue(str.contains("success"));
        assertTrue(str.contains("0"));
        
        String str2 = LDAPResultCode.INVALID_CREDENTIALS.toString();
        assertTrue(str2.contains("invalidCredentials"));
        assertTrue(str2.contains("49"));
    }

    @Test
    public void testAllCodesAreUnique() {
        LDAPResultCode[] values = LDAPResultCode.values();
        for (int i = 0; i < values.length; i++) {
            for (int j = i + 1; j < values.length; j++) {
                assertNotEquals(
                    "Duplicate code: " + values[i] + " and " + values[j],
                    values[i].getCode(), values[j].getCode());
            }
        }
    }

    @Test
    public void testAllNamesAreUnique() {
        LDAPResultCode[] values = LDAPResultCode.values();
        for (int i = 0; i < values.length; i++) {
            for (int j = i + 1; j < values.length; j++) {
                assertNotEquals(
                    "Duplicate name: " + values[i] + " and " + values[j],
                    values[i].getName(), values[j].getName());
            }
        }
    }
}

