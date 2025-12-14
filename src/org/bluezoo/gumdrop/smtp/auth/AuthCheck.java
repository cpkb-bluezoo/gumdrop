/*
 * AuthCheck.java
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

import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.security.NoSuchAlgorithmException;
import java.text.MessageFormat;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.mime.ContentDisposition;
import org.bluezoo.gumdrop.mime.ContentID;
import org.bluezoo.gumdrop.mime.ContentType;
import org.bluezoo.gumdrop.mime.MIMELocator;
import org.bluezoo.gumdrop.mime.MIMEParseException;
import org.bluezoo.gumdrop.mime.MIMEParser;
import org.bluezoo.gumdrop.mime.MIMEVersion;
import org.bluezoo.gumdrop.mime.rfc5322.EmailAddress;
import org.bluezoo.gumdrop.mime.rfc5322.MessageHandler;
import org.bluezoo.gumdrop.mime.rfc5322.MessageParser;
import org.bluezoo.gumdrop.mime.rfc5322.ObsoleteStructureType;

/**
 * Per-message authentication check instance.
 *
 * <p>AuthCheck is created by the SMTP connection when an AuthPipeline is
 * associated. It receives raw message bytes and handles all authentication
 * internally, invoking the registered callbacks at appropriate times.
 *
 * <p>The lifecycle is:
 * <ol>
 *   <li>{@link #checkSender(EmailAddress)} - Called on MAIL FROM, triggers SPF</li>
 *   <li>{@link #receive(ByteBuffer)} - Called with raw message bytes as they stream</li>
 *   <li>{@link #close()} - Called at end-of-data, triggers DKIM and DMARC</li>
 * </ol>
 *
 * <p>This class is not thread-safe; all methods must be called from the
 * same thread (typically the SelectorLoop thread).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see AuthPipeline
 */
public class AuthCheck {

    private static final Logger LOGGER = Logger.getLogger(AuthCheck.class.getName());
    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.smtp.auth.L10N");

    private final AuthPipeline pipeline;
    private final InetAddress clientIP;
    private final String heloHost;

    // SPF state
    private EmailAddress sender;
    private SPFResult spfResult;
    private String spfDomain;
    private String spfExplanation;

    // Body hash initialization state
    private boolean bodyHashInitialized;

    // DKIM result state
    private DKIMResult dkimResult;
    private String dkimDomain;
    private String dkimSelector;

    // DMARC state
    private String fromDomain;
    private DMARCResult dmarcResult;
    private DMARCPolicy dmarcPolicy;
    private String dmarcDomain;

    // Message parser for header parsing (DKIM-Signature, From, etc.)
    // Use DKIMMessageParser when DKIM is enabled to capture raw header bytes
    private final MIMEParser parser;
    private final DKIMMessageParser dkimParser;

    /**
     * Creates a new authentication check.
     * This constructor is package-private; use AuthPipeline.createCheck().
     */
    AuthCheck(AuthPipeline pipeline, InetAddress clientIP, String heloHost) {
        this.pipeline = pipeline;
        this.clientIP = clientIP;
        this.heloHost = heloHost;
        this.bodyHashInitialized = false;

        // Create parser with composite handler for header parsing
        // Use DKIMMessageParser when DKIM verification is enabled
        MessageHandler userHandler = pipeline.getMessageHandler();
        if (pipeline.isDKIMEnabled()) {
            this.dkimParser = new DKIMMessageParser();
            this.parser = dkimParser;
            if (userHandler != null) {
                this.dkimParser.setMessageHandler(new CompositeMessageHandler(userHandler));
            } else {
                this.dkimParser.setMessageHandler(new AuthMessageHandler());
            }
        } else {
            this.dkimParser = null;
            MessageParser mp = new MessageParser();
            this.parser = mp;
            if (userHandler != null) {
                mp.setMessageHandler(new CompositeMessageHandler(userHandler));
            } else {
                mp.setMessageHandler(new AuthMessageHandler());
            }
        }
    }

