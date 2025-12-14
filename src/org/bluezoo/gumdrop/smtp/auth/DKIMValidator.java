/*
 * DKIMValidator.java
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
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.dns.DNSMessage;
import org.bluezoo.gumdrop.dns.DNSQueryCallback;
import org.bluezoo.gumdrop.dns.DNSResolver;
import org.bluezoo.gumdrop.dns.DNSResourceRecord;
import org.bluezoo.gumdrop.dns.DNSType;

/**
 * DKIM (DomainKeys Identified Mail) validator as defined in RFC 6376.
 *
 * <p>DKIM validates that a message was signed by the claimed domain and
 * that the message content has not been modified. This implementation
 * is fully asynchronous, using callbacks for DNS lookups.
 *
 * <p>The validator uses a {@link DKIMMessageParser} to capture raw header
 * bytes for proper DKIM canonicalization. The body hash is computed
 * separately from the raw message bytes.
 *
 * <p>Example usage:
 * <pre><code>
 * DNSResolver resolver = new DNSResolver();
 * resolver.useSystemResolvers();
 * resolver.open();
 *
 * // Parse message with DKIMMessageParser
 * DKIMMessageParser parser = new DKIMMessageParser();
 * parser.receive(messageData);
 * parser.close();
 *
 * DKIMValidator dkim = new DKIMValidator(resolver);
 * dkim.setMessageParser(parser);
 * dkim.setBodyHash(computedBodyHash);
 *
 * // Verify
 * dkim.verify(new DKIMCallback() {
 *     &#64;Override
 *     public void dkimResult(DKIMResult result, String domain, String selector) {
 *         if (result == DKIMResult.PASS) {
 *             // Signature verified
 *         }
 *     }
 * });
 * </code></pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see DKIMMessageParser
 * @see <a href="https://www.rfc-editor.org/rfc/rfc6376">RFC 6376 - DKIM</a>
 */
public class DKIMValidator {

    private static final Logger LOGGER = Logger.getLogger(DKIMValidator.class.getName());
    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.smtp.auth.L10N");

    private final DNSResolver resolver;

    private DKIMMessageParser messageParser;
    private DKIMSignature signature;
    private byte[] bodyHash;

