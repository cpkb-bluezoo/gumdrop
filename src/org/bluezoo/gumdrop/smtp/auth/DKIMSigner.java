/*
 * DKIMSigner.java
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

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * RFC 6376 §5 — DKIM message signer.
 *
 * <p>Generates a DKIM-Signature header for outbound messages. The signer
 * applies the specified canonicalization (simple or relaxed) to headers and
 * body, computes the body hash, signs the header hash with the private key,
 * and produces a complete DKIM-Signature header ready for prepending.
 *
 * <p>Supported algorithms:
 * <ul>
 *   <li>{@code rsa-sha256} — RSA with SHA-256 (RFC 6376 §3.3)</li>
 *   <li>{@code ed25519-sha256} — Ed25519 with SHA-256 (RFC 8463)</li>
 * </ul>
 *
 * <h4>Usage</h4>
 * <pre>{@code
 * DKIMSigner signer = new DKIMSigner(privateKey, "example.com", "sel1");
 * signer.setHeaderCanonicalization("relaxed");
 * signer.setBodyCanonicalization("relaxed");
 * signer.setSignedHeaders(Arrays.asList("from", "to", "subject", "date"));
 *
 * // Feed body lines (after headers, in wire format with CRLF)
 * signer.bodyLine(bodyData);
 * signer.endBody();
 *
 * // Feed headers (name + raw value with CRLF)
 * String dkimHeader = signer.sign(headerLines);
 * // Prepend dkimHeader to the message
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see DKIMValidator
 * @see <a href="https://www.rfc-editor.org/rfc/rfc6376#section-5">RFC 6376 §5</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc8463">RFC 8463 — Ed25519-SHA256</a>
 */
public class DKIMSigner {

    private static final String CRLF = "\r\n";

    private final PrivateKey privateKey;
    private final String domain;
    private final String selector;

    private String algorithm = "rsa-sha256";
    private String headerCanonicalization = "relaxed";
    private String bodyCanonicalization = "relaxed";
    private List<String> signedHeaders;
    private long signatureTimestamp = -1;
    private long signatureExpiration = -1;
    private String identity;

    private MessageDigest bodyDigest;
    private int trailingEmptyLines;
    private boolean bodyStarted;

    /**
     * RFC 6376 §5.1 — creates a new DKIM signer.
     *
     * @param privateKey the signing key (RSA or Ed25519)
     * @param domain the signing domain (d= tag)
     * @param selector the selector (s= tag)
     */
    public DKIMSigner(PrivateKey privateKey, String domain, String selector) {
        this.privateKey = privateKey;
        this.domain = domain;
        this.selector = selector;
        this.signedHeaders = new ArrayList<>();
    }

    /** Sets the signing algorithm: "rsa-sha256" (default) or "ed25519-sha256". */
    public void setAlgorithm(String algorithm) {
        this.algorithm = algorithm;
    }

    /** Sets header canonicalization: "simple" or "relaxed" (default). */
    public void setHeaderCanonicalization(String canon) {
        this.headerCanonicalization = canon;
    }

    /** Sets body canonicalization: "simple" or "relaxed" (default). */
    public void setBodyCanonicalization(String canon) {
        this.bodyCanonicalization = canon;
    }

    /** RFC 6376 §5.4 — sets the list of header names to sign (lowercase). */
    public void setSignedHeaders(List<String> headers) {
        this.signedHeaders = headers;
    }

    /** RFC 6376 §3.5 — sets the signature timestamp (t= tag, Unix seconds). */
    public void setTimestamp(long timestamp) {
        this.signatureTimestamp = timestamp;
    }

    /** RFC 6376 §3.5 — sets the signature expiration (x= tag, Unix seconds). */
    public void setExpiration(long expiration) {
        this.signatureExpiration = expiration;
    }

    /** RFC 6376 §3.5 — sets the agent/user identity (i= tag). */
    public void setIdentity(String identity) {
        this.identity = identity;
    }

