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
 * Unit tests for LdapResultCode.
 */
public class LDAPResultCodeTest {

    @Test
    public void testSuccessCode() {
        assertEquals(0, LdapResultCode.SUCCESS.getCode());
        assertTrue(LdapResultCode.SUCCESS.isSuccess());
        assertEquals("success", LdapResultCode.SUCCESS.getName());
    }

    @Test
    public void testInvalidCredentialsCode() {
        assertEquals(49, LdapResultCode.INVALID_CREDENTIALS.getCode());
        assertFalse(LdapResultCode.INVALID_CREDENTIALS.isSuccess());
        assertEquals("invalidCredentials", LdapResultCode.INVALID_CREDENTIALS.getName());
    }

    @Test
    public void testFromCodeSuccess() {
        LdapResultCode code = LdapResultCode.fromCode(0);
        assertEquals(LdapResultCode.SUCCESS, code);
    }

    @Test
    public void testFromCodeOperationsError() {
        LdapResultCode code = LdapResultCode.fromCode(1);
        assertEquals(LdapResultCode.OPERATIONS_ERROR, code);
    }

    @Test
    public void testFromCodeProtocolError() {
        LdapResultCode code = LdapResultCode.fromCode(2);
        assertEquals(LdapResultCode.PROTOCOL_ERROR, code);
    }

    @Test
    public void testFromCodeTimeLimitExceeded() {
        LdapResultCode code = LdapResultCode.fromCode(3);
        assertEquals(LdapResultCode.TIME_LIMIT_EXCEEDED, code);
    }

    @Test
    public void testFromCodeSizeLimitExceeded() {
        LdapResultCode code = LdapResultCode.fromCode(4);
        assertEquals(LdapResultCode.SIZE_LIMIT_EXCEEDED, code);
    }

    @Test
    public void testFromCodeCompare() {
        assertEquals(LdapResultCode.COMPARE_FALSE, LdapResultCode.fromCode(5));
        assertEquals(LdapResultCode.COMPARE_TRUE, LdapResultCode.fromCode(6));
    }

    @Test
    public void testFromCodeAuthMethods() {
        assertEquals(LdapResultCode.AUTH_METHOD_NOT_SUPPORTED, LdapResultCode.fromCode(7));
        assertEquals(LdapResultCode.STRONGER_AUTH_REQUIRED, LdapResultCode.fromCode(8));
    }

    @Test
    public void testFromCodeReferral() {
        assertEquals(LdapResultCode.REFERRAL, LdapResultCode.fromCode(10));
    }

    @Test
    public void testFromCodeNoSuchAttribute() {
        assertEquals(LdapResultCode.NO_SUCH_ATTRIBUTE, LdapResultCode.fromCode(16));
    }

    @Test
    public void testFromCodeNoSuchObject() {
        assertEquals(LdapResultCode.NO_SUCH_OBJECT, LdapResultCode.fromCode(32));
    }

    @Test
    public void testFromCodeInvalidCredentials() {
        assertEquals(LdapResultCode.INVALID_CREDENTIALS, LdapResultCode.fromCode(49));
    }

    @Test
    public void testFromCodeInsufficientAccessRights() {
        assertEquals(LdapResultCode.INSUFFICIENT_ACCESS_RIGHTS, LdapResultCode.fromCode(50));
    }

    @Test
    public void testFromCodeBusy() {
        assertEquals(LdapResultCode.BUSY, LdapResultCode.fromCode(51));
    }

    @Test
    public void testFromCodeUnavailable() {
        assertEquals(LdapResultCode.UNAVAILABLE, LdapResultCode.fromCode(52));
    }

    @Test
    public void testFromCodeUnwillingToPerform() {
        assertEquals(LdapResultCode.UNWILLING_TO_PERFORM, LdapResultCode.fromCode(53));
    }

    @Test
    public void testFromCodeEntryAlreadyExists() {
        assertEquals(LdapResultCode.ENTRY_ALREADY_EXISTS, LdapResultCode.fromCode(68));
    }

    @Test
    public void testFromCodeOther() {
        assertEquals(LdapResultCode.OTHER, LdapResultCode.fromCode(80));
    }

    @Test
    public void testFromCodeUnknown() {
        // Unknown codes should return OTHER
        LdapResultCode code = LdapResultCode.fromCode(9999);
        assertEquals(LdapResultCode.OTHER, code);
    }

    @Test
    public void testFromCodeNegative() {
        // Invalid negative code should return OTHER
        LdapResultCode code = LdapResultCode.fromCode(-1);
        assertEquals(LdapResultCode.OTHER, code);
    }

    @Test
    public void testIsSuccessOnlyForZero() {
        for (LdapResultCode code : LdapResultCode.values()) {
            if (code.getCode() == 0) {
                assertTrue(code.isSuccess());
            } else {
                assertFalse(code.isSuccess());
            }
        }
    }

    @Test
    public void testToString() {
        String str = LdapResultCode.SUCCESS.toString();
        assertTrue(str.contains("success"));
        assertTrue(str.contains("0"));
        
        String str2 = LdapResultCode.INVALID_CREDENTIALS.toString();
        assertTrue(str2.contains("invalidCredentials"));
        assertTrue(str2.contains("49"));
    }

    @Test
    public void testAllCodesAreUnique() {
        LdapResultCode[] values = LdapResultCode.values();
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
        LdapResultCode[] values = LdapResultCode.values();
        for (int i = 0; i < values.length; i++) {
            for (int j = i + 1; j < values.length; j++) {
                assertNotEquals(
                    "Duplicate name: " + values[i] + " and " + values[j],
                    values[i].getName(), values[j].getName());
            }
        }
    }
}

