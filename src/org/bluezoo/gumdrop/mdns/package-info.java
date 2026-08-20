/*
 * package-info.java
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

/**
 * Multicast DNS (RFC 6762) and DNS-SD (RFC 6763) support for Gumdrop.
 *
 * <p>This package lets a Gumdrop instance:
 * <ul>
 * <li>Claim and defend a {@code .local} hostname on the local network,
 *     answering other hosts' queries for it</li>
 * <li>Query for other hosts' records and read back what it learns</li>
 * <li>Auto-advertise its own configured services (mail, web, DNS,
 *     etc.) so they show up in Bonjour/Avahi/{@code dns-sd -B}
 *     browsers</li>
 * </ul>
 *
 * <h2>Architecture</h2>
 *
 * <ul>
 *   <li>{@link org.bluezoo.gumdrop.mdns.MDNSService} &ndash; owns
 *       configuration, the probing/announcing state machine for this
 *       instance's own hostname, and the public
 *       {@link org.bluezoo.gumdrop.mdns.MDNSService#query query}/{@link
 *       org.bluezoo.gumdrop.mdns.MDNSService#lookup lookup} API</li>
 *   <li>{@link org.bluezoo.gumdrop.mdns.MDNSListener} &ndash; the UDP
 *       multicast transport: binds port 5353 and joins the mDNS group
 *       on every eligible network interface</li>
 *   <li>{@link org.bluezoo.gumdrop.mdns.MDNSCache} &ndash; the
 *       querier-side record cache, with RFC 6762 section 5.2 active
 *       refresh and section 10.2 cache-flush semantics</li>
 *   <li>{@link org.bluezoo.gumdrop.mdns.DNSSDAdvertiser} &ndash;
 *       builds RFC 6763 PTR/SRV/TXT records for this Gumdrop instance's
 *       own configured services</li>
 * </ul>
 *
 * <p>Wire-format support for mDNS's two repurposed bits (the QCLASS
 * "QU" bit and the RR CLASS "cache-flush" bit) lives in the sibling
 * {@link org.bluezoo.gumdrop.dns} package, on
 * {@link org.bluezoo.gumdrop.dns.DNSQuestion} and
 * {@link org.bluezoo.gumdrop.dns.DNSResourceRecord} respectively, since
 * mDNS otherwise reuses the standard DNS message format as-is.
 *
 * <h2>Usage</h2>
 *
 * <p>The simplest configuration just claims a hostname:
 * <pre>{@code
 * <service class="org.bluezoo.gumdrop.mdns.MDNSService">
 *   <property name="hostname" value="gumdrop"/>
 *   <listener class="org.bluezoo.gumdrop.mdns.MDNSListener"/>
 * </service>
 * }</pre>
 *
 * <p>If {@code hostname} is omitted, the JVM's local hostname (domain
 * suffix stripped) is used, falling back to {@code "gumdrop"}. If
 * another host already holds the name, or wins a simultaneous-probe
 * tie-break, this instance renames itself (e.g. {@code gumdrop-2}) and
 * re-probes automatically &mdash; check
 * {@link org.bluezoo.gumdrop.mdns.MDNSService#getCurrentName} after
 * {@link org.bluezoo.gumdrop.mdns.MDNSService#isAnnounced} to find out
 * what name was actually claimed.
 *
 * <h2>Querying other hosts</h2>
 *
 * <pre>{@code
 * mdnsService.query("printer.local", DNSType.A);
 * // ... answers arrive asynchronously as other hosts respond ...
 * List<DNSResourceRecord> answers = mdnsService.lookup("printer.local", DNSType.A);
 * }</pre>
 *
 * <h2>DNS-SD auto-advertisement</h2>
 *
 * <p>Enabled by default once a hostname is successfully announced. It
 * reads {@code Gumdrop.getInstance().getServices()} at that point, so
 * <strong>the {@code mdns} service must be declared last</strong> in
 * {@code gumdroprc.xml} &mdash; services start in document order, and
 * any service started after {@code mdns} won't have its listener ports
 * picked up:
 * <pre>{@code
 * <property name="advertise-services" value="true"/>  <!-- default -->
 * <property name="excluded-services" value="dns"/>     <!-- space-separated -->
 * }</pre>
 *
 * <p>Only a deliberately conservative set of well-established DNS-SD
 * service types is advertised (see
 * {@link org.bluezoo.gumdrop.mdns.DNSSDAdvertiser} for the exact list);
 * a listener whose {@code getDescription()} isn't in that list is
 * silently skipped, not treated as an error.
 *
 * <h2>Known limitations</h2>
 *
 * <ul>
 * <li>IPv4 only &ndash; no {@code ff02::fb} IPv6 multicast group
 *     support yet</li>
 * <li>RFC 6762 section 8.2's simultaneous-probe tie-break compares a
 *     single representative address rather than the full RRset
 *     ordering algorithm (a real host usually only advertises one
 *     address, so this covers the common case)</li>
 * <li>DNS-SD's SRV/TXT records aren't put through mDNS probing before
 *     use, unlike the host's own address record(s)</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see org.bluezoo.gumdrop.mdns.MDNSService
 * @see org.bluezoo.gumdrop.mdns.MDNSListener
 * @see org.bluezoo.gumdrop.mdns.MDNSCache
 * @see org.bluezoo.gumdrop.mdns.DNSSDAdvertiser
 * @see org.bluezoo.gumdrop.dns.DNSService
 */
package org.bluezoo.gumdrop.mdns;