    /**
     * Checks SPF for the envelope sender.
     *
     * <p>Called by SMTPConnection when MAIL FROM is processed. The SPF check
     * runs asynchronously; when complete, the registered SPFCallback (if any)
     * is invoked.
     *
     * @param sender the envelope sender address (MAIL FROM), or null for bounce
     */
    public void checkSender(EmailAddress sender) {
        this.sender = sender;
        this.spfDomain = (sender != null) ? sender.getDomain() : heloHost;

        if (!pipeline.isSPFEnabled()) {
            spfResult = SPFResult.NONE;
            SPFCallback callback = pipeline.getSPFCallback();
            if (callback != null) {
                callback.spfResult(SPFResult.NONE, null);
            }
            return;
        }

        final SPFCallback userCallback = pipeline.getSPFCallback();
        pipeline.getSPFValidator().check(sender, clientIP, heloHost, new SPFCallback() {
            public void spfResult(SPFResult result, String explanation) {
                AuthCheck.this.spfResult = result;
                spfExplanation = explanation;
                if (userCallback != null) {
                    userCallback.spfResult(result, explanation);
                }
            }
        });
    }

    /**
     * Receives raw message bytes.
     *
     * <p>Called by SMTPConnection as message data streams in. The bytes are
     * passed to the parser which handles both header and body processing.
     * Body hash computation is done in DKIMMessageParser.bodyLine().
     *
     * @param data the raw message bytes
     */
    public void receive(ByteBuffer data) {
        // Initialize body hash once headers are complete
        if (!bodyHashInitialized && dkimParser != null && dkimParser.isHeadersComplete()) {
            initBodyHash();
            bodyHashInitialized = true;
        }

        // Pass to parser - it handles header capture and body hashing
        try {
            parser.receive(data);
        } catch (MIMEParseException e) {
            LOGGER.log(Level.WARNING, L10N.getString("err.parse_message"), e);
        }

        // Check again in case headers completed during this receive
        if (!bodyHashInitialized && dkimParser != null && dkimParser.isHeadersComplete()) {
            initBodyHash();
            bodyHashInitialized = true;
        }
    }

    /**
     * Completes authentication checks.
     *
     * <p>Called by SMTPConnection at end-of-data. This:
     * <ul>
     *   <li>Closes the MessageParser</li>
     *   <li>Verifies DKIM signature</li>
     *   <li>Evaluates DMARC policy</li>
     *   <li>Invokes registered callbacks</li>
     * </ul>
     */
    public void close() {
        // Close parser
        try {
            parser.close();
        } catch (MIMEParseException e) {
            LOGGER.log(Level.WARNING, L10N.getString("err.close_parser"), e);
        }

        // Run DKIM then DMARC
        verifyDKIM();
    }

    /**
     * Verifies DKIM signature.
     */
    private void verifyDKIM() {
        DKIMValidator dkimValidator = pipeline.getDKIMValidator();
        if (dkimValidator == null || dkimParser == null) {
            dkimResult = DKIMResult.NONE;
            evaluateDMARC();
            return;
        }

        // Set the DKIMMessageParser which contains raw header bytes and body hash
        dkimValidator.setMessageParser(dkimParser);

        // Get computed body hash from the parser
        byte[] bodyHash = dkimParser.getBodyHash();
        if (bodyHash != null) {
            dkimValidator.setBodyHash(bodyHash);
        }

        final DKIMCallback userCallback = pipeline.getDKIMCallback();
        dkimValidator.verify(new DKIMCallback() {
            public void dkimResult(DKIMResult result, String domain, String selector) {
                AuthCheck.this.dkimResult = result;
                dkimDomain = domain;
                dkimSelector = selector;
                if (userCallback != null) {
                    userCallback.dkimResult(result, domain, selector);
                }
                evaluateDMARC();
            }
        });
    }

