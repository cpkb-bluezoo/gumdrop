/*
 * DkimValidator.java
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

package org.bluezoo.gumdrop.smtp.auth;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.EdECPoint;
import java.security.spec.EdECPublicKeySpec;
import java.security.spec.NamedParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.util.ByteArrays;
import org.bluezoo.gumdrop.dns.DnsMessage;
import org.bluezoo.gumdrop.dns.DnsQueryCallback;
import org.bluezoo.gumdrop.dns.client.DnsResolver;
import org.bluezoo.gumdrop.dns.DnsResourceRecord;
import org.bluezoo.gumdrop.dns.DnsType;

/**
 * DKIM (DomainKeys Identified Mail) validator as defined in RFC 6376.
 *
 * <p>DKIM validates that a message was signed by the claimed domain and
 * that the message content has not been modified. This implementation
 * is fully asynchronous, using callbacks for DNS lookups.
 *
 * <p>The validator uses a {@link DkimMessageParser} to capture raw header
 * bytes for proper DKIM canonicalization. The body hash is computed
 * separately from the raw message bytes.
 *
 * <p>Example usage:
 * <pre><code>
 * DnsResolver resolver = new DnsResolver();
 * resolver.useSystemResolvers();
 * resolver.open();
 *
 * // Parse message with DkimMessageParser
 * DkimMessageParser parser = new DkimMessageParser();
 * parser.receive(messageData);
 * parser.close();
 *
 * DkimValidator dkim = new DkimValidator(resolver);
 * dkim.setMessageParser(parser);
 * dkim.setBodyHash(computedBodyHash);
 *
 * // Verify
 * dkim.verify(new DkimCallback() {
 *     &#64;Override
 *     public void dkimResult(DkimResult result, String domain, String selector) {
 *         if (result == DkimResult.PASS) {
 *             // Signature verified
 *         }
 *     }
 * });
 * </code></pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see DkimMessageParser
 * @see <a href="https://www.rfc-editor.org/rfc/rfc6376">RFC 6376 - DKIM</a>
 */
public class DkimValidator {

    private static final Logger LOGGER = Logger.getLogger(DkimValidator.class.getName());
    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.smtp.auth.L10N");

    private final DnsResolver resolver;

    private DkimMessageParser messageParser;
    private DkimSignature signature;
    private byte[] bodyHash;

    /** When set, used instead of {@code getRawHeader("dkim-signature")}. */
    private String explicitSignatureHeaderLine;

    /**
     * Creates a new DKIM validator using the specified DNS resolver.
     *
     * @param resolver the DNS resolver to use for public key lookups
     */
    public DkimValidator(DnsResolver resolver) {
        this.resolver = resolver;
    }

    /**
     * Sets the message parser that captured raw header bytes.
     *
     * <p>The parser should have already processed the message headers.
     * The validator uses the raw header bytes from the parser for proper
     * DKIM canonicalization.
     *
     * @param parser the DKIM message parser with captured headers
     */
    public void setMessageParser(DkimMessageParser parser) {
        this.messageParser = parser;
        // Get the signature from the parser
        if (this.signature == null) {
            this.signature = parser.getDKIMSignature();
        }
    }

    /**
     * Sets the computed body hash for {@code bh=} verification.
     *
     * <p>The hash must match the algorithm in the signature (SHA-256 for
     * {@code rsa-sha256}) after body canonicalization. For ARC, pass the same
     * digest used for all {@code ARC-Message-Signature} checks on the message.
     *
     * @param hash the computed body hash, or null to skip body-hash comparison
     */
    public void setBodyHash(byte[] hash) {
        this.bodyHash = hash;
    }

    /**
     * Returns the parsed DKIM signature, or null if none found.
     *
     * @return the DKIM signature
     */
    public DkimSignature getSignature() {
        if (signature == null && messageParser != null) {
            signature = messageParser.getDKIMSignature();
        }
        return signature;
    }

