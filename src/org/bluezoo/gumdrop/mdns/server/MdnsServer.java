/*
 * MdnsServer.java
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

package org.bluezoo.gumdrop.mdns.server;

import org.bluezoo.gumdrop.mdns.DnssdAdvertiser;
import org.bluezoo.gumdrop.mdns.MdnsCache;
import org.bluezoo.gumdrop.mdns.MdnsListener;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.Listener;
import org.bluezoo.gumdrop.Server;
import org.bluezoo.gumdrop.dns.DnsClass;
import org.bluezoo.gumdrop.dns.DnsFormatException;
import org.bluezoo.gumdrop.dns.DnsMessage;
import org.bluezoo.gumdrop.dns.DnsQuestion;
import org.bluezoo.gumdrop.dns.DnsResourceRecord;
import org.bluezoo.gumdrop.dns.DnsType;

/**
 * A multicast DNS responder and querier (RFC 6762).
 *
 * <p>On {@link #start()}, this service probes for the exclusive right
 * to use its configured hostname on the local network (RFC 6762
 * section 8.1), renaming itself (e.g. {@code gumdrop-2.local}) and
 * re-probing if another host already holds the name or wins a
 * simultaneous-probe tie-break (section 8.2). Once probing succeeds it
 * announces its address records (section 8.3) and answers matching
 * queries from other hosts until {@link #stop()}, when it sends a
 * "goodbye" packet (section 10.1) withdrawing its records.
 *
 * <p>It can also query for other hosts' records via {@link #query} and
 * read them back via {@link #lookup}: answers are kept in an
 * {@link MdnsCache} that actively re-queries each record before it
 * expires (section 5.2), so a lookup stays populated without the
 * caller needing to re-query.
 *
 * <p>DNS-SD (RFC 6763) service records are not yet published (a
 * separate, later phase).
 *
 * <p>All mutable state here (probe/announce state, the current name,
 * pending timer, the record cache) is touched only from
 * {@link #handleDatagram} and timer callbacks, both of which are
 * always invoked on the owning {@link MdnsListener}'s single transport
 * thread &mdash; there is no separate synchronization.
 *
 * <p>Do not subclass for application logic. Use {@link #compose()}, or the
 * public setters directly. Unlike the request/response protocols, {@code
 * MdnsServer} has no application handler SPI &mdash; probing, announcing,
 * and query answering are self-contained protocol behaviour.
 *
 * <h2>Composition Example</h2>
 * <pre>{@code
 * MdnsServer server = MdnsServer.compose()
 *         .listener(new MdnsListener())
 *         .hostname("gumdrop")
 *         .server();
 * gumdrop.addServer(server);
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see MdnsListener
 */
public class MdnsServer implements Server {

    private static final Logger LOGGER =
            Logger.getLogger(MdnsServer.class.getName());
    public static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.mdns.L10N");

    // RFC 6762 section 8.1: probing.
    private static final int PROBE_COUNT = 3;
    private static final long PROBE_INTERVAL_MS = 250;
    private static final int PROBE_INITIAL_DELAY_MAX_MS = 250;
    // RFC 6762 section 8.2: on a lost simultaneous-probe tie-break,
    // wait this long before restarting probing.
    private static final long PROBE_CONFLICT_WAIT_MS = 1000;
    // RFC 6762 section 8.3: announcing.
    private static final int ANNOUNCE_COUNT = 2;
    private static final long ANNOUNCE_INTERVAL_MS = 1000;
    // RFC 6762 section 6: randomize multicast query responses to avoid
    // many responders replying in lockstep.
    private static final int RESPONSE_DELAY_MIN_MS = 20;
    private static final int RESPONSE_DELAY_SPREAD_MS = 100;
    // RFC 6762 section 10: 120s is the recommended TTL for address
    // records of a host that could change address (e.g. DHCP).
    private static final int RECORD_TTL = 120;

    private enum State { IDLE, PROBING, ANNOUNCED }

    private final List<MdnsListener> listeners = new ArrayList<MdnsListener>();
    private final Random random = new Random();

    private String hostname;
    private boolean advertiseServices = true;
    private final Set<String> excludedDescriptions = new HashSet<String>();
    private Gumdrop gumdrop;

    private String hostnameLabel;
    private int nameConflictSuffix = 1;
    private String currentName;
    private List<InetAddress> ownAddresses = Collections.emptyList();

