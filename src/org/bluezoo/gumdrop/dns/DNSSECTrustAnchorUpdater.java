/*
 * DNSSECTrustAnchorUpdater.java
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

import org.bluezoo.gumdrop.dns.client.DNSResolver;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * RFC 5011 automated DNSSEC trust anchor rollover.
 *
 * <p>Watches one or more "trust points" (zones whose trust anchor
 * should stay current automatically -- typically {@code "."}, the
 * root) by periodically querying their DNSKEY RRset and tracking each
 * Secure Entry Point (KSK) key through the states RFC 5011 section 4.2
 * defines:
 * <ul>
 * <li><b>AddPending</b> -- a new key, signed by an already-trusted key,
 *     first observed. Not yet usable for validation.</li>
 * <li><b>Valid</b> -- an AddPending key that has been continuously
 *     observed for at least {@link #getAddHoldDownMs}, or a key already
 *     configured as a static trust anchor (RFC 5011 section 4.1: such
 *     keys start Valid, no hold-down needed).</li>
 * <li><b>Missing</b> -- a Valid key no longer present in the observed
 *     RRset. Returns to Valid immediately if it reappears; otherwise
 *     removed once missing for {@link #getRemoveHoldDownMs}.</li>
 * <li><b>Revoked</b> -- a Valid or AddPending key observed with the
 *     REVOKE bit set (RFC 5011 section 3) and a valid self-signature
 *     (RFC 5011 section 5.1). Stops being trusted immediately, removed
 *     once revoked for {@link #getRemoveHoldDownMs}.</li>
 * </ul>
 *
 * <p>A DNSKEY RRset is only acted on when a currently-trusted key (a
 * static trust anchor, or an already-Valid tracked key) validly signs
 * it -- RFC 5011 section 5's core safeguard against accepting a
 * fraudulent key addition. If no such signature is found, this cycle's
 * response is ignored and the existing trust anchors are left exactly
 * as they were: RFC 5011 support never blocks or fails ordinary
 * resolution, and a lookup failure just means the next scheduled check
 * gets another chance. Likewise, an update that would leave a trust
 * point with zero Valid keys is rejected outright (RFC 5011 section
 * 5.1) rather than applied.
 *
 * <p>Valid keys are promoted into {@link DNSSECTrustAnchor} via {@link
 * DNSSECTrustAnchor#addDNSKEYAnchor}; keys leaving the Valid state are
 * removed from it the same way. Tracked state (which keys, which
 * state, and since when) is persisted to {@link #setStateFile} across
 * restarts, as RFC 5011 section 2.3 requires for hold-down timers to
 * survive one.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see DNSSECTrustAnchor
 * @see <a href="https://www.rfc-editor.org/rfc/rfc5011">RFC 5011</a>
 */
public class DNSSECTrustAnchorUpdater {

    private static final Logger LOGGER =
            Logger.getLogger(DNSSECTrustAnchorUpdater.class.getName());
    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.dns.L10N");

    /** RFC 5011 section 4.1: hold-down times MUST NOT be less than 30 days. */
    public static final long DEFAULT_HOLD_DOWN_MS = 30L * 24 * 60 * 60 * 1000;

    /**
     * RFC 5011 section 4.1 calls for querying the trust point no less
     * often than half of the DNSKEY RRset's own original TTL, so a
     * change is never missed between two consecutive observations for
     * long enough to be mistaken for instantaneous. Rather than
     * deriving that per response, gumdrop uses this fixed default,
     * comfortably on the frequent side for realistic root-zone TTLs
     * (RFC 5011 requires checking often enough, so erring towards
     * "more often" is the safe direction) -- 24 hours means a 30-day
     * hold-down is checked roughly 30 times, far more than needed to
     * tell a stable new key from a transient blip.
     */
    public static final long DEFAULT_CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000;

    /** RFC 4034 section 2.1.1: DNSKEY REVOKE flag, bit 8. */
    private static final int FLAG_REVOKE = 0x0080;

    private final DNSResolver resolver;
    private final DNSSECTrustAnchor trustAnchor;

    private volatile long addHoldDownMs = DEFAULT_HOLD_DOWN_MS;
    private volatile long removeHoldDownMs = DEFAULT_HOLD_DOWN_MS;
    private volatile long checkIntervalMs = DEFAULT_CHECK_INTERVAL_MS;
    private File stateFile;