    /**
     * Verifies a parsed signature using an explicit raw header line (for
     * example {@code ARC-Message-Signature} or {@code ARC-Seal} per RFC 8617).
     *
     * <p>The parser must still be set via {@link #setMessageParser} so signed
     * headers listed in {@code h=} can be loaded. {@link #setBodyHash} should
     * be set when the signature includes a {@code bh=} tag.
     *
     * @param parsed the parsed signature tags
     * @param rawSignatureHeaderLine the full header line as received (including
     *                               field name and line ending)
     * @param callback the result callback
     */
    public void verifyHeaderSignature(DkimSignature parsed,
                                      String rawSignatureHeaderLine,
                                      final DkimCallback callback) {
        this.signature = parsed;
        this.explicitSignatureHeaderLine = rawSignatureHeaderLine;
        verify(callback);
    }

    /**
     * Verifies the DKIM signature asynchronously (RFC 6376 §6).
     *
     * <p>Uses {@link #getSignature()} from the message parser unless
     * {@link #verifyHeaderSignature} was called first for an ARC header.
     *
     * @param callback the callback to receive the result
     */
    public void verify(final DkimCallback callback) {
        // Get signature from parser if not already set
        if (signature == null && messageParser != null) {
            signature = messageParser.getDKIMSignature();
        }

        if (signature == null) {
            callback.dkimResult(DkimResult.NONE, null, null);
            return;
        }

        if (messageParser == null) {
            // No raw header bytes available
            callback.dkimResult(DkimResult.PERMERROR, signature.getDomain(),
                    signature.getSelector());
            return;
        }

        // RFC 6376 does not require h= to cover From, but a PASS here is
        // relied on elsewhere (DmarcValidator) to authenticate the message's
        // From domain. A signature that never covers From can be replayed
        // unmodified under an arbitrary From address at the same signing
        // domain, so treat it as unusable rather than PASS.
        boolean arcSeal = explicitSignatureHeaderLine != null
                && explicitSignatureHeaderLine.toLowerCase()
                        .startsWith("arc-seal:");
        if (!arcSeal && !signature.getSignedHeaders().contains("from")) {
            callback.dkimResult(DkimResult.PERMERROR, signature.getDomain(),
                    signature.getSelector());
            return;
        }

        // Check if signature has expired
        long now = System.currentTimeMillis() / 1000;
        if (signature.getExpiration() > 0 && now > signature.getExpiration()) {
            callback.dkimResult(DkimResult.FAIL, signature.getDomain(),
                    signature.getSelector());
            return;
        }

        // Verify body hash first
        if (bodyHash != null) {
            String expectedHash = signature.getBodyHash();
            if (!ByteArrays.equalsConstantTime(
                    Base64.getDecoder().decode(expectedHash), bodyHash)) {
                callback.dkimResult(DkimResult.FAIL, signature.getDomain(),
                        signature.getSelector());
                return;
            }
        }

        // Look up public key
        String queryName = signature.getKeyQueryName();
        resolver.queryTXT(queryName, new DnsQueryCallback() {
            @Override
            public void onResponse(DnsMessage response) {
                handleKeyResponse(response, callback);
            }

            @Override
            public void onError(String error) {
                callback.dkimResult(DkimResult.TEMPERROR, signature.getDomain(),
                        signature.getSelector());
            }
        });
    }