    /**
     * RFC 6376 §3.7 — feeds a body line for hash computation.
     * Call this for each line of the message body (in wire format with CRLF).
     *
     * @param line the body line bytes
     * @param offset start offset
     * @param length number of bytes
     */
    public void bodyLine(byte[] line, int offset, int length) {
        if (bodyDigest == null) {
            initBodyDigest();
        }
        boolean relaxed = "relaxed".equals(bodyCanonicalization);
        if (relaxed) {
            bodyLineRelaxed(line, offset, length);
        } else {
            bodyLineSimple(line, offset, length);
        }
    }

    /**
     * RFC 6376 §3.4.4 — simple body canonicalization.
     * Empty trailing lines are deferred and only hashed if a non-empty line follows.
     */
    private void bodyLineSimple(byte[] line, int offset, int length) {
        boolean isEmpty = (length == 2 && line[offset] == '\r' && line[offset + 1] == '\n')
                       || (length == 1 && line[offset] == '\n');
        if (isEmpty) {
            trailingEmptyLines++;
        } else {
            flushTrailingEmpty();
            bodyDigest.update(line, offset, length);
            bodyStarted = true;
        }
    }

    /**
     * RFC 6376 §3.4.4 — relaxed body canonicalization.
     * Compresses WSP to single SP, strips trailing WSP per line.
     */
    private void bodyLineRelaxed(byte[] line, int offset, int length) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        boolean prevWSP = false;
        for (int i = offset; i < offset + length; i++) {
            byte b = line[i];
            if (b == ' ' || b == '\t') {
                prevWSP = true;
            } else if (b == '\r' || b == '\n') {
                // ignore CR/LF in this pass; we add CRLF at end
            } else {
                if (prevWSP) {
                    out.write(' ');
                    prevWSP = false;
                }
                out.write(b);
            }
        }
        byte[] canonical = out.toByteArray();
        if (canonical.length == 0) {
            trailingEmptyLines++;
        } else {
            flushTrailingEmpty();
            bodyDigest.update(canonical);
            bodyDigest.update(CRLF.getBytes(StandardCharsets.US_ASCII));
            bodyStarted = true;
        }
    }

    private void flushTrailingEmpty() {
        byte[] empty = CRLF.getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < trailingEmptyLines; i++) {
            bodyDigest.update(empty);
        }
        trailingEmptyLines = 0;
    }

    /** Signals end of body. If no body was seen, hash a single CRLF (simple) or empty (relaxed). */
    public void endBody() {
        if (bodyDigest == null) {
            initBodyDigest();
        }
        if (!bodyStarted && !"relaxed".equals(bodyCanonicalization)) {
            bodyDigest.update(CRLF.getBytes(StandardCharsets.US_ASCII));
        }
    }

    /**
     * RFC 6376 §5.4–5.6 — signs the message headers and returns a complete
     * DKIM-Signature header (including the "DKIM-Signature: " prefix and
     * trailing CRLF).
     *
     * @param rawHeaders list of raw header lines (name: value CRLF), in order
     * @return the complete DKIM-Signature header
     * @throws Exception if signing fails
     */
    public String sign(List<String> rawHeaders) throws Exception {
        if (bodyDigest == null) {
            initBodyDigest();
        }
        byte[] bodyHashBytes = bodyDigest.digest();
        String bodyHashB64 = Base64.getEncoder().encodeToString(bodyHashBytes);

        // RFC 6376 §5.4 — build the DKIM-Signature header with empty b=
        String dkimHeaderValue = buildSignatureHeaderValue(bodyHashB64, "");

        // RFC 6376 §5.5 — compute the header hash
        boolean relaxed = "relaxed".equals(headerCanonicalization);
        StringBuilder dataToSign = new StringBuilder();
        for (String headerName : signedHeaders) {
            String headerLine = findHeader(rawHeaders, headerName);
            if (headerLine != null) {
                dataToSign.append(canonicalizeHeader(headerLine, relaxed));
            }
        }
        // Append the DKIM-Signature header (without trailing CRLF)
        String dkimFull = "dkim-signature:" + dkimHeaderValue;
        String dkimCanon = canonicalizeHeader(dkimFull, relaxed);
        if (dkimCanon.endsWith(CRLF)) {
            dkimCanon = dkimCanon.substring(0, dkimCanon.length() - 2);
        }
        dataToSign.append(dkimCanon);

        // RFC 6376 §5.6 — compute the signature
        byte[] sigBytes = computeSignature(dataToSign.toString().getBytes(StandardCharsets.UTF_8));
        String sigB64 = Base64.getEncoder().encodeToString(sigBytes);

        String finalValue = buildSignatureHeaderValue(bodyHashB64, sigB64);
        return "DKIM-Signature: " + finalValue + CRLF;
    }

    /** RFC 6376 §3.5 — builds the tag=value string for the DKIM-Signature header. */
    private String buildSignatureHeaderValue(String bodyHash, String signatureValue) {
        StringBuilder sb = new StringBuilder();
        sb.append("v=1; a=").append(algorithm);
        sb.append("; c=").append(headerCanonicalization).append("/").append(bodyCanonicalization);
        sb.append("; d=").append(domain);
        sb.append("; s=").append(selector);
        if (signatureTimestamp >= 0) {
            sb.append("; t=").append(signatureTimestamp);
        }
        if (signatureExpiration >= 0) {
            sb.append("; x=").append(signatureExpiration);
        }
        if (identity != null) {
            sb.append("; i=").append(identity);
        }
        sb.append("; h=");
        for (int i = 0; i < signedHeaders.size(); i++) {
            if (i > 0) {
                sb.append(":");
            }
            sb.append(signedHeaders.get(i));
        }
        sb.append("; bh=").append(bodyHash);
        sb.append("; b=").append(signatureValue);
        return sb.toString();
    }

    /** RFC 6376 §3.4.2 — canonicalize a header line. */
    private String canonicalizeHeader(String header, boolean relaxed) {
        if (!relaxed) {
            return header.endsWith(CRLF) ? header : header + CRLF;
        }
        int colonPos = header.indexOf(':');
        if (colonPos <= 0) {
            return header.endsWith(CRLF) ? header : header + CRLF;
        }
        String name = header.substring(0, colonPos).toLowerCase().trim();
        String value = header.substring(colonPos + 1);
        String canon = unfoldAndCompress(value);
        return name + ":" + canon + CRLF;
    }

    /** Finds the first matching header by name (case-insensitive). */
    private String findHeader(List<String> rawHeaders, String name) {
        String lowerName = name.toLowerCase();
        for (int i = rawHeaders.size() - 1; i >= 0; i--) {
            String line = rawHeaders.get(i);
            int colon = line.indexOf(':');
            if (colon > 0) {
                String hName = line.substring(0, colon).trim().toLowerCase();
                if (hName.equals(lowerName)) {
                    return line;
                }
            }
        }
        return null;
    }

    /**
     * RFC 6376 §5.6 / RFC 8463 — computes the cryptographic signature.
     * Supports RSA-SHA256 and Ed25519-SHA256.
     */
    private byte[] computeSignature(byte[] data) throws Exception {
        String sigAlgorithm;
        if (algorithm.startsWith("ed25519")) {
            sigAlgorithm = "Ed25519";
        } else {
            sigAlgorithm = "SHA256withRSA";
        }
        Signature sig = Signature.getInstance(sigAlgorithm);
        sig.initSign(privateKey);
        sig.update(data);
        return sig.sign();
    }

    private void initBodyDigest() {
        try {
            bodyDigest = MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
        trailingEmptyLines = 0;
        bodyStarted = false;
    }

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
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) == ' ') {
            sb.setLength(sb.length() - 1);
        }
        return sb.toString();
    }

}