    private final Map<String, List<TrackedKey>> trustPoints = new ConcurrentHashMap<>();

    private ScheduledExecutorService scheduler;
    private final Map<String, ScheduledFuture<?>> scheduledChecks = new ConcurrentHashMap<>();

    /**
     * Creates an updater. {@link #addTrustPoint} must be called at
     * least once, and {@link #start} to begin periodic checking --
     * this constructor does neither.
     *
     * @param resolver the resolver to query trust points with
     * @param trustAnchor the trust anchor store to keep current
     */
    public DNSSECTrustAnchorUpdater(DNSResolver resolver, DNSSECTrustAnchor trustAnchor) {
        if (resolver == null) {
            throw new NullPointerException("resolver");
        }
        if (trustAnchor == null) {
            throw new NullPointerException("trustAnchor");
        }
        this.resolver = resolver;
        this.trustAnchor = trustAnchor;
    }

    // ── Configuration ──

    /**
     * Sets the Add hold-down time: how long a newly-observed key must
     * be continuously present before being promoted to Valid. RFC 5011
     * section 4.1 requires at least 30 days for real trust points;
     * lower values are only for testing.
     *
     * @param ms the hold-down time in milliseconds
     */
    public void setAddHoldDownMs(long ms) {
        this.addHoldDownMs = ms;
    }

    public long getAddHoldDownMs() {
        return addHoldDownMs;
    }

    /**
     * Sets the Remove hold-down time: how long a Missing or Revoked
     * key is retained (unusable for validation, but not yet forgotten)
     * before being purged. RFC 5011 does not mandate a specific value;
     * gumdrop defaults it to the same 30 days as the Add hold-down.
     *
     * @param ms the hold-down time in milliseconds
     */
    public void setRemoveHoldDownMs(long ms) {
        this.removeHoldDownMs = ms;
    }

    public long getRemoveHoldDownMs() {
        return removeHoldDownMs;
    }

    /**
     * Sets how often each trust point's DNSKEY RRset is queried. See
     * {@link #DEFAULT_CHECK_INTERVAL_MS}.
     *
     * @param ms the check interval in milliseconds
     */
    public void setCheckIntervalMs(long ms) {
        this.checkIntervalMs = ms;
    }

    public long getCheckIntervalMs() {
        return checkIntervalMs;
    }

    /**
     * Sets the file tracked key state is persisted to and immediately
     * loads any state already there, merging it in (a trust point
     * already enrolled via {@link #addTrustPoint} keeps its in-memory
     * state for keys not mentioned in the file; the file only adds to
     * or is added to by what's already tracked). Call before {@link
     * #addTrustPoint}/{@link #start} to load prior state as of process
     * startup, per RFC 5011 section 2.3.
     *
     * @param file the state file (need not exist yet)
     */
    public void setStateFile(File file) {
        this.stateFile = file;
        loadState();
    }

    // ── Trust point management ──

    /**
     * Enrolls a zone for automated rollover. Its currently-configured
     * {@link DNSSECTrustAnchor} DS anchors (if any) seed the initial
     * Valid key(s) on the first successful check; a zone with no
     * static anchor at all can still be enrolled, but nothing is ever
     * promoted for it unless some other already-Valid key already
     * covers it (e.g. one restored from {@link #setStateFile}).
     *
     * @param zone the trust point zone name (e.g. {@code "."} for the root)
     */
    public void addTrustPoint(String zone) {
        String key = DNSSECValidator.canonicalizeName(zone);
        trustPoints.computeIfAbsent(key, z -> new ArrayList<>());
    }

    /**
     * Returns the zones currently enrolled for automated rollover.
     *
     * @return the enrolled trust point zones
     */
    public Set<String> getTrustPoints() {
        return Collections.unmodifiableSet(trustPoints.keySet());
    }