    /**
     * Handles the DNS response for the public key lookup.
     * RFC 6376 §6.1.2 — key retrieval via DNS TXT.
     */
    private void handleKeyResponse(DnsMessage response, DkimCallback callback) {
        // Check for errors
        int rcode = response.getRcode();
        if (rcode == DnsMessage.RCODE_NXDOMAIN) {
            callback.dkimResult(DkimResult.PERMERROR, signature.getDomain(),
                    signature.getSelector());
            return;
        }

        if (rcode != DnsMessage.RCODE_NOERROR) {
            callback.dkimResult(DkimResult.TEMPERROR, signature.getDomain(),
                    signature.getSelector());
            return;
        }

        // Find the public key record
        String keyRecord = null;
        List<DnsResourceRecord> answers = response.getAnswers();
        for (int i = 0; i < answers.size(); i++) {
            DnsResourceRecord rr = answers.get(i);
            if (rr.getType() == DnsType.TXT) {
                String txt = rr.getText();
                if (txt != null && txt.contains("p=")) {
                    keyRecord = txt;
                    break;
                }
            }
        }

        if (keyRecord == null) {
            callback.dkimResult(DkimResult.PERMERROR, signature.getDomain(),
                    signature.getSelector());
            return;
        }

        // Parse the key record
        PublicKey publicKey = parsePublicKey(keyRecord);
        if (publicKey == null) {
            callback.dkimResult(DkimResult.PERMERROR, signature.getDomain(),
                    signature.getSelector());
            return;
        }

        // Verify the signature
        try {
            boolean verified = verifySignature(publicKey);
            DkimResult result = verified ? DkimResult.PASS : DkimResult.FAIL;
            callback.dkimResult(result, signature.getDomain(), signature.getSelector());
        } catch (Exception e) {
            LOGGER.log(Level.FINE, L10N.getString("debug.dkim_verify_error"), e);
            callback.dkimResult(DkimResult.PERMERROR, signature.getDomain(),
                    signature.getSelector());
        }
    }

    /**
     * Parses a public key from a DKIM DNS record.
     */
    private PublicKey parsePublicKey(String record) {
        // Parse tag=value pairs
        String keyData = null;
        String keyType = "rsa";

        String[] parts = splitOnSemicolons(record);
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i].trim();
            int eqPos = part.indexOf('=');
            if (eqPos <= 0) {
                continue;
            }

            String tag = part.substring(0, eqPos).trim();
            String value = part.substring(eqPos + 1).trim();

