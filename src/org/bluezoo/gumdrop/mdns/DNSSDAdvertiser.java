/*
 * DNSSDAdvertiser.java
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

package org.bluezoo.gumdrop.mdns;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bluezoo.gumdrop.Listener;
import org.bluezoo.gumdrop.Service;
import org.bluezoo.gumdrop.dns.DNSResourceRecord;

/**
 * Builds DNS-SD (RFC 6763) service records for gumdrop's own configured
 * services, so they show up in Bonjour/Avahi/{@code dns-sd -B} browsers
 * on the local network.
 *
 * <p>Takes the list of {@link Service}s to advertise as a plain
 * parameter rather than reading {@code Gumdrop.getInstance().getServices()}
 * itself, so it's independently unit-testable against fake services
 * without needing a running {@code Gumdrop} instance &mdash; the same
 * reasoning behind {@link MDNSCache} taking its scheduling capability
 * through a small interface instead of reaching into {@link MDNSListener}
 * directly. {@link MDNSService} is what supplies the real service list.
 *
 * <p>Only {@link Listener#getDescription()} values with a well-known,
 * long-established DNS-SD service type are advertised (see {@link
 * #SERVICE_TYPES}); this is a deliberately conservative subset of what
 * gumdrop can serve, not the full IANA service type registry, so it
 * doesn't assert a mapping it isn't confident is correct. Anything else
 * &mdash; including internal-only listeners like {@code health} or
 * {@code cluster}, which were never in this table to begin with &mdash;
 * is silently skipped rather than treated as an error, so adding a new
 * protocol listener elsewhere in gumdrop never needs a matching change
 * here just to avoid breaking.
 *
 * <p><strong>Simplification:</strong> unlike the host's own A record(s)
 * (RFC 6762 section 8.1), the SRV/TXT records this class produces are
 * not put through mDNS probing before use, even though RFC 6763 section
 * 8.1 treats them as ordinary unique records subject to the same
 * conflict-detection rules. In practice a collision would require
 * another host on the same network coincidentally sharing gumdrop's
 * already-probed-and-conflict-resolved hostname <em>and</em> advertising
 * the same service type &mdash; a much narrower risk than the hostname
 * collision probing actually protects against.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see MDNSService
 */
final class DNSSDAdvertiser {

    /** RFC 6763 section 9: the meta-query name used to browse all advertised service types. */
    static final String DNS_SD_META_QUERY_NAME = "_services._dns-sd._udp.local";

    /**
     * {@link Listener#getDescription()} value to DNS-SD service type
     * (without the trailing {@code .local}). Deliberately limited to
     * long-established, unambiguous mappings -- see the class
     * documentation.
     */
    private static final Map<String, String> SERVICE_TYPES = buildServiceTypes();

    private static Map<String, String> buildServiceTypes() {
        Map<String, String> m = new HashMap<String, String>();
        m.put("http", "_http._tcp");
        m.put("https", "_https._tcp");
        m.put("ftp", "_ftp._tcp");
        m.put("imap", "_imap._tcp");
        m.put("imaps", "_imaps._tcp");
        m.put("pop3", "_pop3._tcp");
        m.put("pop3s", "_pop3s._tcp");
        m.put("smtp", "_smtp._tcp");
        return Collections.unmodifiableMap(m);
    }

    private DNSSDAdvertiser() {
    }

    /**
     * Builds the PTR/SRV/TXT records advertising every eligible listener
     * across the given services, plus the section 9 meta-query PTRs for
     * each distinct service type advertised.
     *
     * @param services the services to advertise (typically {@code
     *                 Gumdrop.getInstance().getServices()})
     * @param hostLabel the mDNS host label actually claimed after
     *                  probing, without the {@code .local} suffix (e.g.
     *                  {@code "gumdrop"} or, after a rename, {@code
     *                  "gumdrop-2"}) -- both the SRV target and the
     *                  service instance name are derived from this, so
     *                  they always match whatever name won probing
     * @param ttl the TTL to use for every generated record
     * @param excludedDescriptions {@link Listener#getDescription()}
     *                             values to skip even if they have a
     *                             known mapping (e.g. to avoid
     *                             advertising gumdrop's own DNS
     *                             resolver)
     * @return the generated records, empty if nothing was eligible
     */
    static List<DNSResourceRecord> buildRecords(List<Service> services, String hostLabel,
                                                 int ttl, Set<String> excludedDescriptions) {
        List<DNSResourceRecord> records = new ArrayList<DNSResourceRecord>();
        Set<String> serviceTypesAdvertised = new LinkedHashSet<String>();
        String hostTarget = hostLabel + ".local";

        for (int s = 0; s < services.size(); s++) {
            @SuppressWarnings("rawtypes")
            List listeners = services.get(s).getListeners();
            for (int l = 0; l < listeners.size(); l++) {
                Object item = listeners.get(l);
                if (!(item instanceof Listener)) {
                    continue;
                }
                Listener listener = (Listener) item;
                String description = listener.getDescription();
                if (excludedDescriptions.contains(description)) {
                    continue;
                }
                String serviceType = SERVICE_TYPES.get(description);
                int port = listener.getPort();
                if (serviceType == null || port <= 0) {
                    continue;
                }

                String serviceTypeLocal = serviceType + ".local";
                String instanceName = hostLabel + "." + serviceTypeLocal;

                // RFC 6762 section 10.2 / RFC 6763 section 8.1: PTR
                // records enumerating a service type are shared records
                // and must never carry the cache-flush bit, but the
                // SRV/TXT records for one specific instance are unique
                // records and should.
                records.add(DNSResourceRecord.ptr(serviceTypeLocal, ttl, instanceName));
                records.add(DNSResourceRecord.srv(instanceName, ttl, 0, 0, port, hostTarget)
                        .withCacheFlush());
                // RFC 6763 section 6.1: a TXT record's RDATA must never be
                // zero-length; a single empty string means "no attributes".
                records.add(DNSResourceRecord.txt(instanceName, ttl,
                        Collections.singletonList("")).withCacheFlush());
                serviceTypesAdvertised.add(serviceTypeLocal);
            }
        }

        for (String serviceTypeLocal : serviceTypesAdvertised) {
            records.add(DNSResourceRecord.ptr(DNS_SD_META_QUERY_NAME, ttl, serviceTypeLocal));
        }
        return records;
    }

}