    /**
     * Creates a new DKIM validator using the specified DNS resolver.
     *
     * @param resolver the DNS resolver to use for public key lookups
     */
    public DKIMValidator(DNSResolver resolver) {
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
    public void setMessageParser(DKIMMessageParser parser) {
        this.messageParser = parser;
        // Get the signature from the parser
        if (this.signature == null) {
            this.signature = parser.getDKIMSignature();
        }
    }

    /**
     * Sets the computed body hash.
     * This should be computed using the algorithm from the DKIM signature
     * (SHA-256 for rsa-sha256) after canonicalizing the body.
     *
     * @param hash the computed body hash
     */
    public void setBodyHash(byte[] hash) {
        this.bodyHash = hash;
    }

    /**
     * Returns the parsed DKIM signature, or null if none found.
     *
     * @return the DKIM signature
     */
    public DKIMSignature getSignature() {
        if (signature == null && messageParser != null) {
            signature = messageParser.getDKIMSignature();
        }
        return signature;
    }

    /**
     * Verifies the DKIM signature asynchronously.
     *
     * @param callback the callback to receive the result
     */
    public void verify(final DKIMCallback callback) {
        // Get signature from parser if not already set
        if (signature == null && messageParser != null) {
            signature = messageParser.getDKIMSignature();
        }

        if (signature == null) {
            callback.dkimResult(DKIMResult.NONE, null, null);
            return;
        }

        if (messageParser == null) {
            // No raw header bytes available
            callback.dkimResult(DKIMResult.PERMERROR, signature.getDomain(),
                    signature.getSelector());
            return;
        }

        // Check if signature has expired
        long now = System.currentTimeMillis() / 1000;
        if (signature.getExpiration() > 0 && now > signature.getExpiration()) {
            callback.dkimResult(DKIMResult.FAIL, signature.getDomain(),
                    signature.getSelector());
            return;
        }

        // Verify body hash first
        if (bodyHash != null) {
            String expectedHash = signature.getBodyHash();
            String actualHash = base64Encode(bodyHash);
            if (!expectedHash.equals(actualHash)) {
                callback.dkimResult(DKIMResult.FAIL, signature.getDomain(),
                        signature.getSelector());
                return;
            }
        }

        // Look up public key
        String queryName = signature.getKeyQueryName();
        resolver.queryTXT(queryName, new DNSQueryCallback() {
            @Override
            public void onResponse(DNSMessage response) {
                handleKeyResponse(response, callback);
            }

            @Override
            public void onError(String error) {
                callback.dkimResult(DKIMResult.TEMPERROR, signature.getDomain(),
                        signature.getSelector());
            }
        });
    }

    /**
     * Handles the DNS response for the public key lookup.
     */
    private void handleKeyResponse(DNSMessage response, DKIMCallback callback) {
        // Check for errors
        int rcode = response.getRcode();
        if (rcode == DNSMessage.RCODE_NXDOMAIN) {
            callback.dkimResult(DKIMResult.PERMERROR, signature.getDomain(),
                    signature.getSelector());
            return;
        }

        if (rcode != DNSMessage.RCODE_NOERROR) {
            callback.dkimResult(DKIMResult.TEMPERROR, signature.getDomain(),
                    signature.getSelector());
            return;
        }

        // Find the public key record
        String keyRecord = null;
        List<DNSResourceRecord> answers = response.getAnswers();
        for (int i = 0; i < answers.size(); i++) {
            DNSResourceRecord rr = answers.get(i);
            if (rr.getType() == DNSType.TXT) {
                String txt = rr.getText();
                if (txt != null && txt.contains("p=")) {
                    keyRecord = txt;
                    break;
                }
            }
        }

        if (keyRecord == null) {
            callback.dkimResult(DKIMResult.PERMERROR, signature.getDomain(),
                    signature.getSelector());
            return;
        }

        // Parse the key record
        PublicKey publicKey = parsePublicKey(keyRecord);
        if (publicKey == null) {
            callback.dkimResult(DKIMResult.PERMERROR, signature.getDomain(),
                    signature.getSelector());
            return;
        }

        // Verify the signature
        try {
            boolean verified = verifySignature(publicKey);
            DKIMResult result = verified ? DKIMResult.PASS : DKIMResult.FAIL;
            callback.dkimResult(result, signature.getDomain(), signature.getSelector());
        } catch (Exception e) {
            LOGGER.log(Level.FINE, L10N.getString("debug.dkim_verify_error"), e);
            callback.dkimResult(DKIMResult.PERMERROR, signature.getDomain(),
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
            X509EncodedKeySpec keySpec = new X509EncodedKeySpec(keyBytes);

            String algorithm;
            if ("ed25519".equals(keyType)) {
                algorithm = "Ed25519";
            } else {
                algorithm = "RSA";
            }

            KeyFactory keyFactory = KeyFactory.getInstance(algorithm);
            return keyFactory.generatePublic(keySpec);
        } catch (Exception e) {
            LOGGER.log(Level.FINE, L10N.getString("debug.dkim_parse_key_error"), e);
            return null;
        }
    }

    /**
     * Verifies the signature using the public key.
     */
    private boolean verifySignature(PublicKey publicKey) throws Exception {
        // Build the header hash
        String headerData = buildHeaderHash();

        // Determine signature algorithm
        String alg = signature.getAlgorithm();
        String sigAlgorithm;
        if (alg.startsWith("rsa-sha256")) {
            sigAlgorithm = "SHA256withRSA";
        } else if (alg.startsWith("rsa-sha1")) {
            sigAlgorithm = "SHA1withRSA";
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
     *
     * <p>This uses the raw header bytes captured by {@link DKIMMessageParser}
     * and applies the appropriate canonicalization (simple or relaxed).
     */
    private String buildHeaderHash() {
        StringBuilder sb = new StringBuilder();
        String headerCanon = signature.getHeaderCanonicalization();
        boolean relaxed = "relaxed".equals(headerCanon);

        // Add signed headers in the order specified
        List<String> signedHeaders = signature.getSignedHeaders();
        Map<String, Integer> usedCount = new HashMap<String, Integer>();

        for (int i = 0; i < signedHeaders.size(); i++) {
            String headerName = signedHeaders.get(i);
            List<DKIMMessageParser.RawHeader> rawHeaders = 
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

            DKIMMessageParser.RawHeader rawHeader = rawHeaders.get(idx);
            String line = canonicalizeRawHeader(rawHeader, relaxed);
            sb.append(line);
        }

        // Add the DKIM-Signature header (without the b= value)
        String dkimHeader = canonicalizeDKIMHeader(relaxed);
        sb.append(dkimHeader);

        return sb.toString();
    }

    /**
     * Canonicalizes a raw header for signature verification.
     *
     * @param rawHeader the raw header with captured bytes
     * @param relaxed true for relaxed canonicalization, false for simple
     * @return the canonicalized header string
     */
    private String canonicalizeRawHeader(DKIMMessageParser.RawHeader rawHeader, boolean relaxed) {
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
        // Get the raw DKIM-Signature header
        DKIMMessageParser.RawHeader rawHeader = messageParser.getRawHeader("dkim-signature");
        if (rawHeader == null) {
            return "";
        }

        String header;
        if (relaxed) {
            header = rawHeader.asStringUnfolded();
        } else {
            header = rawHeader.asString();
        }

        // Remove the b= value (but keep the tag)
        int bPos = header.indexOf("b=");
        if (bPos < 0) {
            // No b= found, return as-is but without trailing CRLF for final header
            return stripTrailingCRLF(relaxed ? relaxedCanonicalizeHeader(header) : header);
        }

        // Find the end of the b= value (next semicolon or end of header)
        int endPos = bPos + 2;
        while (endPos < header.length()) {
            char c = header.charAt(endPos);
            if (c == ';') {
                break;
            }
            // Skip to end of line ignoring whitespace in the value
            endPos++;
        }

        String beforeB = header.substring(0, bPos + 2);
        String afterB = (endPos < header.length()) ? header.substring(endPos) : "";

        String modified = beforeB + afterB;

        if (relaxed) {
            // Apply relaxed canonicalization, then remove trailing CRLF
            return stripTrailingCRLF(relaxedCanonicalizeHeader(modified));
        } else {
            // Simple: remove trailing CRLF for final header
            return stripTrailingCRLF(modified);
        }
    }

    /**
     * Removes trailing CRLF or LF from a string.
     */
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

