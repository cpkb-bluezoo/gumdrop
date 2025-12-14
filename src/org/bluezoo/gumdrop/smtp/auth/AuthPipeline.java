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

import org.bluezoo.gumdrop.dns.DNSResolver;
import org.bluezoo.gumdrop.mime.rfc5322.EmailAddress;
import org.bluezoo.gumdrop.mime.rfc5322.MessageHandler;
import org.bluezoo.gumdrop.smtp.SMTPPipeline;

/**
 * Authentication pipeline for SPF, DKIM, and DMARC checks.
 *
 * <p>AuthPipeline implements {@link SMTPPipeline} to integrate with
 * SMTPConnection. Configure it with callbacks for the checks you want,
 * then associate it with the connection. The SMTP layer automatically:
 *
 * <ul>
 *   <li>Runs SPF check on MAIL FROM and invokes your SPFCallback</li>
 *   <li>Feeds raw message bytes for DKIM signature verification</li>
 *   <li>Tees bytes to your registered MessageHandler (if any)</li>
 *   <li>Runs DKIM verification at end-of-data and invokes your DKIMCallback</li>
 *   <li>Runs DMARC evaluation and invokes your DMARCCallback</li>
 * </ul>
 *
 * <p>Example usage:
 *
 * <pre><code>
 * // In connected() or mailFrom(), create and configure pipeline
 * AuthPipeline.Builder builder = new AuthPipeline.Builder(resolver, clientIP, heloHost);
 *
 * builder.onSPF(new SPFCallback() {
 *     public void onResult(SPFResult result, String explanation) {
 *         if (result == SPFResult.FAIL) {
 *             callback.mailFromReply(SenderPolicyResult.REJECT);
 *         } else {
 *             callback.mailFromReply(SenderPolicyResult.ACCEPT);
 *         }
 *     }
 * });
 *
 * builder.onDMARC(new DMARCCallback() {
 *     public void onResult(DMARCResult result, DMARCPolicy policy, String domain) {
 *         if (result == DMARCResult.FAIL &amp;&amp; policy == DMARCPolicy.REJECT) {
 *             dataCallback.reply(DataEndReply.REJECT);
 *         } else {
 *             dataCallback.reply(DataEndReply.ACCEPT);
 *         }
 *     }
 * });
 *
 * // Associate with connection
 * connection.setPipeline(builder.build());
 * </code></pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see SMTPPipeline
 * @see AuthCheck
 */
public class AuthPipeline implements SMTPPipeline {

    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.smtp.auth.L10N");

    private final DNSResolver resolver;
    private final InetAddress clientIP;
    private final String heloHost;

    // Callbacks
    private final SPFCallback spfCallback;
    private final DKIMCallback dkimCallback;
    private final DMARCCallback dmarcCallback;

    // User's message handler for teed content
    private final MessageHandler messageHandler;

    // Shared validator instances
    private final SPFValidator spfValidator;
    private final DKIMValidator dkimValidator;
    private final DMARCValidator dmarcValidator;

    // Per-message state
    private AuthCheck currentCheck;
    private AuthCheckChannel currentChannel;

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

        // Create validators based on what callbacks are registered
        this.spfValidator = (spfCallback != null) ? new SPFValidator(resolver) : null;
        this.dkimValidator = (dkimCallback != null || dmarcCallback != null)
                ? new DKIMValidator(resolver) : null;
        this.dmarcValidator = (dmarcCallback != null)
                ? new DMARCValidator(resolver) : null;
    }

    // -- SMTPPipeline implementation --

    @Override
    public void mailFrom(EmailAddress sender) {
        // Create new check for this message
        currentCheck = new AuthCheck(this, clientIP, heloHost);
        currentCheck.checkSender(sender);
    }

    @Override
    public void rcptTo(EmailAddress recipient) {
        // Authentication doesn't care about recipients
    }

    @Override
    public WritableByteChannel getMessageChannel() {
        if (currentCheck == null) {
            return null;
        }
        currentChannel = new AuthCheckChannel(currentCheck);
        return currentChannel;
    }

    @Override
    public void endData() {
        if (currentCheck != null) {
            currentCheck.close();
        }
    }

    @Override
    public void reset() {
        currentCheck = null;
        currentChannel = null;
    }

    // -- Internal accessors for AuthCheck --

    /**
     * Returns whether SPF checking is enabled.
     */
    boolean isSPFEnabled() {
        return spfValidator != null;
    }

    /**
     * Returns whether DKIM checking is enabled.
     */
    boolean isDKIMEnabled() {
        return dkimValidator != null;
    }

    /**
     * Returns whether DMARC checking is enabled.
     */
    boolean isDMARCEnabled() {
        return dmarcValidator != null;
    }

    SPFCallback getSPFCallback() {
        return spfCallback;
    }

    DKIMCallback getDKIMCallback() {
        return dkimCallback;
    }

    DMARCCallback getDMARCCallback() {
        return dmarcCallback;
    }

    MessageHandler getMessageHandler() {
        return messageHandler;
    }

    SPFValidator getSPFValidator() {
        return spfValidator;
    }

    DKIMValidator getDKIMValidator() {
        return dkimValidator;
    }

    DMARCValidator getDMARCValidator() {
        return dmarcValidator;
    }

    DNSResolver getResolver() {
        return resolver;
    }

    // -- Channel adapter --

    /**
     * WritableByteChannel adapter that forwards writes to AuthCheck.
     */
    private static class AuthCheckChannel implements WritableByteChannel {

        private final AuthCheck check;
        private boolean open = true;

        AuthCheckChannel(AuthCheck check) {
            this.check = check;
        }

        @Override
        public int write(ByteBuffer src) throws IOException {
            int remaining = src.remaining();
            check.receive(src);
            return remaining;
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() throws IOException {
            open = false;
            // Don't call check.close() here - endData() does that
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
         * <p>The raw message bytes will be teed to a MessageParser that
         * invokes this handler.
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