            if ("p".equals(tag)) {
                keyData = removeWhitespace(value);
            } else if ("k".equals(tag)) {
                keyType = value.toLowerCase();
            }
        }

        if (keyData == null || keyData.isEmpty()) {
            // Empty p= means key has been revoked
            return null;
        }

        try {
            byte[] keyBytes = Base64.getDecoder().decode(keyData);

            if ("ed25519".equals(keyType)) {
                // RFC 8463 §4 — Ed25519 public keys are raw 32-byte values.
                return parseEd25519PublicKey(keyBytes);
            } else {
                X509EncodedKeySpec keySpec = new X509EncodedKeySpec(keyBytes);
                KeyFactory keyFactory = KeyFactory.getInstance("RSA");
                return keyFactory.generatePublic(keySpec);
            }
        } catch (Exception e) {
            LOGGER.log(Level.FINE, L10N.getString("debug.dkim_parse_key_error"), e);
            return null;
        }
    }

    /**
     * RFC 8463 §4 — parses a raw Ed25519 public key.
     * The key in DNS is the raw 32-byte public point, which must be
     * converted into a Java EdECPublicKeySpec.
     */
    private PublicKey parseEd25519PublicKey(byte[] rawKey) throws Exception {
        if (rawKey.length == 32) {
            // Raw 32-byte key per RFC 8463 — convert to EdECPublicKeySpec
            boolean xOdd = (rawKey[31] & 0x80) != 0;
            byte[] yBytes = rawKey.clone();
            yBytes[31] &= 0x7F; // clear sign bit
            // Reverse to big-endian
            for (int i = 0; i < yBytes.length / 2; i++) {
                byte tmp = yBytes[i];
                yBytes[i] = yBytes[yBytes.length - 1 - i];
                yBytes[yBytes.length - 1 - i] = tmp;
            }
            EdECPoint point = new EdECPoint(xOdd, new java.math.BigInteger(1, yBytes));
            EdECPublicKeySpec spec = new EdECPublicKeySpec(NamedParameterSpec.ED25519, point);
            KeyFactory kf = KeyFactory.getInstance("Ed25519");
            return kf.generatePublic(spec);
        }
        // Fallback: try X509EncodedKeySpec (wrapped key)
        X509EncodedKeySpec keySpec = new X509EncodedKeySpec(rawKey);
        KeyFactory kf = KeyFactory.getInstance("Ed25519");
        return kf.generatePublic(keySpec);
    }

    /**
     * Verifies the signature using the public key.
     * RFC 6376 §6.1 — extract and verify.
     */
    private boolean verifySignature(PublicKey publicKey) throws Exception {
        // Build the header hash
        String headerData = buildHeaderHash();

        // Determine signature algorithm
        String alg = signature.getAlgorithm();
        String sigAlgorithm;
        if (alg.startsWith("rsa-sha256")) {
            sigAlgorithm = "SHA256withRSA";
        } else if (alg.startsWith("ed25519")) {
            sigAlgorithm = "Ed25519";
        } else {
            return false;
        }

        // Decode the signature
        byte[] sigBytes = Base64.getDecoder().decode(signature.getSignature());

        // Verify
        Signature sig = Signature.getInstance(sigAlgorithm);
        sig.initVerify(publicKey);
        sig.update(headerData.getBytes("UTF-8"));
        return sig.verify(sigBytes);
    }

    /**
     * Builds the header data for signature verification.
     * RFC 6376 §3.4 — canonicalization.
     *
     * <p>This uses the raw header bytes captured by {@link DkimMessageParser}
     * and applies the appropriate canonicalization (simple or relaxed).
     */
    private String buildHeaderHash() {
        StringBuilder sb = new StringBuilder();
        String headerCanon = signature.getHeaderCanonicalization();
        boolean relaxed = "relaxed".equals(headerCanon);

        // Add signed headers in the order specified
        List<String> signedHeaders = signature.getSignedHeaders();
        Map<String, Integer> usedCount = new HashMap<String, Integer>();
        boolean explicitArcSeal = explicitSignatureHeaderLine != null
                && explicitSignatureHeaderLine.toLowerCase()
                        .startsWith("arc-seal:");

        for (int i = 0; i < signedHeaders.size(); i++) {
            String headerName = signedHeaders.get(i);
            List<DkimMessageParser.RawHeader> rawHeaders = 
                    messageParser.getAllRawHeaders(headerName);

            if (rawHeaders.isEmpty()) {
                continue;
            }

            // DKIM uses headers from bottom to top - start from last occurrence
            Integer used = usedCount.get(headerName);
            int idx = (used == null) ? rawHeaders.size() - 1 : used - 1;
            if (idx < 0) {
                continue;
            }
            usedCount.put(headerName, idx);

            DkimMessageParser.RawHeader rawHeader = rawHeaders.get(idx);
            if (explicitArcSeal && "arc-seal".equals(headerName)) {
                String raw = relaxed ? rawHeader.asStringUnfolded()
                        : rawHeader.asString();
                sb.append(canonicalizeSignedHeaderField(raw, relaxed));
            } else {
                String line = canonicalizeRawHeader(rawHeader, relaxed);
                sb.append(line);
            }
        }

        // RFC 8617 ARC-Seal: h= already lists arc-seal with b= removed in the loop.
        boolean arcSealSignedInH = explicitArcSeal
                && signedHeaders.contains("arc-seal");
        if (!arcSealSignedInH) {
            String dkimHeader = canonicalizeDKIMHeader(relaxed);
            sb.append(dkimHeader);
        }

        return sb.toString();
    }

    /**
     * Canonicalizes a raw header for signature verification.
     * RFC 6376 §3.4 — canonicalization.
     *
     * @param rawHeader the raw header with captured bytes
     * @param relaxed true for relaxed canonicalization, false for simple
     * @return the canonicalized header string
     */
    private String canonicalizeRawHeader(DkimMessageParser.RawHeader rawHeader, boolean relaxed) {
        if (relaxed) {
            // Relaxed: unfold, lowercase name, compress whitespace
            String unfolded = rawHeader.asStringUnfolded();
            return relaxedCanonicalizeHeader(unfolded);
        } else {
            // Simple: use raw bytes as-is (includes CRLF at end)
            return rawHeader.asString();
        }
    }

    /**
     * Applies relaxed canonicalization to a header string.
     *
     * <p>Per RFC 6376:
     * <ul>
     *   <li>Lowercase the header name</li>
     *   <li>Remove whitespace after colon, before value</li>
     *   <li>Compress all whitespace sequences to a single space</li>
     *   <li>Remove trailing whitespace before CRLF</li>
     * </ul>
     */
    private String relaxedCanonicalizeHeader(String header) {
        // Find colon
        int colonPos = header.indexOf(':');
        if (colonPos <= 0) {
            return header;
        }

        String name = header.substring(0, colonPos).toLowerCase().trim();
        String value = header.substring(colonPos + 1);
        String canonValue = unfoldAndCompress(value);

        return name + ":" + canonValue + "\r\n";
    }

    /**
     * Canonicalizes the DKIM-Signature header for verification.
     * The b= tag value is removed (replaced with empty).
     */
    private String canonicalizeDKIMHeader(boolean relaxed) {
        String header;
        if (explicitSignatureHeaderLine != null) {
            header = explicitSignatureHeaderLine;
            if (relaxed) {
                header = unfoldHeaderLine(header);
            }
            return canonicalizeSignedHeaderField(header, relaxed);
        } else {
            DkimMessageParser.RawHeader rawHeader =
                    messageParser.getRawHeader("dkim-signature");
            if (rawHeader == null) {
                return "";
            }
            if (relaxed) {
                header = rawHeader.asStringUnfolded();
            } else {
                header = rawHeader.asString();
            }
            return canonicalizeSignedHeaderField(header, relaxed);
        }
    }

    /**
     * Canonicalizes a signature header field (DKIM-Signature, ARC-Seal, etc.)
     * with the {@code b=} value removed.
     */
    private String canonicalizeSignedHeaderField(String header, boolean relaxed) {
        int bPos = header.indexOf("b=");
        if (bPos < 0) {
            return stripTrailingCRLF(relaxed ? relaxedCanonicalizeHeader(header)
                    : header);
        }
        int endPos = bPos + 2;
        while (endPos < header.length()) {
            char c = header.charAt(endPos);
            if (c == ';') {
                break;
            }
            endPos++;
        }
        String beforeB = header.substring(0, bPos + 2);
        String afterB = (endPos < header.length()) ? header.substring(endPos) : "";
        String modified = beforeB + afterB;
        if (relaxed) {
            return stripTrailingCRLF(relaxedCanonicalizeHeader(modified));
        }
        return stripTrailingCRLF(modified);
    }

    /**
     * Removes trailing CRLF or LF from a string.
     */
    private static String unfoldHeaderLine(String header) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < header.length(); i++) {
            char c = header.charAt(i);
            if (c != '\r' && c != '\n') {
                sb.append(c);
            }
        }
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) == ' ') {
            sb.setLength(sb.length() - 1);
        }
        return sb.toString();
    }

    private String stripTrailingCRLF(String s) {
        int len = s.length();
        if (len >= 2 && s.charAt(len - 2) == '\r' && s.charAt(len - 1) == '\n') {
            return s.substring(0, len - 2);
        }
        if (len >= 1 && s.charAt(len - 1) == '\n') {
            return s.substring(0, len - 1);
        }
        return s;
    }

    /**
     * Unfolds and compresses whitespace in a header value.
     */
    private String unfoldAndCompress(String s) {
        StringBuilder sb = new StringBuilder();
        boolean prevSpace = false;
        boolean started = false;

        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\r' || c == '\n' || c == ' ' || c == '\t') {
                if (started && !prevSpace) {
                    sb.append(' ');
                    prevSpace = true;
                }
            } else {
                sb.append(c);
                prevSpace = false;
                started = true;
            }
        }

        // Trim trailing space
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) == ' ') {
            sb.setLength(sb.length() - 1);
        }

        return sb.toString();
    }

    // -- Helper Methods --

    private static String[] splitOnSemicolons(String s) {
        List<String> parts = new ArrayList<String>();
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == ';') {
                parts.add(s.substring(start, i));
                start = i + 1;
            }
        }
        if (start < s.length()) {
            parts.add(s.substring(start));
        }
        return parts.toArray(new String[0]);
    }

    private static String removeWhitespace(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != ' ' && c != '\t' && c != '\r' && c != '\n') {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String base64Encode(byte[] data) {
        return Base64.getEncoder().encodeToString(data);
    }

}

