/*
 * ArcSealer.java
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

import java.security.PrivateKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Seals a message with a new ARC instance after local authentication
 * (RFC 8617 intermediary / relay role).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ArcValidator
 * @see <a href="https://www.rfc-editor.org/rfc/rfc8617">RFC 8617 - ARC</a>
 */
public class ArcSealer {

    private static final List<String> DEFAULT_AMS_HEADERS = Arrays.asList(
            "from", "to", "subject", "date", "message-id");

    private final DkimSigner signer;
    private String authservId = "localhost";

    /**
     * Creates a sealer with ARC signing credentials (same shape as DKIM).
     *
     * @param privateKey the signing key
     * @param domain the signing domain ({@code d=})
     * @param selector the DNS selector ({@code s=})
     */
    public ArcSealer(PrivateKey privateKey, String domain, String selector) {
        this.signer = new DkimSigner(privateKey, domain, selector);
    }

    /**
     * Sets the authserv-id placed in {@code ARC-Authentication-Results}.
     *
     * @param authservId identifier of this server (default {@code localhost})
     */
    public void setAuthservId(String authservId) {
        this.authservId = authservId;
    }

    /**
     * Sets header canonicalization for AMS and AS ({@code simple} or {@code relaxed}).
     *
     * @param canon canonicalization name per RFC 6376
     */
    public void setHeaderCanonicalization(String canon) {
        signer.setHeaderCanonicalization(canon);
    }

    /**
     * Sets body canonicalization used for the shared {@code bh=} on this hop.
     *
     * @param canon canonicalization name per RFC 6376
     */
    public void setBodyCanonicalization(String canon) {
        signer.setBodyCanonicalization(canon);
    }

    /**
     * Feeds one body line for hashing (RFC 6376), in wire format including CRLF.
     *
     * @param line body line bytes
     * @param offset start offset in {@code line}
     * @param length number of bytes to hash
     */
    public void bodyLine(byte[] line, int offset, int length) {
        signer.bodyLine(line, offset, length);
    }

    /**
     * Signals that the entire message body has been supplied to {@link #bodyLine}.
     */
    public void endBody() {
        signer.endBody();
    }

    /**
     * Builds a new ARC set ({@code i=N}) for this hop.
     *
     * <p>Call {@link #bodyLine} and {@link #endBody} on the same message body
     * before sealing. The returned lines should be prepended to the message
     * (AAR, then AMS, then AS) before relay.
     *
     * @param existingChain prior validated sets on the message (may be empty)
     * @param messageHeaders non-ARC header lines (CRLF-terminated), in order
     * @param incomingCv validation result for {@code existingChain}; use
     *                     {@link ArcCvResult#NONE} when {@code existingChain}
     *                     is empty
     * @param authResults authentication results for this hop; instance and
     *                    authserv-id are updated automatically
     * @return three header lines: AAR, AMS, AS (each with CRLF)
     * @throws Exception if signing fails
     */
    public List<String> seal(List<ArcSet> existingChain, List<String> messageHeaders,
                             ArcCvResult incomingCv,
                             ArcAuthenticationResults authResults) throws Exception {
        int instance = existingChain.size() + 1;
        authResults.setInstance(instance);
        authResults.setAuthservId(authservId);
        String aarLine = authResults.formatHeaderLine();

        List<String> arcBlock = new ArrayList<String>();
        for (int i = 0; i < existingChain.size(); i++) {
            ArcSet set = existingChain.get(i);
            arcBlock.add(set.getAuthenticationResults());
            arcBlock.add(set.getMessageSignature());
            arcBlock.add(set.getSeal());
        }

        List<String> headersForSign = new ArrayList<String>();
        headersForSign.addAll(arcBlock);
        headersForSign.add(aarLine);
        headersForSign.addAll(messageHeaders);

        String bodyHashB64 = signer.computeBodyHashBase64();

        String amsLine = signer.signArc(headersForSign, "ARC-Message-Signature",
                instance, DEFAULT_AMS_HEADERS, bodyHashB64, null);

        List<String> headersForSeal = new ArrayList<String>(headersForSign);
        headersForSeal.add(amsLine);

        List<String> sealH = buildSealSignedHeaderNames(instance);
        ArcCvResult cv = existingChain.isEmpty() ? ArcCvResult.NONE : incomingCv;
        String asLine = signer.signArc(headersForSeal, "ARC-Seal", instance, sealH,
                bodyHashB64, cv);

        List<String> result = new ArrayList<String>(3);
        result.add(aarLine);
        result.add(amsLine);
        result.add(asLine);
        return result;
    }

    private static List<String> buildSealSignedHeaderNames(int instance) {
        List<String> names = new ArrayList<String>();
        for (int j = 1; j <= instance; j++) {
            names.add("arc-seal");
            names.add("arc-message-signature");
            names.add("arc-authentication-results");
        }
        return names;
    }
}
