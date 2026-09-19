/*
 * DnsNsecProofIngester.java
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

import org.bluezoo.gumdrop.dns.client.DnsBailiwick;
import org.bluezoo.gumdrop.dns.client.DnssecValidator;

import java.util.List;

/**
 * Ingests DNSSEC-validated denial proofs into {@link DnsNsecProofCache} using
 * event-driven section dispatch (no wire parsing here).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class DnsNsecProofIngester {

    private DnsNsecProofIngester() {
    }

    /**
     * Stores NSEC/NSEC3 proofs from a validated negative response.
     *
     * @param questionName the QNAME that was validated
     * @param response the upstream response (must already be SECURE)
     * @param cache the proof cache
     */
    public static void ingestSecureNegative(String questionName,
                                            DnsMessage response,
                                            DnsNsecProofCache cache) {
        if (cache == null || response == null || questionName == null) {
            return;
        }
        List<DnsResourceRecord> authorities = response.getAuthorities();
        if (authorities.isEmpty()) {
            return;
        }
        List<DnsResourceRecord> nsec =
                DnssecValidator.filterByType(authorities, DnsType.NSEC);
        List<DnsResourceRecord> nsec3 =
                DnssecValidator.filterByType(authorities, DnsType.NSEC3);
        if (nsec.isEmpty() && nsec3.isEmpty()) {
            return;
        }
        List<DnsResourceRecord> rrsigs;
        int coveredType;
        if (!nsec.isEmpty()) {
            coveredType = DnsType.NSEC.getValue();
        } else {
            coveredType = DnsType.NSEC3.getValue();
        }
        rrsigs = DnssecValidator.findRRSIGs(authorities, coveredType);
        if (rrsigs.isEmpty()) {
            return;
        }
        String signerZone = rrsigs.get(0).getRRSIGSignerName();
        List<DnsResourceRecord> inBailiwick =
                DnsBailiwick.filterAuthoritiesInBailiwick(
                        questionName, authorities);
        cache.ingestAuthoritySection(signerZone, inBailiwick);
    }
}