    private State state = State.IDLE;
    private int probesSent;
    private int announcesSent;
    private List<DnsResourceRecord> currentRecords = Collections.emptyList();
    private MdnsListener.TimerHandleWrapper timerHandle;

    private final MdnsCache cache = new MdnsCache(new MdnsCache.Refresher() {
        @Override
        public void sendRefreshQuery(String name, DnsType type) {
            sendQuery(name, type, true);
        }

        @Override
        public MdnsListener.TimerHandleWrapper scheduleTimer(long delayMs, Runnable task) {
            return primaryListener().scheduleTimer(delayMs, task);
        }
    });

    /**
     * Creates a new mDNS service.
     */
    public MdnsServer() {
    }

    // ── Listener management ──

    /**
     * Adds an mDNS listener.
     *
     * @param listener the mDNS listener endpoint
     */
    public void addListener(MdnsListener listener) {
        listeners.add(listener);
    }

    /**
     * Sets the listeners from a configuration list. Each item must be
     * an {@link MdnsListener}.
     *
     * @param list the list of listener endpoints
     */
    public void setListeners(List<?> list) {
        for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);
            if (item instanceof MdnsListener) {
                addListener((MdnsListener) item);
            }
        }
    }

    @Override
    public List<Listener> getListeners() {
        return Collections.<Listener>unmodifiableList(listeners);
    }

    // ── Configuration ──

    /**
     * Sets the hostname label to probe for and advertise as
     * {@code <hostname>.local}. If unset, the JVM's local hostname
     * (with any domain suffix stripped) is used, falling back to
     * {@code "gumdrop"} if that can't be determined.
     *
     * @param hostname the hostname label (no trailing ".local")
     */
    public void setHostname(String hostname) {
        this.hostname = hostname;
    }

    /**
     * Sets whether to auto-advertise gumdrop's own configured services
     * as DNS-SD (RFC 6763) records once announced. Default true.
     *
     * <p>Uses the {@link Gumdrop} runtime's {@code getServers()} at
     * announce-time, so it only sees protocol servers that have already
     * started -- add this server to the {@link Gumdrop} runtime
     * <strong>last</strong> (e.g. the final {@code gumdrop.addServer(...)}
     * call in application composition code) so every other server's
     * listeners are already bound and assigned real ports by the time
     * this runs.
     *
     * @param advertiseServices true to auto-advertise
     */
    public void setAdvertiseServices(boolean advertiseServices) {
        this.advertiseServices = advertiseServices;
    }

    /**
     * Sets {@link org.bluezoo.gumdrop.Listener#getDescription()} values
     * to never advertise via DNS-SD even if otherwise eligible (e.g.
     * {@code "dns"} to avoid advertising gumdrop's own DNS resolver).
     * Space-separated.
     *
     * @param descriptions space-separated listener descriptions to exclude
     */
    public void setExcludedServices(String descriptions) {
        excludedDescriptions.clear();
        if (descriptions == null) {
            return;
        }
        StringTokenizer st = new StringTokenizer(descriptions);
        while (st.hasMoreTokens()) {
            excludedDescriptions.add(st.nextToken());
        }
    }

    /**
     * Returns the name currently probed for or announced (including
     * any conflict-resolution suffix and the {@code .local} suffix),
     * or null before {@link #start()} has run.
     *
     * @return the current mDNS name
     */
    public String getCurrentName() {
        return currentName;
    }

    /**
     * Returns true once probing has completed successfully and this
     * service is announcing/answering for {@link #getCurrentName()}.
     *
     * @return true if announced
     */
    public boolean isAnnounced() {
        return state == State.ANNOUNCED;
    }

    // ── Querying (RFC 6762 section 5) ──

    /**
     * Sends a one-shot mDNS query for the given name/type to the local
     * network. Answers arrive asynchronously as other hosts respond
     * and are cached (with RFC 6762 section 5.2 active refresh, so
     * they stay current without needing to be re-queried by the
     * caller); poll {@link #lookup} afterwards to read them &mdash;
     * e.g. after a short delay, or periodically.
     *
     * @param name the name to query for (e.g. {@code "foo.local"})
     * @param type the record type to query for
     */
    public void query(String name, DnsType type) {
        sendQuery(name, type, true);
    }

    /**
     * Returns the currently cached records for a name/type, most
     * recently populated by {@link #query} or by another host's
     * unsolicited announcement. Empty if nothing is cached (including
     * if nothing has been queried for yet).
     *
     * @param name the record name
     * @param type the record type
     * @return the cached records, never null
     */
    public List<DnsResourceRecord> lookup(String name, DnsType type) {
        return cache.lookup(name, type);
    }

    private void sendQuery(String name, DnsType type, boolean includeKnownAnswers) {
        if (listeners.isEmpty()) {
            return;
        }
        DnsQuestion question = new DnsQuestion(name, type, DnsClass.IN);
        List<DnsResourceRecord> knownAnswers = includeKnownAnswers
                ? cache.lookup(name, type) : Collections.<DnsResourceRecord>emptyList();
        DnsMessage query = new DnsMessage(0, 0,
                Collections.singletonList(question),
                knownAnswers,
                Collections.<DnsResourceRecord>emptyList(),
                Collections.<DnsResourceRecord>emptyList());
        primaryListener().sendToGroup(query.serialize());
    }

    // ── Lifecycle ──

    @Override
    public void start(Gumdrop gumdrop) {
        this.gumdrop = gumdrop;
        hostnameLabel = resolveHostnameLabel();
        ownAddresses = gatherOwnAddresses();

        boolean anyBound = false;
        for (int i = 0; i < listeners.size(); i++) {
            MdnsListener l = listeners.get(i);
            l.setServer(this);
            try {
                l.start(gumdrop);
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Failed to start mDNS listener: " + l, e);
                continue;
            }
            anyBound |= l.isBound();
        }

        if (listeners.isEmpty()) {
            LOGGER.warning(L10N.getString("warn.mdns_no_listener"));
            return;
        }
        // l.start() logs and swallows bind failures rather than
        // throwing (matching DnsListener's convention), so a failed
        // bind falls through to here rather than the catch block above
        // -- anyBound is what actually tells us whether it's safe to
        // start probing.
        if (!anyBound) {
            LOGGER.warning(L10N.getString("warn.mdns_no_listener_bound"));
            return;
        }
        if (ownAddresses.isEmpty()) {
            LOGGER.warning(L10N.getString("warn.mdns_no_addresses"));
            return;
        }
        beginProbing();
    }

    @Override
    public void stop() {
        for (int i = 0; i < listeners.size(); i++) {
            try {
                listeners.get(i).stop();
            } catch (Exception e) {
                LOGGER.log(Level.WARNING,
                        "Failed to stop mDNS listener: " + listeners.get(i), e);
            }
        }
        cancelTimer();
        cache.clear();
        state = State.IDLE;
    }

    private String resolveHostnameLabel() {
        String candidate = hostname;
        if (candidate == null || candidate.trim().isEmpty()) {
            try {
                candidate = InetAddress.getLocalHost().getHostName();
            } catch (UnknownHostException e) {
                candidate = "gumdrop";
            }
        }
        candidate = candidate.trim();
        int dot = candidate.indexOf('.');
        if (dot >= 0) {
            candidate = candidate.substring(0, dot);
        }
        return candidate.isEmpty() ? "gumdrop" : candidate;
    }

    private List<InetAddress> gatherOwnAddresses() {
        List<InetAddress> result = new ArrayList<InetAddress>();
        try {
            Enumeration<NetworkInterface> interfaces =
                    NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                // Mirror MdnsListener's own interface eligibility (up,
                // non-loopback, non-point-to-point): an address on an
                // interface we don't actually join/multicast-send on
                // (e.g. a VPN tunnel) isn't reachable via mDNS and
                // shouldn't be advertised.
                if (!ni.isUp() || ni.isLoopback() || ni.isPointToPoint()) {
                    continue;
                }
                Enumeration<InetAddress> addresses = ni.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr instanceof Inet4Address) {
                        result.add(addr);
                    }
                }
            }
        } catch (SocketException e) {
            LOGGER.log(Level.WARNING,
                    L10N.getString("err.mdns_enumerate_interfaces"), e);
        }
        return result;
    }

    // ── Probing (RFC 6762 section 8.1/8.2) ──

    private void beginProbing() {
        state = State.PROBING;
        currentName = buildCandidateName();
        probesSent = 0;
        scheduleNext(random.nextInt(PROBE_INITIAL_DELAY_MAX_MS + 1), new Runnable() {
            @Override public void run() { sendNextProbe(); }
        });
    }

    private String buildCandidateName() {
        String label = nameConflictSuffix <= 1
                ? hostnameLabel : hostnameLabel + "-" + nameConflictSuffix;
        return label + ".local";
    }

    private void sendNextProbe() {
        List<DnsResourceRecord> proposed = buildRecords(currentName, RECORD_TTL, false);
        DnsQuestion question = new DnsQuestion(currentName, DnsType.ANY, DnsClass.IN);
        DnsMessage probe = new DnsMessage(0, 0,
                Collections.singletonList(question),
                Collections.<DnsResourceRecord>emptyList(),
                proposed,
                Collections.<DnsResourceRecord>emptyList());
        primaryListener().sendToGroup(probe.serialize());

        probesSent++;
        if (probesSent < PROBE_COUNT) {
            scheduleNext(PROBE_INTERVAL_MS, new Runnable() {
                @Override public void run() { sendNextProbe(); }
            });
        } else {
            scheduleNext(PROBE_INTERVAL_MS, new Runnable() {
                @Override public void run() { announce(); }
            });
        }
    }

    private void restartProbingAfterConflict() {
        if (LOGGER.isLoggable(Level.INFO)) {
            String msg = MessageFormat.format(
                    L10N.getString("info.mdns_name_conflict"), currentName);
            LOGGER.info(msg);
        }
        nameConflictSuffix++;
        beginProbing();
    }

    // ── Announcing (RFC 6762 section 8.3) ──

    private void announce() {
        state = State.ANNOUNCED;
        announcesSent = 0;
        List<DnsResourceRecord> records = new ArrayList<DnsResourceRecord>(
                buildRecords(currentName, RECORD_TTL, true));
        if (advertiseServices) {
            String hostLabel = currentName.substring(
                    0, currentName.length() - ".local".length());
            records.addAll(DnssdAdvertiser.buildRecords(
                    gumdrop.getServers(), hostLabel,
                    RECORD_TTL, excludedDescriptions));
        }
        currentRecords = records;
        sendAnnouncement();
    }

    private void sendAnnouncement() {
        DnsMessage response = new DnsMessage(0,
                DnsMessage.FLAG_QR | DnsMessage.FLAG_AA,
                Collections.<DnsQuestion>emptyList(),
                currentRecords,
                Collections.<DnsResourceRecord>emptyList(),
                Collections.<DnsResourceRecord>emptyList());
        primaryListener().sendToGroup(response.serialize());

        announcesSent++;
        if (announcesSent < ANNOUNCE_COUNT) {
            scheduleNext(ANNOUNCE_INTERVAL_MS, new Runnable() {
                @Override public void run() { sendAnnouncement(); }
            });
        }
    }

    /**
     * Sends a goodbye packet (RFC 6762 section 10.1: an unsolicited
     * response with TTL 0) withdrawing this responder's records.
     * Called by {@link MdnsListener#stop()} while its endpoint is
     * still open.
     *
     * @param origin the listener that is stopping
     */
    public void sendGoodbye(MdnsListener origin) {
        if (state != State.ANNOUNCED || currentRecords.isEmpty()) {
            return;
        }
        List<DnsResourceRecord> goodbyeRecords = buildRecords(currentName, 0, true);
        DnsMessage goodbye = new DnsMessage(0,
                DnsMessage.FLAG_QR | DnsMessage.FLAG_AA,
                Collections.<DnsQuestion>emptyList(),
                goodbyeRecords,
                Collections.<DnsResourceRecord>emptyList(),
                Collections.<DnsResourceRecord>emptyList());
        origin.sendToGroup(goodbye.serialize());
        state = State.IDLE;
    }

    private List<DnsResourceRecord> buildRecords(String name, int ttl, boolean cacheFlush) {
        List<DnsResourceRecord> records = new ArrayList<DnsResourceRecord>(ownAddresses.size());
        int rawClass = DnsClass.IN.getValue()
                | (cacheFlush ? DnsResourceRecord.CACHE_FLUSH_BIT : 0);
        for (InetAddress addr : ownAddresses) {
            records.add(new DnsResourceRecord(name, DnsType.A, DnsType.A.getValue(),
                    DnsClass.IN, rawClass, ttl, addr.getAddress()));
        }
        return records;
    }

    // ── Incoming datagram handling ──

    /**
     * Handles a datagram received on the mDNS multicast group.
     *
     * @param origin the listener that received the datagram
     * @param data the raw datagram data
     * @param source the sender's address
     */
    public void handleDatagram(MdnsListener origin, ByteBuffer data, InetSocketAddress source) {
        try {
            DnsMessage message = DnsMessage.parse(data);
            if (message.isResponse()) {
                handleIncomingResponse(message);
            } else {
                handleIncomingQuery(origin, message, source);
            }
        } catch (DnsFormatException e) {
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.log(Level.FINE,
                        L10N.getString("warn.mdns_malformed_packet"), e);
            }
        } catch (Exception e) {
            String msg = MessageFormat.format(
                    L10N.getString("err.mdns_handle_datagram"), source);
            LOGGER.log(Level.WARNING, msg, e);
        }
    }

    private void handleIncomingResponse(DnsMessage message) {
        if (state == State.PROBING && checkProbeConflict(message)) {
            return;
        }
        cache.addAll(message.getAnswers());
    }

    private boolean checkProbeConflict(DnsMessage message) {
        List<DnsResourceRecord> answers = message.getAnswers();
        for (int i = 0; i < answers.size(); i++) {
            if (isConflicting(answers.get(i))) {
                restartProbingAfterConflict();
                return true;
            }
        }
        return false;
    }

    private boolean isConflicting(DnsResourceRecord rr) {
        if (!currentName.equalsIgnoreCase(rr.getName()) || rr.getType() != DnsType.A) {
            return false;
        }
        return !matchesOwnAddress(rr.getRData());
    }

    private void handleIncomingQuery(MdnsListener origin, DnsMessage message,
                                      InetSocketAddress source) {
        if (state == State.PROBING) {
            checkSimultaneousProbeConflict(message);
            return;
        }
        if (state != State.ANNOUNCED) {
            return;
        }
        // RFC 6762 section 7.3: real queriers essentially always send a
        // single question per query, so a response per matched question
        // (rather than batching every question's answers into one
        // packet) keeps this simple without losing anything in practice.
        List<DnsQuestion> questions = message.getQuestions();
        for (int i = 0; i < questions.size(); i++) {
            DnsQuestion q = questions.get(i);
            List<DnsResourceRecord> matches = matchingRecords(q.getName(), q.getType());
            if (matches.isEmpty() || isFullyKnown(message, matches)) {
                continue;
            }
            respondToQuery(origin, q, matches, source);
        }
    }

    /**
     * Returns our own current records (host address plus, once
     * published, DNS-SD service records) matching a question's name
     * and type.
     */
    private List<DnsResourceRecord> matchingRecords(String name, DnsType type) {
        List<DnsResourceRecord> result = new ArrayList<DnsResourceRecord>();
        for (int i = 0; i < currentRecords.size(); i++) {
            DnsResourceRecord rr = currentRecords.get(i);
            if (rr.getName().equalsIgnoreCase(name)
                    && (type == DnsType.ANY || rr.getType() == type)) {
                result.add(rr);
            }
        }
        return result;
    }

    /**
     * RFC 6762 section 8.2: when another host probes for the same name
     * we're currently probing for, compare the proposed records and
     * defer to the lexicographically greater one. This compares a
     * single representative record from each side (we typically only
     * advertise one address) rather than the full RRset ordering
     * algorithm the RFC describes for the general case.
     */
    private void checkSimultaneousProbeConflict(DnsMessage message) {
        if (ownAddresses.isEmpty()) {
            return;
        }
        byte[] ours = ownAddresses.get(0).getAddress();
        List<DnsResourceRecord> authorities = message.getAuthorities();
        for (int i = 0; i < authorities.size(); i++) {
            DnsResourceRecord rr = authorities.get(i);
            if (!currentName.equalsIgnoreCase(rr.getName()) || rr.getType() != DnsType.A) {
                continue;
            }
            byte[] theirs = rr.getRData();
            if (compareUnsigned(theirs, ours) > 0) {
                cancelTimer();
                scheduleNext(PROBE_CONFLICT_WAIT_MS, new Runnable() {
                    @Override public void run() { beginProbing(); }
                });
                return;
            }
        }
    }

    private static int compareUnsigned(byte[] a, byte[] b) {
        int len = Math.min(a.length, b.length);
        for (int i = 0; i < len; i++) {
            int diff = (a[i] & 0xFF) - (b[i] & 0xFF);
            if (diff != 0) {
                return diff;
            }
        }
        return a.length - b.length;
    }

    /**
     * RFC 6762 section 7.1: known-answer suppression. Returns true only
     * if the querier's own known-answer list already includes every one
     * of the given records (matched by name/type/rdata) with more than
     * half its TTL remaining, in which case we owe no answer at all.
     */
    private boolean isFullyKnown(DnsMessage message, List<DnsResourceRecord> matches) {
        List<DnsResourceRecord> knownAnswers = message.getAnswers();
        for (int i = 0; i < matches.size(); i++) {
            DnsResourceRecord candidate = matches.get(i);
            boolean known = false;
            for (int j = 0; j < knownAnswers.size(); j++) {
                DnsResourceRecord rr = knownAnswers.get(j);
                if (candidate.getName().equalsIgnoreCase(rr.getName())
                        && candidate.getType() == rr.getType()
                        && rr.getTTL() > candidate.getTTL() / 2
                        && Arrays.equals(candidate.getRData(), rr.getRData())) {
                    known = true;
                    break;
                }
            }
            if (!known) {
                return false;
            }
        }
        return true;
    }

    private boolean matchesOwnAddress(byte[] rdata) {
        for (InetAddress addr : ownAddresses) {
            if (Arrays.equals(addr.getAddress(), rdata)) {
                return true;
            }
        }
        return false;
    }

    private void respondToQuery(final MdnsListener origin, DnsQuestion question,
                                 final List<DnsResourceRecord> answers,
                                 final InetSocketAddress source) {
        final DnsMessage response = new DnsMessage(0,
                DnsMessage.FLAG_QR | DnsMessage.FLAG_AA,
                Collections.<DnsQuestion>emptyList(),
                answers,
                Collections.<DnsResourceRecord>emptyList(),
                Collections.<DnsResourceRecord>emptyList());
        if (question.isUnicastResponseRequested()) {
            origin.sendTo(response.serialize(), source);
        } else {
            long delay = RESPONSE_DELAY_MIN_MS + random.nextInt(RESPONSE_DELAY_SPREAD_MS);
            origin.scheduleTimer(delay, new Runnable() {
                @Override public void run() { origin.sendToGroup(response.serialize()); }
            });
        }
    }

    // ── Timer plumbing ──

    private void scheduleNext(long delayMs, Runnable task) {
        cancelTimer();
        timerHandle = primaryListener().scheduleTimer(delayMs, task);
    }

    private void cancelTimer() {
        if (timerHandle != null) {
            timerHandle.cancel();
            timerHandle = null;
        }
    }

    private MdnsListener primaryListener() {
        return listeners.get(0);
    }

    /**
     * Starts fluent composition of a concrete {@link MdnsServer}.
     *
     * @return a new composer
     */
    public static Composer compose() {
        return new Composer();
    }

    /**
     * Fluent composition of listeners and configuration.
     */
    public static final class Composer {

        private final List<MdnsListener> listeners = new ArrayList<MdnsListener>();
        private String hostname;
        private boolean advertiseServices = true;
        private String excludedServices;

        private Composer() {
        }

        public Composer listener(MdnsListener listener) {
            if (listener == null) {
                throw new NullPointerException("listener");
            }
            listeners.add(listener);
            return this;
        }

        public Composer hostname(String hostname) {
            this.hostname = hostname;
            return this;
        }

        public Composer advertiseServices(boolean advertiseServices) {
            this.advertiseServices = advertiseServices;
            return this;
        }

        public Composer excludedServices(String excludedServices) {
            this.excludedServices = excludedServices;
            return this;
        }

        /**
         * Creates the composed server. At least one listener is required.
         */
        public MdnsServer server() {
            if (listeners.isEmpty()) {
                throw new IllegalStateException(
                        "at least one listener is required");
            }
            MdnsServer server = new MdnsServer();
            if (hostname != null) {
                server.setHostname(hostname);
            }
            server.setAdvertiseServices(advertiseServices);
            if (excludedServices != null) {
                server.setExcludedServices(excludedServices);
            }
            for (int i = 0; i < listeners.size(); i++) {
                server.addListener(listeners.get(i));
            }
            return server;
        }
    }

}
