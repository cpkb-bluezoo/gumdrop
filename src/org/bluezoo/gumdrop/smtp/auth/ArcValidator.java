/*
 * ArcValidator.java
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

package org.bluezoo.gumdrop.smtp.auth;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bluezoo.gumdrop.dns.client.DnsResolver;

/**
 * Validates an Authenticated Received Chain on a message (RFC 8617
 * receiving MTA role). Verification is asynchronous and callback-driven.
 *
 * <p>ARC headers are grouped incrementally via {@link #getArcHeaderParser()}
 * while {@link DkimMessageParser} receives the message. Cryptographic
 * verification starts when {@link #verify(ArcCallback)} is called after
 * end-of-data.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ArcHeaderParser
 * @see ArcSealer
 * @see <a href="https://www.rfc-editor.org/rfc/rfc8617">RFC 8617 - ARC</a>
 */
public class ArcValidator {

    private final DnsResolver resolver;
    private final ArcHeaderParser arcHeaderParser;
    private final ChainBuilder chainBuilder = new ChainBuilder();

    private DkimMessageParser messageParser;
    private byte[] bodyHash;

    /**
     * Creates an ARC validator.
     *
     * @param resolver DNS resolver for ARC key lookups
     */
    public ArcValidator(DnsResolver resolver) {
        this.resolver = resolver;
        this.arcHeaderParser = new ArcHeaderParser(chainBuilder);
    }

    /**
     * Returns the push-parser to register on {@link DkimMessageParser}.
     *
     * @return header parser sharing this validator's chain state
     */
    public ArcHeaderParser getArcHeaderParser() {
        return arcHeaderParser;
    }

    /**
     * Clears chain state for the next message on this pipeline.
     */
    public void resetForMessage() {
        chainBuilder.reset();
        arcHeaderParser.reset();
    }

    /**
     * Sets the parser that captured the message headers and body hash inputs.
     *
     * @param parser parser that finished processing the message
     */
    public void setMessageParser(DkimMessageParser parser) {
        this.messageParser = parser;
    }

    /**
     * Sets the message body hash used to verify each {@code ARC-Message-Signature}.
     *
     * <p>Must be the same bytes returned once from
     * {@link DkimMessageParser#getBodyHash()}; the digest must not be computed
     * twice on the same parser.
     *
     * @param hash SHA-256 body hash, or null to skip body-hash checks
     */
    public void setBodyHash(byte[] hash) {
        this.bodyHash = hash;
    }

    /**
     * Validates the ARC chain and invokes the callback on completion.
     *
     * <p>Verification is fully asynchronous: the callback runs after DNS lookups
     * for each AMS and AS complete. Instances are checked in order from
     * {@code i=1} upward.
     *
     * @param callback receives the outcome; must not be null
     */
    public void verify(final ArcCallback callback) {
        if (messageParser == null) {
            callback.arcResult(new ArcValidationResult(
                    ArcCvResult.NONE, false, null));
            return;
        }
        if (!chainBuilder.isHeadersEnded()) {
            arcHeaderParser.endHeaders();
        }
        if (chainBuilder.isMalformed()) {
            callback.arcResult(new ArcValidationResult(
                    ArcCvResult.FAIL, true, chainBuilder.getSets()));
            return;
        }
        List<ArcSet> sets = chainBuilder.getSets();
        if (sets.isEmpty()) {
            callback.arcResult(new ArcValidationResult(
                    ArcCvResult.NONE, false, sets));
            return;
        }
        verifySetAt(sets, 0, callback);
    }

    private void verifySetAt(final List<ArcSet> sets, final int index,
                             final ArcCallback callback) {
        if (index >= sets.size()) {
            callback.arcResult(new ArcValidationResult(
                    ArcCvResult.PASS, false, sets));
            return;
        }
        final ArcSet set = sets.get(index);
        if (set.getParsedMessageSignature() == null
                || set.getParsedSeal() == null) {
            callback.arcResult(new ArcValidationResult(
                    ArcCvResult.FAIL, true, sets));
            return;
        }
        DkimValidator dkimValidator = new DkimValidator(resolver);
        dkimValidator.setMessageParser(messageParser);
        dkimValidator.setBodyHash(bodyHash);
        dkimValidator.verifyHeaderSignature(set.getParsedMessageSignature(),
                set.getMessageSignature(), new DkimCallback() {
                    @Override
                    public void dkimResult(DkimResult result, String signingDomain,
                                           String selector) {
                        if (result != DkimResult.PASS) {
                            callback.arcResult(new ArcValidationResult(
                                    ArcCvResult.FAIL, false, sets));
                            return;
                        }
                        verifySeal(sets, index, callback);
                    }
                });
    }