    /**
     * Returns a read-only snapshot of a trust point's tracked keys,
     * for diagnostics and tests.
     *
     * @param zone the trust point zone name
     * @return the tracked keys, or an empty list if the zone isn't enrolled
     */
    public List<TrackedKeyInfo> getTrackedKeys(String zone) {
        List<TrackedKey> tracked = trustPoints.get(DNSSECValidator.canonicalizeName(zone));
        if (tracked == null) {
            return Collections.emptyList();
        }
        List<TrackedKeyInfo> result = new ArrayList<>(tracked.size());
        for (TrackedKey tk : tracked) {
            result.add(new TrackedKeyInfo(tk.keyTag, tk.algorithm, tk.publicKey(), tk.state, tk.stateChangedAt));
        }
        return result;
    }

    // ── Lifecycle ──

    /**
     * Begins periodic DNSKEY checks for every enrolled trust point,
     * starting immediately. Safe to call more than once (a trust point
     * added after the first call is picked up by future scheduling but
     * only gets its own periodic check once {@code start()} is called
     * again, or via {@link #checkNow}).
     */
    public synchronized void start() {
        if (scheduler == null) {
            scheduler = Executors.newScheduledThreadPool(1, r -> {
                Thread t = new Thread(r, "gumdrop-rfc5011-updater");
                t.setDaemon(true);
                return t;
            });
        }
        for (String zone : trustPoints.keySet()) {
            scheduleZone(zone);
        }
    }

    private void scheduleZone(String zone) {
        if (scheduledChecks.containsKey(zone)) {
            return;
        }
        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(() -> {
            try {
                checkNow(zone);
            } catch (RuntimeException e) {
                // A ScheduledExecutorService silently and permanently
                // stops future runs of a periodic task if one
                // invocation throws uncaught -- must never let that
                // happen here, or this trust point stops being
                // refreshed at all until the next process restart.
                LOGGER.log(Level.WARNING, MessageFormat.format(
                        L10N.getString("rfc5011.check_failed"), zone), e);
            }
        }, 0, checkIntervalMs, TimeUnit.MILLISECONDS);
        scheduledChecks.put(zone, future);
    }

