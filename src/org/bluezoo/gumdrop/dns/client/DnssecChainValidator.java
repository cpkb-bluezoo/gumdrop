/*
 * DnssecChainValidator.java
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

package org.bluezoo.gumdrop.dns.client;

import org.bluezoo.gumdrop.CryptoExecutor;
import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.dns.DnsMessage;
import org.bluezoo.gumdrop.dns.DnsQueryCallback;
import org.bluezoo.gumdrop.dns.DnsResourceRecord;
import org.bluezoo.gumdrop.dns.DnssecStatus;
import org.bluezoo.gumdrop.dns.DnsType;

import java.text.MessageFormat;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.dns.client.DnsResolver;

/**
 * Asynchronous DNSSEC chain-of-trust validator.
 * RFC 4035 section 5: walks from the signer zone up to a configured
 * trust anchor, fetching DNSKEY and DS records at each delegation
 * point using the async {@link DnsResolver}.
 *
 * <p>The validation flow for a response is:
 * <ol>
 * <li>Find RRSIG records covering the answer RRset</li>
 * <li>Fetch DNSKEY for the signer zone (if not already present)</li>
 * <li>Verify one of the RRSIGs using its matching DNSKEY</li>
 * <li>Authenticate the zone's DNSKEY RRset: it must be signed by a key that
 *     a trust anchor or a DS record from the parent vouches for (a zone key
 *     is never trusted merely because it appears beside such a key)</li>
 * <li>Authenticate the DS RRset the same way, and repeat up to the root
 *     trust anchor</li>
 * </ol>
 *
 * <p>An RRset may carry several RRSIGs (algorithm or key rollover); it is
 * secure if any one of them verifies. A zone whose keys use only algorithms
 * or DS digest types this validator does not support is treated as
 * unsigned (RFC 4035 section 5.2), but only after the parent's DS RRset has
 * been authenticated, so that such a result cannot be forced by an attacker.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class DnssecChainValidator {

    private static final Logger LOGGER =
            Logger.getLogger(DnssecChainValidator.class.getName());
    static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.dns.L10N");

    private static final int MAX_CHAIN_DEPTH = 8;

    private final DnsResolver resolver;
    private final DnssecTrustAnchor trustAnchor;
    private Clock clock = Clock.systemUTC();

    /**
     * Creates a chain validator.
     *
     * @param resolver the resolver for DNSKEY/DS fetching
     * @param trustAnchor the trust anchor store
     */
    public DnssecChainValidator(DnsResolver resolver,
                                DnssecTrustAnchor trustAnchor) {
        this.resolver = resolver;
        this.trustAnchor = trustAnchor;
    }

    /**
     * Sets the clock that signature validity periods are checked against.
     * The default is the system clock; a fixed clock lets recorded
     * responses be validated as of the time they were captured.
     *
     * @param clock the clock to use
     * @return this validator
     */
    public DnssecChainValidator clock(Clock clock) {
        this.clock = clock;
        return this;
    }

    /**
     * Validates a DNS response by verifying its RRSIG signatures
     * and walking the chain of trust to a trust anchor.
     *
     * @param response the DNS response to validate
     * @param callback receives the validation result
     */
    public void validate(DnsMessage response,
                         DnssecValidationCallback callback) {
        List<DnsResourceRecord> answers = response.getAnswers();
        if (answers.isEmpty()) {
            validateAuthority(response, callback);
            return;
        }

        DnsResourceRecord firstAnswer = answers.get(0);
        if (firstAnswer.getType() == null) {
            callback.onValidated(DnssecStatus.INSECURE, response);
            return;
        }

        int coveredType = firstAnswer.getRawType();
        List<DnsResourceRecord> rrset = new ArrayList<>();
        for (int i = 0; i < answers.size(); i++) {
            DnsResourceRecord rr = answers.get(i);
            if (rr.getRawType() == coveredType) {
                rrset.add(rr);
            }
        }

        List<DnsResourceRecord> rrsigs =
                DnssecValidator.findRRSIGs(answers, coveredType);
        if (rrsigs.isEmpty()) {
            callback.onValidated(DnssecStatus.INSECURE, response);
            return;
        }

        List<DnsResourceRecord> current = currentSignatures(rrsigs);
        if (current.isEmpty()) {
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine(MessageFormat.format(
                        L10N.getString("dnssec.expired_signature"),
                        rrsigs.get(0).getRRSIGSignerName()));
            }
            callback.onValidated(DnssecStatus.BOGUS, response);
            return;
        }

        String signerZone = current.get(0).getRRSIGSignerName();
        current = sameSigner(current, signerZone);

        List<DnsResourceRecord> dnskeys =
                DnssecValidator.filterByType(answers, DnsType.DNSKEY);
        if (!dnskeys.isEmpty()) {
            verifyWithKeys(rrset, current, answers, signerZone,
                    response, callback, 0);
        } else {
            fetchDNSKEY(rrset, current, signerZone, response,
                    callback, 0);
        }
    }

    /**
     * Validates a negative response (NXDOMAIN or NODATA) by checking
     * NSEC/NSEC3 records in the authority section.
     */
    private void validateAuthority(DnsMessage response,
                                   DnssecValidationCallback callback) {
        List<DnsResourceRecord> authorities = response.getAuthorities();
        List<DnsResourceRecord> nsecRecords =
                DnssecValidator.filterByType(authorities, DnsType.NSEC);
        List<DnsResourceRecord> nsec3Records =
                DnssecValidator.filterByType(authorities, DnsType.NSEC3);

        if (nsecRecords.isEmpty() && nsec3Records.isEmpty()) {
            callback.onValidated(DnssecStatus.INSECURE, response);
            return;
        }

        // RFC 9276 section 3.2: an NSEC3 proof above the iteration limit is
        // insecure; do not spend any further work on it
        if (nsecRecords.isEmpty()
                && DnssecValidator.exceedsNsec3IterationLimit(nsec3Records)) {
            callback.onValidated(DnssecStatus.INSECURE, response);
            return;
        }

        List<DnsResourceRecord> rrsigs;
        if (!nsecRecords.isEmpty()) {
            rrsigs = DnssecValidator.findRRSIGs(
                    authorities, DnsType.NSEC.getValue());
        } else {
            rrsigs = DnssecValidator.findRRSIGs(
                    authorities, DnsType.NSEC3.getValue());
        }

        if (rrsigs.isEmpty()) {
            callback.onValidated(DnssecStatus.INSECURE, response);
            return;
        }

        List<DnsResourceRecord> current = currentSignatures(rrsigs);
        if (current.isEmpty()) {
            callback.onValidated(DnssecStatus.BOGUS, response);
            return;
        }

        String signerZone = current.get(0).getRRSIGSignerName();
        current = sameSigner(current, signerZone);
        List<DnsResourceRecord> rrset;
        if (!nsecRecords.isEmpty()) {
            rrset = nsecRecords;
        } else {
            rrset = nsec3Records;
        }

        fetchDNSKEY(rrset, current, signerZone, response, callback, 0);
    }

    /** The validation time, in seconds since the epoch. */
    private long now() {
        return clock.millis() / 1000;
    }

    /** The signatures whose validity period includes the present. */
    private List<DnsResourceRecord> currentSignatures(
            List<DnsResourceRecord> rrsigs) {
        List<DnsResourceRecord> current = new ArrayList<>();
        for (int i = 0; i < rrsigs.size(); i++) {
            if (DnssecValidator.isRRSIGCurrent(rrsigs.get(i), now())) {
                current.add(rrsigs.get(i));
            }
        }
        return current;
    }

    /** The signatures made in the named zone. */
    private static List<DnsResourceRecord> sameSigner(
            List<DnsResourceRecord> rrsigs, String signerZone) {
        List<DnsResourceRecord> same = new ArrayList<>();
        for (int i = 0; i < rrsigs.size(); i++) {
            if (signerZone.equalsIgnoreCase(rrsigs.get(i).getRRSIGSignerName())) {
                same.add(rrsigs.get(i));
            }
        }
        return same;
    }

    /**
     * Attempts to verify one of the RRSIGs using the DNSKEY records among
     * {@code keyRecords}, then authenticates the signing key and walks the
     * chain of trust upward.
     *
     * @param keyRecords the zone's DNSKEY records and, when supplied, the
     *        RRSIGs over that DNSKEY RRset
     */
    private void verifyWithKeys(
            List<DnsResourceRecord> rrset,
            List<DnsResourceRecord> rrsigs,
            List<DnsResourceRecord> keyRecords,
            String signerZone,
            DnsMessage response,
            DnssecValidationCallback callback,
            int depth) {

        List<DnsResourceRecord> dnskeys =
                DnssecValidator.filterByType(keyRecords, DnsType.DNSKEY);

        // Pair each signature with the key it names, supported algorithms
        // first so that one verifiable signature is enough.
        final List<DnsResourceRecord> usableSigs = new ArrayList<>();
        final List<DnsResourceRecord> usableKeys = new ArrayList<>();
        DnsResourceRecord unsupportedKey = null;
        for (int i = 0; i < rrsigs.size(); i++) {
            DnsResourceRecord rrsig = rrsigs.get(i);
            DnsResourceRecord key =
                    DnssecValidator.findMatchingDNSKEY(rrsig, dnskeys);
            if (key == null) {
                continue;
            }
            if (DnssecAlgorithm.fromNumber(rrsig.getRRSIGAlgorithm()) == null) {
                if (unsupportedKey == null) {
                    unsupportedKey = key;
                }
                if (LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.fine(MessageFormat.format(
                            L10N.getString("dnssec.unsupported_algorithm"),
                            rrsig.getRRSIGAlgorithm()));
                }
                continue;
            }
            usableSigs.add(rrsig);
            usableKeys.add(key);
        }

        if (usableSigs.isEmpty()) {
            if (unsupportedKey == null) {
                if (LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.fine(MessageFormat.format(
                            L10N.getString("dnssec.no_matching_key"),
                            rrsigs.get(0).getRRSIGKeyTag(), signerZone));
                }
                callback.onValidated(DnssecStatus.BOGUS, response);
                return;
            }
            // Every signature uses an algorithm this validator lacks. That
            // makes the zone unverifiable here, not forged; but whether it
            // is merely unsigned for us is for the authenticated DS RRset
            // to say, so go and ask it.
            List<DnsResourceRecord> entry = new ArrayList<>();
            entry.add(unsupportedKey);
            if (trustAnchor.isDNSKEYTrusted(signerZone, unsupportedKey)) {
                callback.onValidated(DnssecStatus.INSECURE, response);
                return;
            }
            if (depth >= MAX_CHAIN_DEPTH) {
                callback.onValidated(DnssecStatus.INDETERMINATE, response);
                return;
            }
            fetchDS(entry, true, signerZone, response,
                    insecureIfSecure(callback), depth);
            return;
        }

        final List<DnsResourceRecord> signedRRset = rrset;
        final List<DnsResourceRecord> records = keyRecords;
        final String zone = signerZone;
        final DnsMessage original = response;
        final DnssecValidationCallback cb = callback;
        final int level = depth;
        offload(new Callable<Integer>() {
            @Override
            public Integer call() {
                for (int i = 0; i < usableSigs.size(); i++) {
                    if (DnssecValidator.verifyRRSIG(signedRRset,
                            usableSigs.get(i), usableKeys.get(i))) {
                        return Integer.valueOf(i);
                    }
                }
                return Integer.valueOf(-1);
            }
        }, original, cb, new CryptoExecutor.Callback<Integer>() {
            @Override
            public void completed(Integer verified) {
                int which = verified.intValue();
                signatureChecked(which >= 0,
                        which >= 0 ? usableKeys.get(which) : null,
                        records, zone, original, cb, level);
            }

            @Override
            public void failed(Throwable error) {
                poolFailed(error, original, cb);
            }
        });
    }

    /**
     * Continues the chain walk once a signature over an RRset has been
     * checked: the key that verified it must now be shown to belong to the
     * zone, which is done by authenticating the zone's DNSKEY RRset.
     */
    private void signatureChecked(
            boolean valid,
            DnsResourceRecord signingKey,
            List<DnsResourceRecord> keyRecords,
            String signerZone,
            DnsMessage response,
            DnssecValidationCallback callback,
            int depth) {

        if (!valid) {
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine(MessageFormat.format(
                        L10N.getString("dnssec.bad_signature"),
                        signerZone));
            }
            callback.onValidated(DnssecStatus.BOGUS, response);
            return;
        }

        // The keys that sign the DNSKEY RRset are the ones a trust anchor or
        // DS record must vouch for. Without a valid signature over the
        // RRset the signing key has to be vouched for itself.
        List<DnsResourceRecord> dnskeys =
                DnssecValidator.filterByType(keyRecords, DnsType.DNSKEY);
        List<DnsResourceRecord> keySigs = new ArrayList<>();
        List<DnsResourceRecord> keySigKeys = new ArrayList<>();
        List<DnsResourceRecord> sigs = DnssecValidator.findRRSIGs(
                keyRecords, DnsType.DNSKEY.getValue());
        for (int i = 0; i < sigs.size(); i++) {
            DnsResourceRecord sig = sigs.get(i);
            if (!DnssecValidator.isRRSIGCurrent(sig, now())
                    || !signerZone.equalsIgnoreCase(sig.getRRSIGSignerName())
                    || DnssecAlgorithm.fromNumber(sig.getRRSIGAlgorithm()) == null) {
                continue;
            }
            DnsResourceRecord key =
                    DnssecValidator.findMatchingDNSKEY(sig, dnskeys);
            if (key != null) {
                keySigs.add(sig);
                keySigKeys.add(key);
            }
        }

        if (keySigs.isEmpty()) {
            List<DnsResourceRecord> entry = new ArrayList<>();
            entry.add(signingKey);
            entryKeysAuthenticated(entry, signerZone, response, callback, depth);
            return;
        }

        final DnsResourceRecord fallback = signingKey;
        final String zone = signerZone;
        final DnsMessage original = response;
        final DnssecValidationCallback cb = callback;
        final int level = depth;
        final List<DnsResourceRecord> keySet = dnskeys;
        final List<DnsResourceRecord> sigList = keySigs;
        final List<DnsResourceRecord> sigKeys = keySigKeys;
        offload(new Callable<List<DnsResourceRecord>>() {
            @Override
            public List<DnsResourceRecord> call() {
                List<DnsResourceRecord> signers = new ArrayList<>();
                for (int i = 0; i < sigList.size(); i++) {
                    DnsResourceRecord key = sigKeys.get(i);
                    if (!signers.contains(key) && DnssecValidator.verifyRRSIG(
                            keySet, sigList.get(i), key)) {
                        signers.add(key);
                    }
                }
                return signers;
            }
        }, original, cb, new CryptoExecutor.Callback<List<DnsResourceRecord>>() {
            @Override
            public void completed(List<DnsResourceRecord> signers) {
                if (signers.isEmpty()) {
                    signers = new ArrayList<>();
                    signers.add(fallback);
                }
                entryKeysAuthenticated(signers, zone, original, cb, level);
            }

            @Override
            public void failed(Throwable error) {
                poolFailed(error, original, cb);
            }
        });
    }

    /**
     * The entry keys are those that vouch for the zone's key set. They
     * are secure if a trust anchor holds one; otherwise the parent's DS
     * RRset must name one.
     */
    private void entryKeysAuthenticated(
            List<DnsResourceRecord> entryKeys,
            String signerZone,
            DnsMessage response,
            DnssecValidationCallback callback,
            int depth) {

        for (int i = 0; i < entryKeys.size(); i++) {
            if (trustAnchor.isDNSKEYTrusted(signerZone, entryKeys.get(i))) {
                callback.onValidated(DnssecStatus.SECURE, response);
                return;
            }
        }

        // A zone with a trust anchor is one that must validate; its keys not
        // matching the anchor is a failure, not a reason to look further. In
        // particular the root has no parent whose DS record could be asked
        // for, and the answer a resolver gets to that question (nothing)
        // would otherwise make a forged root key set look merely unsigned.
        if (!trustAnchor.getAnchors(signerZone).isEmpty()
                || !trustAnchor.getDNSKEYAnchors(signerZone).isEmpty()) {
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine(MessageFormat.format(
                        L10N.getString("dnssec.anchor_mismatch"), signerZone));
            }
            callback.onValidated(DnssecStatus.BOGUS, response);
            return;
        }

        if (depth >= MAX_CHAIN_DEPTH) {
            callback.onValidated(DnssecStatus.INDETERMINATE, response);
            return;
        }

        fetchDS(entryKeys, false, signerZone, response, callback, depth);
    }

    /** Maps a SECURE outcome to INSECURE and passes any other through. */
    private static DnssecValidationCallback insecureIfSecure(
            final DnssecValidationCallback inner) {
        return new DnssecValidationCallback() {
            @Override
            public void onValidated(DnssecStatus status, DnsMessage response) {
                inner.onValidated(status == DnssecStatus.SECURE
                        ? DnssecStatus.INSECURE : status, response);
            }
        };
    }

    /**
     * Runs a CPU-bound verification on the crypto pool and delivers the
     * outcome on the resolver's selector loop. Verification (RSA, ECDSA,
     * EdDSA) can take a noticeable fraction of a millisecond or more, so it
     * does not run on the loop. Without a loop or a crypto pool (a resolver
     * that has not been given a running Gumdrop) it runs inline.
     */
    private <T> void offload(Callable<T> operation, DnsMessage response,
                             DnssecValidationCallback callback,
                             CryptoExecutor.Callback<T> outcome) {
        final SelectorLoop loop = resolver.getSelectorLoop();
        Gumdrop gumdrop = (loop != null) ? loop.getGumdrop() : null;
        CryptoExecutor pool = (gumdrop != null)
                ? gumdrop.getCryptoExecutor() : null;
        if (pool == null) {
            T result;
            try {
                result = operation.call();
            } catch (Exception e) {
                outcome.failed(e);
                return;
            }
            outcome.completed(result);
            return;
        }
        pool.submit(new Executor() {
            @Override
            public void execute(Runnable command) {
                loop.invokeLater(command);
            }
        }, operation, outcome);
    }

    /**
     * The crypto pool refused or failed the work. That says nothing about
     * the data, so the result is indeterminate rather than bogus.
     */
    private void poolFailed(Throwable error, DnsMessage response,
                            DnssecValidationCallback callback) {
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.log(Level.FINE, L10N.getString("dnssec.verify_offload_failed"),
                    error);
        }
        callback.onValidated(DnssecStatus.INDETERMINATE, response);
    }

    /**
     * Fetches the DNSKEY RRset for a zone and continues validation.
     */
    private void fetchDNSKEY(
            final List<DnsResourceRecord> rrset,
            final List<DnsResourceRecord> rrsigs,
            final String signerZone,
            final DnsMessage response,
            final DnssecValidationCallback callback,
            final int depth) {

        resolver.query(signerZone, DnsType.DNSKEY,
                new DnsQueryCallback() {
                    @Override
                    public void onResponse(DnsMessage dnskeyResponse) {
                        List<DnsResourceRecord> keyRecords =
                                dnskeyResponse.getAnswers();
                        if (DnssecValidator.filterByType(
                                keyRecords, DnsType.DNSKEY).isEmpty()) {
                            callback.onValidated(
                                    DnssecStatus.INSECURE, response);
                            return;
                        }
                        verifyWithKeys(rrset, rrsigs, keyRecords,
                                signerZone, response, callback, depth);
                    }

                    @Override
                    public void onError(String error) {
                        if (LOGGER.isLoggable(Level.FINE)) {
                            LOGGER.fine(MessageFormat.format(
                                    L10N.getString(
                                            "dnssec.dnskey_fetch_failed"),
                                    signerZone, error));
                        }
                        callback.onValidated(
                                DnssecStatus.INDETERMINATE, response);
                    }
                });
    }

    /**
     * Fetches DS records from the parent zone and verifies that one of the
     * entry keys is authenticated by a DS, then continues the chain walk.
     *
     * @param unsupportedRoute true when the signatures could not be
     *        verified for want of a supported algorithm
     */
    private void fetchDS(
            final List<DnsResourceRecord> entryKeys,
            final boolean unsupportedRoute,
            final String zone,
            final DnsMessage response,
            final DnssecValidationCallback callback,
            final int depth) {

        resolver.query(zone, DnsType.DS,
                new DnsQueryCallback() {
                    @Override
                    public void onResponse(DnsMessage dsResponse) {
                        List<DnsResourceRecord> dsRecords =
                                DnssecValidator.filterByType(
                                        dsResponse.getAnswers(),
                                        DnsType.DS);
                        if (dsRecords.isEmpty()) {
                            callback.onValidated(
                                    DnssecStatus.INSECURE, response);
                            return;
                        }
                        verifyDSChain(entryKeys, unsupportedRoute, dsRecords,
                                zone, dsResponse, response, callback,
                                depth);
                    }

                    @Override
                    public void onError(String error) {
                        if (LOGGER.isLoggable(Level.FINE)) {
                            LOGGER.fine(MessageFormat.format(
                                    L10N.getString(
                                            "dnssec.ds_fetch_failed"),
                                    zone, error));
                        }
                        callback.onValidated(
                                DnssecStatus.INDETERMINATE, response);
                    }
                });
    }

    /**
     * Verifies that an entry key matches one of the DS records, then
     * validates the DS RRset itself and continues up the chain.
     */
    private void verifyDSChain(
            final List<DnsResourceRecord> entryKeys,
            final boolean unsupportedRoute,
            final List<DnsResourceRecord> dsRecords,
            final String zone,
            final DnsMessage dsResponse,
            final DnsMessage originalResponse,
            final DnssecValidationCallback callback,
            final int depth) {

        offload(new Callable<Boolean>() {
            @Override
            public Boolean call() {
                for (int k = 0; k < entryKeys.size(); k++) {
                    for (int i = 0; i < dsRecords.size(); i++) {
                        if (DnssecValidator.verifyDS(entryKeys.get(k),
                                dsRecords.get(i))) {
                            return Boolean.TRUE;
                        }
                    }
                }
                return Boolean.FALSE;
            }
        }, originalResponse, callback, new CryptoExecutor.Callback<Boolean>() {
            @Override
            public void completed(Boolean matched) {
                dsChecked(matched.booleanValue(), entryKeys, unsupportedRoute,
                        dsRecords, zone, dsResponse, originalResponse,
                        callback, depth);
            }

            @Override
            public void failed(Throwable error) {
                poolFailed(error, originalResponse, callback);
            }
        });
    }

    /**
     * Continues the chain walk once the DS digest check has finished.
     */
    private void dsChecked(
            boolean dsMatched,
            List<DnsResourceRecord> entryKeys,
            boolean unsupportedRoute,
            List<DnsResourceRecord> dsRecords,
            String zone,
            DnsMessage dsResponse,
            DnsMessage originalResponse,
            DnssecValidationCallback callback,
            int depth) {

        if (!hasUsableDS(dsRecords)) {
            // RFC 4035 section 5.2: no DS record uses an algorithm and
            // digest type this validator supports (SHA-1 and RSASHA1 are
            // not), so there is no usable path from the parent and the zone
            // is treated as unsigned. That is only safe once the DS RRset
            // itself is authenticated, or an attacker could downgrade a
            // signed zone by injecting one; so the chain walk continues and
            // a SECURE outcome becomes INSECURE.
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine(MessageFormat.format(
                        L10N.getString("dnssec.ds_no_supported_digest"), zone));
            }
            callback = insecureIfSecure(callback);
        } else if (unsupportedRoute || !dsMatched) {
            // The zone advertises a supported algorithm, so signatures made
            // only with an unsupported one (or a key the DS does not name)
            // are not to be taken as "merely unsupported".
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine(MessageFormat.format(
                        L10N.getString("dnssec.ds_mismatch"), zone));
            }
            callback.onValidated(DnssecStatus.BOGUS, originalResponse);
            return;
        }

        List<DnsResourceRecord> dsRRSIGs = currentSignatures(
                DnssecValidator.findRRSIGs(
                        dsResponse.getAnswers(),
                        DnsType.DS.getValue()));
        if (dsRRSIGs.isEmpty()) {
            for (int i = 0; i < entryKeys.size(); i++) {
                if (trustAnchor.isDNSKEYTrusted(zone, entryKeys.get(i))) {
                    callback.onValidated(
                            DnssecStatus.SECURE, originalResponse);
                    return;
                }
            }
            callback.onValidated(
                    DnssecStatus.INSECURE, originalResponse);
            return;
        }

        String parentZone = dsRRSIGs.get(0).getRRSIGSignerName();
        dsRRSIGs = sameSigner(dsRRSIGs, parentZone);

        fetchDNSKEYForParent(dsRecords, dsRRSIGs, parentZone,
                originalResponse, callback, depth + 1);
    }

    /**
     * Returns whether any DS record uses both an algorithm and a digest
     * type this validator supports.
     */
    private static boolean hasUsableDS(List<DnsResourceRecord> dsRecords) {
        for (int i = 0; i < dsRecords.size(); i++) {
            DnsResourceRecord ds = dsRecords.get(i);
            if (DnssecAlgorithm.dsDigestAlgorithm(ds.getDSDigestType()) != null
                    && DnssecAlgorithm.fromNumber(ds.getDSAlgorithm()) != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * Fetches the parent zone's DNSKEY to verify the DS RRSIG,
     * continuing the chain walk.
     */
    private void fetchDNSKEYForParent(
            final List<DnsResourceRecord> dsRecords,
            final List<DnsResourceRecord> dsRRSIGs,
            final String parentZone,
            final DnsMessage originalResponse,
            final DnssecValidationCallback callback,
            final int depth) {

        if (depth >= MAX_CHAIN_DEPTH) {
            callback.onValidated(
                    DnssecStatus.INDETERMINATE, originalResponse);
            return;
        }

        resolver.query(parentZone, DnsType.DNSKEY,
                new DnsQueryCallback() {
                    @Override
                    public void onResponse(DnsMessage dnskeyResponse) {
                        List<DnsResourceRecord> keyRecords =
                                dnskeyResponse.getAnswers();
                        if (DnssecValidator.filterByType(
                                keyRecords, DnsType.DNSKEY).isEmpty()) {
                            callback.onValidated(
                                    DnssecStatus.INSECURE,
                                    originalResponse);
                            return;
                        }
                        verifyWithKeys(dsRecords, dsRRSIGs, keyRecords,
                                parentZone, originalResponse,
                                callback, depth);
                    }

                    @Override
                    public void onError(String error) {
                        callback.onValidated(
                                DnssecStatus.INDETERMINATE,
                                originalResponse);
                    }
                });
    }

    /**
     * Returns the parent zone name by stripping the leftmost label.
     *
     * @param zone the zone name
     * @return the parent zone, or "." for a TLD
     */
    static String parentZone(String zone) {
        if (zone == null || zone.isEmpty() || ".".equals(zone)) {
            return ".";
        }
        int dot = zone.indexOf('.');
        if (dot < 0 || dot == zone.length() - 1) {
            return ".";
        }
        return zone.substring(dot + 1);
    }

}
