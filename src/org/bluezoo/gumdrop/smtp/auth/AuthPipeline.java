/*
 * AuthPipeline.java
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

import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.dns.client.DnsResolver;
import org.bluezoo.gumdrop.mime.MimeParseException;
import org.bluezoo.gumdrop.mime.rfc5322.EmailAddress;
import org.bluezoo.gumdrop.mime.rfc5322.MessageHandler;
import org.bluezoo.gumdrop.smtp.SmtpPipeline;

/**
 * Authentication pipeline for SPF, DKIM, DMARC, and optional ARC checks.
 * Integrates RFC 7208 (SPF), RFC 6376 (DKIM), RFC 7489 (DMARC), RFC 8617 (ARC).
 *
 * <p>AuthPipeline implements {@link SmtpPipeline} to integrate with
 * SMTPConnection. Configure it with callbacks for the checks you want,
 * then associate it with the connection.
 *
 * <p>The pipeline is purely event-driven:
 * <ul>
 *   <li>SPF check runs at MAIL FROM, delivers result via {@link SpfCallback}</li>
 *   <li>DKIM verification runs at end-of-data, delivers result via {@link DkimCallback}</li>
 *   <li>DMARC evaluation runs when DKIM completes (using accumulated SPF result
 *       and From domain), delivers result via {@link DmarcCallback}</li>
 * </ul>
 *
 * <p>Example usage:
 *
 * <pre><code>
 * AuthPipeline pipeline = new AuthPipeline.Builder(resolver, clientIP, heloHost)
 *     .onSPF((result, explanation) -&gt; {
 *         if (result == SpfResult.FAIL) {
 *             // Log or take action
 *         }
 *     })
 *     .onDMARC((result, policy, domain, verdict) -&gt; {
 *         if (verdict == AuthVerdict.REJECT) {
 *             // Reject message
 *         }
 *     })
 *     .build();
 * </code></pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see SmtpPipeline
 * @see DmarcValidator
 * @see DmarcMessageHandler
 * @see <a href="https://www.rfc-editor.org/rfc/rfc7208">RFC 7208 - SPF</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc6376">RFC 6376 - DKIM</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc7489">RFC 7489 - DMARC</a>
 */
public class AuthPipeline implements SmtpPipeline {

    private static final Logger LOGGER = Logger.getLogger(AuthPipeline.class.getName());

    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.smtp.auth.L10N");

    private final DnsResolver resolver;
    private final InetAddress clientIP;
    private final String heloHost;

    // User callbacks
    private final SpfCallback spfCallback;
    private final DkimCallback dkimCallback;
    private final DmarcCallback dmarcCallback;
    private final ArcCallback arcCallback;
    private final ArcDmarcPolicy arcDmarcPolicy;

    // User's message handler for teed content
    private final MessageHandler messageHandler;

    // Validators
    private final SpfValidator spfValidator;
    private final DkimValidator dkimValidator;
    private final ArcValidator arcValidator;

    // Per-message state
    private DkimMessageParser parser;
    private DmarcValidator dmarcValidator;

    /**
     * Creates a pipeline from the builder.
     */
    private AuthPipeline(Builder builder) {
        this.resolver = builder.resolver;
        this.clientIP = builder.clientIP;
        this.heloHost = builder.heloHost;

        this.spfCallback = builder.spfCallback;
        this.dkimCallback = builder.dkimCallback;
        this.dmarcCallback = builder.dmarcCallback;
        this.arcCallback = builder.arcCallback;
        this.arcDmarcPolicy = builder.arcDmarcPolicy;
        this.messageHandler = builder.messageHandler;

        // Create validators
        this.spfValidator = new SpfValidator(resolver);
        this.dkimValidator = new DkimValidator(resolver);
        if (builder.arcCallback != null || builder.arcDmarcPolicy != null) {
            this.arcValidator = new ArcValidator(resolver);
        } else {
            this.arcValidator = null;
        }
    }

    // -- SmtpPipeline implementation --