    /**
     * Evaluates DMARC policy.
     */
    private void evaluateDMARC() {
        DMARCValidator dmarcValidator = pipeline.getDMARCValidator();
        if (dmarcValidator == null || fromDomain == null) {
            dmarcResult = DMARCResult.NONE;
            dmarcPolicy = DMARCPolicy.NONE;
            // Notify user callback even when no DMARC validation
            DMARCCallback userCallback = pipeline.getDMARCCallback();
            if (userCallback != null) {
                userCallback.dmarcResult(DMARCResult.NONE, DMARCPolicy.NONE, 
                                         fromDomain, AuthVerdict.NONE);
            }
            return;
        }

        dmarcDomain = fromDomain;
        final DMARCCallback userCallback = pipeline.getDMARCCallback();

        dmarcValidator.evaluate(
                fromDomain,
                spfResult, spfDomain,
                dkimResult, dkimDomain,
                new DMARCCallback() {
                    public void dmarcResult(DMARCResult result, DMARCPolicy policy,
                                            String domain, AuthVerdict verdict) {
                        dmarcResult = result;
                        dmarcPolicy = policy;
                        dmarcDomain = domain;
                        if (userCallback != null) {
                            userCallback.dmarcResult(result, policy, domain, verdict);
                        }
                    }
                });
    }

    /**
     * Initializes body hash computation on the DKIMMessageParser.
     * Called once headers are complete so we know the DKIM signature parameters.
     */
    private void initBodyHash() {
        if (dkimParser == null) {
            return;
        }

        DKIMSignature sig = dkimParser.getDKIMSignature();
        if (sig == null) {
            return;
        }

        // Algorithm is like "rsa-sha256" - extract hash part
        String algorithm = sig.getAlgorithm();
        String digestName;
        if (algorithm != null && algorithm.contains("sha256")) {
            digestName = "SHA-256";
        } else if (algorithm != null && algorithm.contains("sha1")) {
            digestName = "SHA-1";
        } else {
            return;
        }

        // Set up body canonicalization
        String bodyCanon = sig.getBodyCanonicalization();
        boolean relaxed = "relaxed".equals(bodyCanon);

        try {
            dkimParser.initBodyHash(digestName, relaxed, sig.getBodyLength());
        } catch (NoSuchAlgorithmException e) {
            LOGGER.log(Level.WARNING,
                    MessageFormat.format(L10N.getString("err.hash_algorithm"), digestName), e);
        }
    }

    /**
     * Extracts the domain from a From header address.
     */
    private void extractFromDomain(List<EmailAddress> addresses) {
        if (addresses != null && !addresses.isEmpty()) {
            EmailAddress from = addresses.get(0);
            fromDomain = from.getDomain();
        }
    }

    /**
     * Returns the SPF result, or null if not yet checked.
     */
    public SPFResult getSPFResult() {
        return spfResult;
    }

    /**
     * Returns the DKIM result, or null if not yet verified.
     */
    public DKIMResult getDKIMResult() {
        return dkimResult;
    }

    /**
     * Returns the DMARC result, or null if not yet evaluated.
     */
    public DMARCResult getDMARCResult() {
        return dmarcResult;
    }

    // -- Internal MessageHandler for authentication --

    /**
     * MessageHandler that collects headers for DKIM and From for DMARC.
     * Body content from this handler is ignored - we compute body hash
     * from raw bytes instead.
     */
    private class AuthMessageHandler implements MessageHandler {

        @Override
        public void setLocator(MIMELocator locator) {
        }

        @Override
        public void startEntity(String boundary) throws MIMEParseException {
        }

        @Override
        public void contentType(ContentType contentType) throws MIMEParseException {
        }

        @Override
        public void contentDisposition(ContentDisposition contentDisposition) throws MIMEParseException {
        }

        @Override
        public void contentTransferEncoding(String encoding) throws MIMEParseException {
        }

        @Override
        public void contentID(ContentID contentID) throws MIMEParseException {
        }

        @Override
        public void contentDescription(String description) throws MIMEParseException {
        }

        @Override
        public void mimeVersion(MIMEVersion version) throws MIMEParseException {
        }

        @Override
        public void endHeaders() throws MIMEParseException {
        }

        @Override
        public void bodyContent(ByteBuffer data) throws MIMEParseException {
            // Ignored - we compute body hash from raw bytes
        }

        @Override
        public void unexpectedContent(ByteBuffer data) throws MIMEParseException {
        }

        @Override
        public void endEntity(String boundary) throws MIMEParseException {
        }

        @Override
        public void header(String name, String value) throws MIMEParseException {
            // Headers are captured by DKIMMessageParser for DKIM validation
            // No action needed here
        }

        @Override
        public void unexpectedHeader(String name, String value) throws MIMEParseException {
        }

