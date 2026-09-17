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

import org.bluezoo.gumdrop.dns.DnsMessage;
import org.bluezoo.gumdrop.dns.DnsQueryCallback;
import org.bluezoo.gumdrop.dns.DnsResourceRecord;
import org.bluezoo.gumdrop.dns.DnssecStatus;
import org.bluezoo.gumdrop.dns.DnsType;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
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
 * <li>Verify the RRSIG using the matching DNSKEY</li>
 * <li>Verify the DNSKEY against a DS record from the parent zone</li>
 * <li>Repeat up to the root trust anchor</li>
 * </ol>
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

        DnsResourceRecord rrsig = rrsigs.get(0);
        if (!DnssecValidator.isRRSIGCurrent(rrsig)) {
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine(MessageFormat.format(
                        L10N.getString("dnssec.expired_signature"),
                        rrsig.getRRSIGSignerName()));
            }
            callback.onValidated(DnssecStatus.BOGUS, response);
            return;
        }

        String signerZone = rrsig.getRRSIGSignerName();

        List<DnsResourceRecord> dnskeys =
                DnssecValidator.filterByType(answers, DnsType.DNSKEY);
        if (!dnskeys.isEmpty()) {
            verifyWithKeys(rrset, rrsig, dnskeys, signerZone,
                    response, callback, 0);
        } else {
            fetchDNSKEY(rrset, rrsig, signerZone, response,
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

        DnsResourceRecord rrsig = rrsigs.get(0);
        if (!DnssecValidator.isRRSIGCurrent(rrsig)) {
            callback.onValidated(DnssecStatus.BOGUS, response);
            return;
        }

        String signerZone = rrsig.getRRSIGSignerName();
        List<DnsResourceRecord> rrset;
        if (!nsecRecords.isEmpty()) {
            rrset = nsecRecords;
        } else {
            rrset = nsec3Records;
        }

        fetchDNSKEY(rrset, rrsig, signerZone, response, callback, 0);
    }

    /**
     * Attempts to verify the RRSIG using the supplied DNSKEY records,
     * then walks the chain of trust upward.
     */
    private void verifyWithKeys(
            List<DnsResourceRecord> rrset,
            DnsResourceRecord rrsig,
            List<DnsResourceRecord> dnskeys,
            String signerZone,
            DnsMessage response,
            DnssecValidationCallback callback,
            int depth) {

        DnsResourceRecord matchingKey =
                DnssecValidator.findMatchingDNSKEY(rrsig, dnskeys);
        if (matchingKey == null) {
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine(MessageFormat.format(
                        L10N.getString("dnssec.no_matching_key"),
                        rrsig.getRRSIGKeyTag(), signerZone));
            }
            callback.onValidated(DnssecStatus.BOGUS, response);
            return;
        }

        if (!DnssecValidator.verifyRRSIG(rrset, rrsig, matchingKey)) {
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine(MessageFormat.format(
                        L10N.getString("dnssec.bad_signature"),
                        signerZone));
            }
            callback.onValidated(DnssecStatus.BOGUS, response);
            return;
        }

        if (trustAnchor.isDNSKEYTrusted(signerZone, matchingKey)) {
            callback.onValidated(DnssecStatus.SECURE, response);
            return;
        }

        if (depth >= MAX_CHAIN_DEPTH) {
            callback.onValidated(DnssecStatus.INDETERMINATE, response);
            return;
        }

        fetchDS(matchingKey, dnskeys, signerZone, response,
                callback, depth);
    }

    /**
     * Fetches the DNSKEY RRset for a zone and continues validation.
     */
    private void fetchDNSKEY(
            final List<DnsResourceRecord> rrset,
            final DnsResourceRecord rrsig,
            final String signerZone,
            final DnsMessage response,
            final DnssecValidationCallback callback,
            final int depth) {

        resolver.query(signerZone, DnsType.DNSKEY,
                new DnsQueryCallback() {
                    @Override
                    public void onResponse(DnsMessage dnskeyResponse) {
                        List<DnsResourceRecord> dnskeys =
                                DnssecValidator.filterByType(
                                        dnskeyResponse.getAnswers(),
                                        DnsType.DNSKEY);
                        if (dnskeys.isEmpty()) {
                            callback.onValidated(
                                    DnssecStatus.INSECURE, response);
                            return;
                        }
                        verifyWithKeys(rrset, rrsig, dnskeys,
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
     * Fetches DS records from the parent zone and verifies that the
     * DNSKEY is authenticated by a DS, then continues the chain walk.
     */
    private void fetchDS(
            final DnsResourceRecord dnskey,
            final List<DnsResourceRecord> zoneDnskeys,
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
                        verifyDSChain(dnskey, zoneDnskeys, dsRecords,
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
     * Verifies that a DNSKEY matches one of the DS records, then
     * validates the DS RRset itself and continues up the chain.
     */
    private void verifyDSChain(
            DnsResourceRecord dnskey,
            List<DnsResourceRecord> zoneDnskeys,
            List<DnsResourceRecord> dsRecords,
            String zone,
            DnsMessage dsResponse,
            DnsMessage originalResponse,
            DnssecValidationCallback callback,
            int depth) {

        DnsResourceRecord ksk = findKSK(zoneDnskeys);
        if (ksk == null) {
            ksk = dnskey;
        }

        boolean dsMatched = false;
        for (int i = 0; i < dsRecords.size(); i++) {
            DnsResourceRecord ds = dsRecords.get(i);
            if (DnssecValidator.verifyDS(ksk, ds)) {
                dsMatched = true;
                break;
            }
        }

        if (!dsMatched) {
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine(MessageFormat.format(
                        L10N.getString("dnssec.ds_mismatch"), zone));
            }
            callback.onValidated(DnssecStatus.BOGUS, originalResponse);
            return;
        }

        List<DnsResourceRecord> dsRRSIGs =
                DnssecValidator.findRRSIGs(
                        dsResponse.getAnswers(),
                        DnsType.DS.getValue());
        if (dsRRSIGs.isEmpty()) {
            if (trustAnchor.isDNSKEYTrusted(zone, ksk)) {
                callback.onValidated(
                        DnssecStatus.SECURE, originalResponse);
            } else {
                callback.onValidated(
                        DnssecStatus.INSECURE, originalResponse);
            }
            return;
        }

        DnsResourceRecord dsRRSIG = dsRRSIGs.get(0);
        String parentZone = dsRRSIG.getRRSIGSignerName();

        fetchDNSKEYForParent(dsRecords, dsRRSIG, parentZone,
                originalResponse, callback, depth + 1);
    }

    /**
     * Fetches the parent zone's DNSKEY to verify the DS RRSIG,
     * continuing the chain walk.
     */
    private void fetchDNSKEYForParent(
            final List<DnsResourceRecord> dsRecords,
            final DnsResourceRecord dsRRSIG,
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
                        List<DnsResourceRecord> parentKeys =
                                DnssecValidator.filterByType(
                                        dnskeyResponse.getAnswers(),
                                        DnsType.DNSKEY);
                        if (parentKeys.isEmpty()) {
                            callback.onValidated(
                                    DnssecStatus.INSECURE,
                                    originalResponse);
                            return;
                        }
                        verifyWithKeys(dsRecords, dsRRSIG, parentKeys,
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
     * Finds the KSK (Secure Entry Point) from a list of DNSKEYs.
     * Falls back to any zone-signing key if no SEP is found.
     */
    private static DnsResourceRecord findKSK(
            List<DnsResourceRecord> dnskeys) {
        for (int i = 0; i < dnskeys.size(); i++) {
            DnsResourceRecord key = dnskeys.get(i);
            if (key.getType() == DnsType.DNSKEY
                    && key.isDNSKEYSecureEntryPoint()) {
                return key;
            }
        }
        return null;
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