    /**
     * Stops all periodic checks. Already-tracked state is left as-is
     * (and remains usable for validation); call {@link #start} again
     * to resume.
     */
    public synchronized void stop() {
        for (ScheduledFuture<?> future : scheduledChecks.values()) {
            future.cancel(false);
        }
        scheduledChecks.clear();
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    /**
     * Triggers one DNSKEY check for a trust point immediately, outside
     * the periodic schedule. Also what the periodic schedule itself
     * calls.
     *
     * @param zone the trust point zone name
     */
    public void checkNow(final String zone) {
        final String canonical = DNSSECValidator.canonicalizeName(zone);
        resolver.query(zone, DNSType.DNSKEY, new DNSQueryCallback() {
            @Override
            public void onResponse(DNSMessage response) {
                handleDNSKEYResponse(canonical, response);
            }

            @Override
            public void onError(String error) {
                // Fail open: leave existing trust anchors exactly as
                // they are and try again on the next scheduled check.
                if (LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.fine(MessageFormat.format(
                            L10N.getString("rfc5011.dnskey_fetch_failed"),
                            canonical, error));
                }
            }
        });
    }

    // ── RFC 5011 state machine ──

    /**
     * The current time, as used for all hold-down timing. Overridable
     * (this class is deliberately non-final) so tests can exercise
     * hold-down expiry deterministically instead of sleeping through
     * real wall-clock time.
     *
     * @return the current time in milliseconds since the epoch
     */
    long now() {
        return System.currentTimeMillis();
    }

    /**
     * Applies one round of the RFC 5011 state machine to a DNSKEY
     * response for {@code zone}. Package-private (not private) so
     * tests can drive it directly.
     */
    void handleDNSKEYResponse(String zoneParam, DNSMessage response) {
        String zone = DNSSECValidator.canonicalizeName(zoneParam);
        List<DNSResourceRecord> answers = response.getAnswers();
        List<DNSResourceRecord> dnskeys =
                DNSSECValidator.filterByType(answers, DNSType.DNSKEY);
        if (dnskeys.isEmpty()) {
            return; // fail open: nothing usable this round
        }
        List<DNSResourceRecord> rrsigs =
                DNSSECValidator.findRRSIGs(answers, DNSType.DNSKEY.getValue());

        List<TrackedKey> tracked =
                trustPoints.computeIfAbsent(zone, z -> new ArrayList<>());

        if (!signedByCurrentlyTrustedKey(zone, dnskeys, rrsigs, tracked)) {
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine(MessageFormat.format(
                        L10N.getString("rfc5011.no_trusted_signature"), zone));
            }
            return; // fail open: no basis to trust this round's RRset
        }

        List<TrackedKey> before = copy(tracked);
        long now = now();
        Set<TrackedKey> seenThisRound = new HashSet<>();

        for (DNSResourceRecord candidate : dnskeys) {
            if (!candidate.isDNSKEYSecureEntryPoint()) {
                continue; // RFC 5011 manages Secure Entry Points (KSKs) only
            }
            byte[] publicKey = candidate.getDNSKEYPublicKey();
            int algorithm = candidate.getDNSKEYAlgorithm();

            if (candidate.isDNSKEYRevoked()) {
                TrackedKey existing = find(tracked, algorithm, publicKey);
                if (existing != null
                        && (existing.state == KeyState.VALID || existing.state == KeyState.ADD_PENDING)
                        && isValidlySelfSigned(candidate, dnskeys, rrsigs)) {
                    if (existing.state == KeyState.VALID) {
                        trustAnchor.removeDNSKEYAnchor(zone, toDNSKEYRecord(zone, existing.rdata));
                    }
                    existing.state = KeyState.REVOKED;
                    existing.stateChangedAt = now;
                    seenThisRound.add(existing);
                }
                continue; // a revoked key is never newly tracked
            }

            TrackedKey existing = find(tracked, algorithm, publicKey);
            boolean staticallyTrusted = trustAnchor.isDNSKEYTrusted(zone, candidate);

            if (existing == null) {
                existing = new TrackedKey(candidate.computeKeyTag(), algorithm,
                        candidate.getRData());
                existing.state = staticallyTrusted ? KeyState.VALID : KeyState.ADD_PENDING;
                existing.stateChangedAt = now;
                tracked.add(existing);
                if (existing.state == KeyState.VALID) {
                    trustAnchor.addDNSKEYAnchor(zone, candidate);
                }
            } else if (existing.state == KeyState.ADD_PENDING
                    && (staticallyTrusted || now - existing.stateChangedAt >= addHoldDownMs)) {
                existing.state = KeyState.VALID;
                existing.stateChangedAt = now;
                trustAnchor.addDNSKEYAnchor(zone, candidate);
            } else if (existing.state == KeyState.MISSING) {
                existing.state = KeyState.VALID;
                existing.stateChangedAt = now;
                trustAnchor.addDNSKEYAnchor(zone, candidate);
            }
            seenThisRound.add(existing);
        }

        for (TrackedKey tk : tracked) {
            if (seenThisRound.contains(tk)) {
                continue;
            }
            if (tk.state == KeyState.VALID) {
                trustAnchor.removeDNSKEYAnchor(zone, toDNSKEYRecord(zone, tk.rdata));
                tk.state = KeyState.MISSING;
                tk.stateChangedAt = now;
            }
        }

        Iterator<TrackedKey> it = tracked.iterator();
        while (it.hasNext()) {
            TrackedKey tk = it.next();
            if ((tk.state == KeyState.MISSING || tk.state == KeyState.REVOKED)
                    && now - tk.stateChangedAt >= removeHoldDownMs) {
                it.remove();
            }
        }

        if (!anyValid(tracked)) {
            // RFC 5011 section 5.1: an update that would leave a trust
            // point with no Valid keys at all MUST be rejected in its
            // entirety, rather than applied -- a malicious or broken
            // response revoking/dropping every key must not be able to
            // leave validation permanently broken. Nothing in `tracked`
            // is Valid at this point (that's what the check above just
            // established), so the only cleanup needed is restoring
            // whatever `before` had Valid back into the trust anchor --
            // this round may have removed it on the way to here.
            LOGGER.warning(MessageFormat.format(
                    L10N.getString("rfc5011.rejecting_zero_valid_keys"), zone));
            for (TrackedKey tk : before) {
                if (tk.state == KeyState.VALID) {
                    trustAnchor.addDNSKEYAnchor(zone, toDNSKEYRecord(zone, tk.rdata));
                }
            }
            tracked.clear();
            tracked.addAll(before);
            return;
        }

        persistState();
    }