        @Override
        public void dateHeader(String name, OffsetDateTime date) throws MIMEParseException {
        }

        @Override
        public void addressHeader(String name, List<EmailAddress> addresses) throws MIMEParseException {
            // Track From header for DMARC
            if ("from".equalsIgnoreCase(name)) {
                extractFromDomain(addresses);
            }
        }

        @Override
        public void messageIDHeader(String name, List<ContentID> contentIDs) throws MIMEParseException {
        }

        @Override
        public void obsoleteStructure(ObsoleteStructureType type) throws MIMEParseException {
        }

    }

    // -- Composite MessageHandler that tees to both auth and user handler --

    /**
     * Composite handler that forwards to both auth handler and user's handler.
     */
    private class CompositeMessageHandler implements MessageHandler {

        private final AuthMessageHandler authHandler = new AuthMessageHandler();
        private final MessageHandler userHandler;

        CompositeMessageHandler(MessageHandler userHandler) {
            this.userHandler = userHandler;
        }

        @Override
        public void setLocator(MIMELocator locator) {
            authHandler.setLocator(locator);
            userHandler.setLocator(locator);
        }

        @Override
        public void startEntity(String boundary) throws MIMEParseException {
            authHandler.startEntity(boundary);
            userHandler.startEntity(boundary);
        }

        @Override
        public void contentType(ContentType contentType) throws MIMEParseException {
            authHandler.contentType(contentType);
            userHandler.contentType(contentType);
        }

        @Override
        public void contentDisposition(ContentDisposition contentDisposition) throws MIMEParseException {
            authHandler.contentDisposition(contentDisposition);
            userHandler.contentDisposition(contentDisposition);
        }

        @Override
        public void contentTransferEncoding(String encoding) throws MIMEParseException {
            authHandler.contentTransferEncoding(encoding);
            userHandler.contentTransferEncoding(encoding);
        }

        @Override
        public void contentID(ContentID contentID) throws MIMEParseException {
            authHandler.contentID(contentID);
            userHandler.contentID(contentID);
        }

        @Override
        public void contentDescription(String description) throws MIMEParseException {
            authHandler.contentDescription(description);
            userHandler.contentDescription(description);
        }

        @Override
        public void mimeVersion(MIMEVersion version) throws MIMEParseException {
            authHandler.mimeVersion(version);
            userHandler.mimeVersion(version);
        }

        @Override
        public void endHeaders() throws MIMEParseException {
            authHandler.endHeaders();
            userHandler.endHeaders();
        }

        @Override
        public void bodyContent(ByteBuffer data) throws MIMEParseException {
            // Auth handler ignores body content (uses raw bytes)
            // Just pass to user handler
            userHandler.bodyContent(data);
        }

        @Override
        public void unexpectedContent(ByteBuffer data) throws MIMEParseException {
            userHandler.unexpectedContent(data);
        }

        @Override
        public void endEntity(String boundary) throws MIMEParseException {
            authHandler.endEntity(boundary);
            userHandler.endEntity(boundary);
        }

        @Override
        public void header(String name, String value) throws MIMEParseException {
            authHandler.header(name, value);
            userHandler.header(name, value);
        }

        @Override
        public void unexpectedHeader(String name, String value) throws MIMEParseException {
            authHandler.unexpectedHeader(name, value);
            userHandler.unexpectedHeader(name, value);
        }

        @Override
        public void dateHeader(String name, OffsetDateTime date) throws MIMEParseException {
            authHandler.dateHeader(name, date);
            userHandler.dateHeader(name, date);
        }

        @Override
        public void addressHeader(String name, List<EmailAddress> addresses) throws MIMEParseException {
            authHandler.addressHeader(name, addresses);
            userHandler.addressHeader(name, addresses);
        }

        @Override
        public void messageIDHeader(String name, List<ContentID> contentIDs) throws MIMEParseException {
            authHandler.messageIDHeader(name, contentIDs);
            userHandler.messageIDHeader(name, contentIDs);
        }

        @Override
        public void obsoleteStructure(ObsoleteStructureType type) throws MIMEParseException {
            authHandler.obsoleteStructure(type);
            userHandler.obsoleteStructure(type);
        }

    }

}
