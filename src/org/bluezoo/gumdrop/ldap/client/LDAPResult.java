/*
 * LDAPResult.java
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

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Represents an LDAP operation result.
 *
 * <p>This is the base result type returned by LDAP operations such as
 * bind, search, modify, add, delete, etc.</p>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class LDAPResult {

    private final LDAPResultCode resultCode;
    private final String matchedDN;
    private final String diagnosticMessage;
    private final List<String> referrals;

    /**
     * Creates a new LDAP result.
     *
     * @param resultCode the result code
     * @param matchedDN the matched DN (may be empty)
     * @param diagnosticMessage the diagnostic message (may be empty)
     */
    public LDAPResult(LDAPResultCode resultCode, String matchedDN, 
                      String diagnosticMessage) {
        this(resultCode, matchedDN, diagnosticMessage, null);
    }

    /**
     * Creates a new LDAP result with referrals.
     *
     * @param resultCode the result code
     * @param matchedDN the matched DN (may be empty)
     * @param diagnosticMessage the diagnostic message (may be empty)
     * @param referrals the referral URLs (may be null)
     */
    public LDAPResult(LDAPResultCode resultCode, String matchedDN,
                      String diagnosticMessage, List<String> referrals) {
        this.resultCode = resultCode;
        this.matchedDN = matchedDN != null ? matchedDN : "";
        this.diagnosticMessage = diagnosticMessage != null ? diagnosticMessage : "";
        this.referrals = referrals != null ? 
                Collections.unmodifiableList(Arrays.asList(referrals.toArray(new String[0]))) :
                Collections.<String>emptyList();
    }

    /**
     * Returns the result code.
     *
     * @return the result code
     */
    public LDAPResultCode getResultCode() {
        return resultCode;
    }

    /**
     * Returns whether the operation was successful.
     *
     * @return true if the result code is SUCCESS
     */
    public boolean isSuccess() {
        return resultCode.isSuccess();
    }

    /**
     * Returns the matched DN.
     *
     * <p>For operations that fail with NO_SUCH_OBJECT, this contains
     * the portion of the DN that was matched.</p>
     *
     * @return the matched DN, may be empty
     */
    public String getMatchedDN() {
        return matchedDN;
    }

    /**
     * Returns the diagnostic message.
     *
     * <p>This may contain additional information about the result,
     * especially for error conditions.</p>
     *
     * @return the diagnostic message, may be empty
     */
    public String getDiagnosticMessage() {
        return diagnosticMessage;
    }

    /**
     * Returns whether referrals were returned.
     *
     * @return true if referrals exist
     */
    public boolean hasReferrals() {
        return !referrals.isEmpty();
    }

    /**
     * Returns the referral URLs.
     *
     * @return unmodifiable list of referral URLs
     */
    public List<String> getReferrals() {
        return referrals;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("LDAPResult[").append(resultCode);
        if (!matchedDN.isEmpty()) {
            sb.append(", matchedDN=").append(matchedDN);
        }
        if (!diagnosticMessage.isEmpty()) {
            sb.append(", message=").append(diagnosticMessage);
        }
        if (!referrals.isEmpty()) {
            sb.append(", referrals=").append(referrals);
        }
        sb.append("]");
        return sb.toString();
    }
}