    /**
     * RFC 5011 section 5: a DNSKEY RRset update is only acted on when
     * validly signed by a key already trusted -- a static trust
     * anchor, or an already-Valid tracked key.
     */
    private boolean signedByCurrentlyTrustedKey(String zone,
            List<DNSResourceRecord> dnskeys, List<DNSResourceRecord> rrsigs,
            List<TrackedKey> tracked) {
        for (DNSResourceRecord rrsig : rrsigs) {
            if (!DNSSECValidator.isRRSIGCurrent(rrsig)) {
                continue;
            }
            if (!zone.equals(DNSSECValidator.canonicalizeName(rrsig.getRRSIGSignerName()))) {
                continue; // must be signed at the trust point itself, not a parent
            }
            DNSResourceRecord signer = DNSSECValidator.findMatchingDNSKEY(rrsig, dnskeys);
            if (signer == null) {
                continue;
            }
            boolean trusted = trustAnchor.isDNSKEYTrusted(zone, signer)
                    || isTrackedValid(tracked, signer);
            if (trusted && DNSSECValidator.verifyRRSIG(dnskeys, rrsig, signer)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isTrackedValid(List<TrackedKey> tracked, DNSResourceRecord key) {
        TrackedKey tk = find(tracked, key.getDNSKEYAlgorithm(), key.getDNSKEYPublicKey());
        return tk != null && tk.state == KeyState.VALID;
    }

    /**
     * RFC 5011 section 5.1: a revocation must be signed by the key
     * being revoked itself, not merely appear with the REVOKE bit set.
     * Note the revoked key's own key tag (used to find its
     * self-signature) differs from its pre-revocation tag, since the
     * REVOKE bit is part of the signed RDATA (section 5.1) -- this
     * looks for a signature under the key's current (revoked) tag,
     * which is what a genuine self-signature would carry.
     */
    private static boolean isValidlySelfSigned(DNSResourceRecord revokedKey,
            List<DNSResourceRecord> dnskeys, List<DNSResourceRecord> rrsigs) {
        int tag = revokedKey.computeKeyTag();
        int algorithm = revokedKey.getDNSKEYAlgorithm();
        for (DNSResourceRecord rrsig : rrsigs) {
            if (rrsig.getRRSIGKeyTag() == tag && rrsig.getRRSIGAlgorithm() == algorithm
                    && DNSSECValidator.isRRSIGCurrent(rrsig)
                    && DNSSECValidator.verifyRRSIG(dnskeys, rrsig, revokedKey)) {
                return true;
            }
        }
        return false;
    }

    private static TrackedKey find(List<TrackedKey> tracked, int algorithm, byte[] publicKey) {
        for (TrackedKey tk : tracked) {
            if (tk.algorithm == algorithm && Arrays.equals(tk.publicKey(), publicKey)) {
                return tk;
            }
        }
        return null;
    }

    private static boolean anyValid(List<TrackedKey> tracked) {
        for (TrackedKey tk : tracked) {
            if (tk.state == KeyState.VALID) {
                return true;
            }
        }
        return false;
    }

    private static List<TrackedKey> copy(List<TrackedKey> tracked) {
        List<TrackedKey> result = new ArrayList<>(tracked.size());
        for (TrackedKey tk : tracked) {
            result.add(tk.copy());
        }
        return result;
    }

    private static DNSResourceRecord toDNSKEYRecord(String zone, byte[] rdata) {
        return new DNSResourceRecord(zone, DNSType.DNSKEY, DNSClass.IN, 0, rdata);
    }

    // ── Persistence (RFC 5011 section 2.3) ──

    private void persistState() {
        if (stateFile == null) {
            return;
        }
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, List<TrackedKey>> entry : trustPoints.entrySet()) {
            for (TrackedKey tk : entry.getValue()) {
                lines.add(entry.getKey() + "\t" + tk.state + "\t" + tk.stateChangedAt + "\t"
                        + Base64.getEncoder().encodeToString(tk.rdata));
            }
        }
        try {
            Files.write(stateFile.toPath(), lines, StandardCharsets.US_ASCII);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, MessageFormat.format(
                    L10N.getString("rfc5011.state_save_failed"), stateFile), e);
        }
    }

    private void loadState() {
        if (stateFile == null || !stateFile.exists()) {
            return;
        }
        try {
            for (String line : Files.readAllLines(stateFile.toPath(), StandardCharsets.US_ASCII)) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                String[] parts = line.split("\t");
                if (parts.length != 4) {
                    continue;
                }
                String zone = parts[0];
                KeyState state = KeyState.valueOf(parts[1]);
                long stateChangedAt = Long.parseLong(parts[2]);
                byte[] rdata = Base64.getDecoder().decode(parts[3]);

                DNSResourceRecord synthetic = toDNSKEYRecord(zone, rdata);
                TrackedKey tk = new TrackedKey(synthetic.computeKeyTag(),
                        synthetic.getDNSKEYAlgorithm(), rdata);
                tk.state = state;
                tk.stateChangedAt = stateChangedAt;

                List<TrackedKey> tracked = trustPoints.computeIfAbsent(zone, z -> new ArrayList<>());
                if (find(tracked, tk.algorithm, tk.publicKey()) == null) {
                    tracked.add(tk);
                    if (state == KeyState.VALID) {
                        trustAnchor.addDNSKEYAnchor(zone, synthetic);
                    }
                }
            }
        } catch (IOException | IllegalArgumentException e) {
            // Corrupt or unreadable state degrades to "nothing restored" --
            // trust points fall back to their statically-configured
            // anchors and re-learn from scratch, rather than failing startup.
            LOGGER.log(Level.WARNING, MessageFormat.format(
                    L10N.getString("rfc5011.state_load_failed"), stateFile), e);
        }
    }