    @Override
    public void mailFrom(EmailAddress sender) {
        // Create DmarcValidator for this message (it aggregates SPF + DKIM results)
        dmarcValidator = new DmarcValidator(resolver, dmarcCallback);
        if (arcDmarcPolicy != null) {
            dmarcValidator.setArcDmarcPolicy(arcDmarcPolicy);
        }

        // Set SPF domain on DmarcValidator
        String spfDomain = (sender != null) ? sender.getDomain() : heloHost;
        dmarcValidator.setSpfDomain(spfDomain);

        // Create SPF callback that forwards to both user callback and DmarcValidator
        SpfCallback effectiveSpfCallback = new SpfCallback() {
            @Override
            public void spfResult(SpfResult result, String explanation) {
                // Forward to DmarcValidator for DMARC evaluation
                dmarcValidator.spfResult(result, explanation);
                // Forward to user callback if registered
                if (spfCallback != null) {
                    spfCallback.spfResult(result, explanation);
                }
            }
        };

        // Run SPF check
        spfValidator.check(sender, clientIP, heloHost, effectiveSpfCallback);

        // Create DKIM message parser
        parser = new DkimMessageParser();

        // Create DmarcMessageHandler that:
        // 1. Extracts From domain for DMARC
        // 2. Tees to user's MessageHandler
        DmarcMessageHandler.FromDomainCallback fromDomainCallback =
                new DmarcMessageHandler.FromDomainCallback() {
                    @Override
                    public void onFromDomain(String domain) {
                        dmarcValidator.setFromDomain(domain);
                    }
                };
        DmarcMessageHandler dmarcHandler = new DmarcMessageHandler(fromDomainCallback, messageHandler);
        parser.setMessageHandler(dmarcHandler);
        if (arcValidator != null) {
            arcValidator.resetForMessage();
            parser.setArcHeaderParser(arcValidator.getArcHeaderParser());
        }
    }

    @Override
    public void rcptTo(EmailAddress recipient) {
        // Authentication doesn't care about recipients
    }

    @Override
    public WritableByteChannel getMessageChannel() {
        if (parser == null) {
            return null;
        }
        return new ParserChannel(parser);
    }

    @Override
    public void endData() {
        if (parser == null) {
            return;
        }

        // Close parser
        try {
            parser.close();
        } catch (MimeParseException e) {
            LOGGER.log(Level.WARNING, L10N.getString("err.close_parser"), e);
        }

        final byte[] bodyHash = parser.getBodyHash();

        if (arcValidator != null) {
            arcValidator.setMessageParser(parser);
            if (bodyHash != null) {
                arcValidator.setBodyHash(bodyHash);
            }
            arcValidator.verify(new ArcCallback() {
                @Override
                public void arcResult(ArcValidationResult result) {
                    dmarcValidator.setArcValidationResult(result);
                    if (arcCallback != null) {
                        arcCallback.arcResult(result);
                    }
                    verifyDkim(bodyHash);
                }
            });
        } else {
            verifyDkim(bodyHash);
        }
    }

    private void verifyDkim(byte[] bodyHash) {
        DkimCallback effectiveDkimCallback = new DkimCallback() {
            @Override
            public void dkimResult(DkimResult result, String signingDomain,
                                   String selector) {
                if (dkimCallback != null) {
                    dkimCallback.dkimResult(result, signingDomain, selector);
                }
                dmarcValidator.dkimResult(result, signingDomain, selector);
            }
        };

        dkimValidator.setMessageParser(parser);
        if (bodyHash != null) {
            dkimValidator.setBodyHash(bodyHash);
        }
        dkimValidator.verify(effectiveDkimCallback);
    }

    @Override
    public void reset() {
        parser = null;
        dmarcValidator = null;
    }

    /**
     * WritableByteChannel that forwards bytes to the DkimMessageParser.
     */
    private static class ParserChannel implements WritableByteChannel {

        private final DkimMessageParser parser;
        private boolean open = true;

        ParserChannel(DkimMessageParser parser) {
            this.parser = parser;
        }

