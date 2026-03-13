/*
 * DNSSECStatus.java
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

package org.bluezoo.gumdrop.dns;

/**
 * DNSSEC validation status for a DNS response.
 * RFC 4035 section 4.3 defines the possible states a validating
 * resolver may assign to response data.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public enum DNSSECStatus {

    /**
     * The response was validated via a chain of trust to a
     * configured trust anchor. All signatures verified.
     * RFC 4035 section 4.3: "secure" data.
     */
    SECURE,

    /**
     * The zone is provably unsigned: the delegation from the
     * parent lacks a DS record, or no trust anchor is configured.
     * RFC 4035 section 4.3: "insecure" data.
     */
    INSECURE,

    /**
     * Validation failed: a signature did not verify, the chain
     * of trust is broken, a signature has expired, or mandatory
     * records are missing.
     * RFC 4035 section 4.3: "bogus" data.
     */
    BOGUS,

    /**
     * The validation status could not be determined, for example
     * because required DNSKEY or DS records could not be fetched
     * or an unsupported algorithm was encountered.
     * RFC 4035 section 4.3: indeterminate state.
     */
    INDETERMINATE

}