    // ── Public types ──

    /** RFC 5011 section 4.2 key states. */
    public enum KeyState {
        ADD_PENDING, VALID, MISSING, REVOKED
    }

    /**
     * A read-only snapshot of one tracked key's state, for diagnostics
     * and tests.
     */
    public static final class TrackedKeyInfo {
        private final int keyTag;
        private final int algorithm;
        private final byte[] publicKey;
        private final KeyState state;
        private final long stateChangedAt;

        TrackedKeyInfo(int keyTag, int algorithm, byte[] publicKey, KeyState state, long stateChangedAt) {
            this.keyTag = keyTag;
            this.algorithm = algorithm;
            this.publicKey = publicKey.clone();
            this.state = state;
            this.stateChangedAt = stateChangedAt;
        }

        /**
         * Returns the pre-revocation key tag. Note that a key's actual
         * on-the-wire tag changes the moment its REVOKE bit is set (RFC
         * 5011 section 5.1); to identify a key across that flip, use
         * {@link #getPublicKey} and {@link #getAlgorithm} instead, the
         * same way this class matches keys internally.
         */
        public int getKeyTag() {
            return keyTag;
        }

        public int getAlgorithm() {
            return algorithm;
        }

        /**
         * Returns the key's public key material (RFC 4034 section
         * 2.1.4) -- stable across a REVOKE-bit flip, unlike the key tag.
         */
        public byte[] getPublicKey() {
            return publicKey.clone();
        }

        public KeyState getState() {
            return state;
        }

        public long getStateChangedAt() {
            return stateChangedAt;
        }
    }

    /**
     * Internal mutable tracking record for one key at one trust point.
     * Identified by algorithm and public key material -- not key tag,
     * which changes when the REVOKE bit is set (RFC 5011 section 5.1) --
     * so a revocation and its pre-revocation entry are correctly
     * recognized as the same key.
     */
    private static final class TrackedKey {
        final int keyTag; // informational only (the pre-revocation tag); not used for matching
        final int algorithm;
        final byte[] rdata; // full DNSKEY RDATA as observed, for exact restoration
        KeyState state;
        long stateChangedAt;

        TrackedKey(int keyTag, int algorithm, byte[] rdata) {
            this.keyTag = keyTag;
            this.algorithm = algorithm;
            this.rdata = rdata.clone();
        }

        byte[] publicKey() {
            byte[] key = new byte[rdata.length - 4];
            System.arraycopy(rdata, 4, key, 0, key.length);
            return key;
        }

        TrackedKey copy() {
            TrackedKey c = new TrackedKey(keyTag, algorithm, rdata);
            c.state = state;
            c.stateChangedAt = stateChangedAt;
            return c;
        }
    }

}