        @Override
        public int write(ByteBuffer src) throws IOException {
            if (!open) {
                throw new IOException("Channel is closed");
            }
            int count = src.remaining();
            if (count > 0) {
                try {
                    parser.receive(src);
                } catch (MimeParseException e) {
                    throw new IOException("Parse error", e);
                }
            }
            return count;
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() throws IOException {
            open = false;
        }
    }

    // -- Builder --

    /**
     * Builder for AuthPipeline.
     */
    public static class Builder {

        private final DnsResolver resolver;
        private final InetAddress clientIP;
        private final String heloHost;

        private SpfCallback spfCallback;
        private DkimCallback dkimCallback;
        private DmarcCallback dmarcCallback;
        private ArcCallback arcCallback;
        private ArcDmarcPolicy arcDmarcPolicy;
        private MessageHandler messageHandler;

        /**
         * Creates a new builder.
         *
         * @param resolver the DNS resolver to use for lookups
         * @param clientIP the IP address of the connecting client
         * @param heloHost the HELO/EHLO hostname from the client
         */
        public Builder(DnsResolver resolver, InetAddress clientIP, String heloHost) {
            if (resolver == null) {
                throw new NullPointerException(L10N.getString("err.null_resolver"));
            }
            this.resolver = resolver;
            this.clientIP = clientIP;
            this.heloHost = heloHost;
        }

        /**
         * Registers a callback for SPF results.
         *
         * <p>The callback will be invoked during MAIL FROM processing,
         * as soon as the SPF check completes.
         *
         * @param callback the SPF result callback
         * @return this builder
         */
        public Builder onSPF(SpfCallback callback) {
            this.spfCallback = callback;
            return this;
        }

        /**
         * Registers a callback for DKIM results.
         *
         * <p>The callback will be invoked at end-of-data, after the
         * DKIM signature has been verified.
         *
         * @param callback the DKIM result callback
         * @return this builder
         */
        public Builder onDKIM(DkimCallback callback) {
            this.dkimCallback = callback;
            return this;
        }

        /**
         * Registers a callback for DMARC results.
         *
         * <p>The callback will be invoked at end-of-data, after both
         * SPF and DKIM results are available.
         *
         * @param callback the DMARC result callback
         * @return this builder
         */
        public Builder onDMARC(DmarcCallback callback) {
            this.dmarcCallback = callback;
            return this;
        }

        /**
         * Registers a callback for ARC chain validation (RFC 8617).
         *
         * <p>Enables {@link ArcValidator} at end-of-data before DKIM verification.
         * The validation result is also passed to {@link DmarcValidator} when
         * {@link #arcDmarcPolicy} is set.
         *
         * @param callback the ARC result callback
         * @return this builder
         */
        public Builder onARC(ArcCallback callback) {
            this.arcCallback = callback;
            return this;
        }

        /**
         * Registers a policy for ARC-aware DMARC alignment.
         *
         * <p>When the ARC chain validates with {@link ArcCvResult#PASS}, the
         * policy may supply alternate SPF/DKIM inputs for
         * {@link DmarcValidator} via {@link ArcAuthSnapshot}. Enabling a policy
         * also activates {@link ArcValidator} even if {@link #onARC} is not used.
         *
         * @param policy the trust policy, or null for local SPF/DKIM only
         * @return this builder
         */
        public Builder arcDmarcPolicy(ArcDmarcPolicy policy) {
            this.arcDmarcPolicy = policy;
            return this;
        }

        /**
         * Registers a message handler to receive parsed message events.
         *
         * <p>The raw message bytes will be parsed and events forwarded
         * to this handler.
         *
         * @param handler the message handler to receive parsed events
         * @return this builder
         */
        public Builder messageHandler(MessageHandler handler) {
            this.messageHandler = handler;
            return this;
        }

        /**
         * Builds the AuthPipeline.
         *
         * @return the configured pipeline
         */
        public AuthPipeline build() {
            return new AuthPipeline(this);
        }

    }

}
