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

import org.bluezoo.gumdrop.dns.client.DNSResolver;
import org.bluezoo.gumdrop.mime.MIMEParseException;
import org.bluezoo.gumdrop.mime.rfc5322.EmailAddress;
import org.bluezoo.gumdrop.mime.rfc5322.MessageHandler;
import org.bluezoo.gumdrop.smtp.SMTPPipeline;

/**
 * Authentication pipeline for SPF, DKIM, and DMARC checks.
 *
 * <p>AuthPipeline implements {@link SMTPPipeline} to integrate with
 * SMTPConnection. Configure it with callbacks for the checks you want,
 * then associate it with the connection.
 *
 * <p>The pipeline is purely event-driven:
 * <ul>
 *   <li>SPF check runs at MAIL FROM, delivers result via {@link SPFCallback}</li>
 *   <li>DKIM verification runs at end-of-data, delivers result via {@link DKIMCallback}</li>
 *   <li>DMARC evaluation runs when DKIM completes (using accumulated SPF result
 *       and From domain), delivers result via {@link DMARCCallback}</li>
 * </ul>
 *
 * <p>Example usage:
 *
 * <pre><code>
 * AuthPipeline pipeline = new AuthPipeline.Builder(resolver, clientIP, heloHost)
 *     .onSPF((result, explanation) -&gt; {
 *         if (result == SPFResult.FAIL) {
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
 * @see SMTPPipeline
 * @see DMARCValidator
 * @see DMARCMessageHandler
 */
public class AuthPipeline implements SMTPPipeline {

    private static final Logger LOGGER = Logger.getLogger(AuthPipeline.class.getName());

    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.smtp.auth.L10N");

    private final DNSResolver resolver;
    private final InetAddress clientIP;
    private final String heloHost;

    // User callbacks
    private final SPFCallback spfCallback;
    private final DKIMCallback dkimCallback;
    private final DMARCCallback dmarcCallback;

    // User's message handler for teed content
    private final MessageHandler messageHandler;

    // Validators
    private final SPFValidator spfValidator;
    private final DKIMValidator dkimValidator;

    // Per-message state
    private DKIMMessageParser parser;
    private DMARCValidator dmarcValidator;

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
        this.messageHandler = builder.messageHandler;

        // Create validators
        this.spfValidator = new SPFValidator(resolver);
        this.dkimValidator = new DKIMValidator(resolver);
    }

    // -- SMTPPipeline implementation --

    @Override
    public void mailFrom(EmailAddress sender) {
        // Create DMARCValidator for this message (it aggregates SPF + DKIM results)
        dmarcValidator = new DMARCValidator(resolver, dmarcCallback);

        // Set SPF domain on DMARCValidator
        String spfDomain = (sender != null) ? sender.getDomain() : heloHost;
        dmarcValidator.setSpfDomain(spfDomain);

        // Create SPF callback that forwards to both user callback and DMARCValidator
        SPFCallback effectiveSpfCallback = new SPFCallback() {
            @Override
            public void spfResult(SPFResult result, String explanation) {
                // Forward to DMARCValidator for DMARC evaluation
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
        parser = new DKIMMessageParser();

        // Create DMARCMessageHandler that:
        // 1. Extracts From domain for DMARC
        // 2. Tees to user's MessageHandler
        DMARCMessageHandler.FromDomainCallback fromDomainCallback =
                new DMARCMessageHandler.FromDomainCallback() {
                    @Override
                    public void onFromDomain(String domain) {
                        dmarcValidator.setFromDomain(domain);
                    }
                };
        DMARCMessageHandler dmarcHandler = new DMARCMessageHandler(fromDomainCallback, messageHandler);
        parser.setMessageHandler(dmarcHandler);
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
        } catch (MIMEParseException e) {
            LOGGER.log(Level.WARNING, "Error closing message parser", e);
        }

        // Create DKIM callback that forwards to both user callback and DMARCValidator
        DKIMCallback effectiveDkimCallback = new DKIMCallback() {
            @Override
            public void dkimResult(DKIMResult result, String signingDomain, String selector) {
                // Forward to user callback if registered
                if (dkimCallback != null) {
                    dkimCallback.dkimResult(result, signingDomain, selector);
                }
                // Forward to DMARCValidator - this triggers DMARC evaluation
                dmarcValidator.dkimResult(result, signingDomain, selector);
            }
        };

        // Verify DKIM signature
        dkimValidator.setMessageParser(parser);
        byte[] bodyHash = parser.getBodyHash();
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
     * WritableByteChannel that forwards bytes to the DKIMMessageParser.
     */
    private static class ParserChannel implements WritableByteChannel {

        private final DKIMMessageParser parser;
        private boolean open = true;

        ParserChannel(DKIMMessageParser parser) {
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
                } catch (MIMEParseException e) {
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

        private final DNSResolver resolver;
        private final InetAddress clientIP;
        private final String heloHost;

        private SPFCallback spfCallback;
        private DKIMCallback dkimCallback;
        private DMARCCallback dmarcCallback;
        private MessageHandler messageHandler;

        /**
         * Creates a new builder.
         *
         * @param resolver the DNS resolver to use for lookups
         * @param clientIP the IP address of the connecting client
         * @param heloHost the HELO/EHLO hostname from the client
         */
        public Builder(DNSResolver resolver, InetAddress clientIP, String heloHost) {
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
        public Builder onSPF(SPFCallback callback) {
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
        public Builder onDKIM(DKIMCallback callback) {
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
        public Builder onDMARC(DMARCCallback callback) {
            this.dmarcCallback = callback;
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
