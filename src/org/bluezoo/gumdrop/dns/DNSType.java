/*
 * DNSType.java
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

package org.bluezoo.gumdrop.dns;

/**
 * DNS resource record types.
 *
 * <p>Common types are fully implemented. Additional types that may be
 * added in future versions:
 * <ul>
 * <li>HINFO (13) - Host information</li>
 * <li>RP (17) - Responsible person</li>
 * <li>AFSDB (18) - AFS database</li>
 * <li>SIG (24) - Signature (obsolete, see RRSIG)</li>
 * <li>KEY (25) - Key (obsolete, see DNSKEY)</li>
 * <li>LOC (29) - Location</li>
 * <li>SRV (33) - Service locator</li>
 * <li>NAPTR (35) - Naming authority pointer</li>
 * <li>KX (36) - Key exchanger</li>
 * <li>CERT (37) - Certificate</li>
 * <li>DNAME (39) - Delegation name</li>
 * <li>APL (42) - Address prefix list</li>
 * <li>DS (43) - Delegation signer</li>
 * <li>SSHFP (44) - SSH fingerprint</li>
 * <li>IPSECKEY (45) - IPsec key</li>
 * <li>RRSIG (46) - DNSSEC signature</li>
 * <li>NSEC (47) - Next secure record</li>
 * <li>DNSKEY (48) - DNS key</li>
 * <li>DHCID (49) - DHCP identifier</li>
 * <li>NSEC3 (50) - NSEC version 3</li>
 * <li>NSEC3PARAM (51) - NSEC3 parameters</li>
 * <li>TLSA (52) - TLS association</li>
 * <li>SMIMEA (53) - S/MIME association</li>
 * <li>HIP (55) - Host identity protocol</li>
 * <li>CDS (59) - Child DS</li>
 * <li>CDNSKEY (60) - Child DNSKEY</li>
 * <li>OPENPGPKEY (61) - OpenPGP key</li>
 * <li>CSYNC (62) - Child-to-parent sync</li>
 * <li>ZONEMD (63) - Zone message digest</li>
 * <li>SVCB (64) - Service binding</li>
 * <li>HTTPS (65) - HTTPS binding</li>
 * <li>SPF (99) - Sender policy framework (obsolete, use TXT)</li>
 * <li>CAA (257) - Certification authority authorization</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public enum DNSType {

    /**
     * IPv4 address record.
     */
    A(1),

    /**
     * Authoritative name server.
     */
    NS(2),

    /**
     * Canonical name (alias).
     */
    CNAME(5),

    /**
     * Start of authority.
     */
    SOA(6),

    /**
     * Domain name pointer (reverse DNS).
     */
    PTR(12),

    /**
     * Mail exchange.
     */
    MX(15),

    /**
     * Text record.
     */
    TXT(16),

    /**
     * IPv6 address record.
     */
    AAAA(28),

    /**
     * Option (EDNS).
     */
    OPT(41),

    /**
     * All records (query only, not stored).
     */
    ANY(255);

    private final int value;

    DNSType(int value) {
        this.value = value;
    }

    /**
     * Returns the numeric value of this type.
     *
     * @return the type value
     */
    public int getValue() {
        return value;
    }

    /**
     * Returns the DNSType for the given numeric value.
     *
     * @param value the type value
     * @return the DNSType, or null if unknown
     */
    public static DNSType fromValue(int value) {
        for (DNSType type : values()) {
            if (type.value == value) {
                return type;
            }
        }
        return null;
    }

}