    private void verifySeal(final List<ArcSet> sets, final int index,
                            final ArcCallback callback) {
        final ArcSet set = sets.get(index);
        DkimValidator sealValidator = new DkimValidator(resolver);
        sealValidator.setMessageParser(messageParser);
        sealValidator.setBodyHash(bodyHash);
        sealValidator.verifyHeaderSignature(set.getParsedSeal(), set.getSeal(),
                new DkimCallback() {
                    @Override
                    public void dkimResult(DkimResult result, String signingDomain,
                                           String selector) {
                        if (result != DkimResult.PASS) {
                            callback.arcResult(new ArcValidationResult(
                                    ArcCvResult.FAIL, false, sets));
                            return;
                        }
                        verifySetAt(sets, index + 1, callback);
                    }
                });
    }

    /**
     * Accumulates ARC header events from {@link ArcHeaderParser}.
     */
    private final class ChainBuilder implements ArcHeaderHandler {

        private final Map<Integer, MutableSet> byInstance =
                new HashMap<Integer, MutableSet>();
        private List<ArcSet> sets = Collections.emptyList();
        private boolean malformed;
        private boolean headersEnded;

        void reset() {
            byInstance.clear();
            sets = Collections.emptyList();
            malformed = false;
            headersEnded = false;
        }

        boolean isHeadersEnded() {
            return headersEnded;
        }

        boolean isMalformed() {
            return malformed;
        }

        List<ArcSet> getSets() {
            return sets;
        }

        @Override
        public void arcAuthenticationResults(int instance, String rawLine) {
            mutableSet(instance).aar = rawLine;
        }

        @Override
        public void arcMessageSignature(int instance, String rawLine,
                                        DkimSignature parsed) {
            MutableSet set = mutableSet(instance);
            set.ams = rawLine;
            set.parsedAms = parsed;
        }

        @Override
        public void arcSeal(int instance, String rawLine, DkimSignature parsed,
                            ArcCvResult sealCv) {
            MutableSet set = mutableSet(instance);
            set.as = rawLine;
            set.parsedAs = parsed;
            set.sealCv = sealCv;
        }

        @Override
        public void arcHeadersEnd() {
            headersEnded = true;
            if (byInstance.isEmpty()) {
                sets = Collections.emptyList();
                return;
            }
            int max = 0;
            for (Integer key : byInstance.keySet()) {
                if (key.intValue() > max) {
                    max = key.intValue();
                }
            }
            for (int inst = 1; inst <= max; inst++) {
                MutableSet set = byInstance.get(Integer.valueOf(inst));
                if (set == null || set.aar == null || set.ams == null
                        || set.as == null) {
                    malformed = true;
                    sets = Collections.emptyList();
                    return;
                }
            }
            List<ArcSet> built = new ArrayList<ArcSet>();
            for (int inst = 1; inst <= max; inst++) {
                MutableSet m = byInstance.get(Integer.valueOf(inst));
                built.add(new ArcSet(inst, m.aar, m.ams, m.as, m.sealCv,
                        m.parsedAms, m.parsedAs));
            }
            sets = Collections.unmodifiableList(built);
        }

        private MutableSet mutableSet(int instance) {
            Integer key = Integer.valueOf(instance);
            MutableSet set = byInstance.get(key);
            if (set == null) {
                set = new MutableSet();
                byInstance.put(key, set);
            }
            return set;
        }
    }

    private static final class MutableSet {
        String aar;
        String ams;
        String as;
        ArcCvResult sealCv = ArcCvResult.NONE;
        DkimSignature parsedAms;
        DkimSignature parsedAs;
    }
}
