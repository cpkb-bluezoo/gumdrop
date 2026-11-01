/*
 * DmarcMessageHandler.java
 * Copyright (C) 2025, 2026 Chris Burdess
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

import java.nio.ByteBuffer;
import java.time.OffsetDateTime;
import java.util.List;

import org.bluezoo.gumdrop.mime.ContentDisposition;
import org.bluezoo.gumdrop.mime.ContentID;
import org.bluezoo.gumdrop.mime.ContentType;
import org.bluezoo.gumdrop.mime.MimeLocator;
import org.bluezoo.gumdrop.mime.MimeParseException;
import org.bluezoo.gumdrop.mime.MimeVersion;
import org.bluezoo.gumdrop.mime.rfc5322.EmailAddress;
import org.bluezoo.gumdrop.mime.rfc5322.MessageHandler;
import org.bluezoo.gumdrop.mime.rfc5322.ObsoleteStructureType;

/**
 * A tee MessageHandler that intercepts the From header for DMARC validation.
 * RFC 7489 — extracts RFC 5322.From domain for alignment.
 *
 * <p>This handler:
 * <ul>
 *   <li>Intercepts the address events of the From header to extract the From domain</li>
 *   <li>Delivers the domain to a registered consumer (typically DmarcValidator)</li>
 *   <li>Proxies all events to an optional delegate MessageHandler</li>
 * </ul>
 *
 * <p>Usage in AuthPipeline:
 * <pre><code>
 * FromDomainCallback callback = new FromDomainCallback() {
 *     public void onFromDomain(String domain) {
 *         dmarcValidator.setFromDomain(domain);
 *     }
 * };
 * DmarcMessageHandler dmarcHandler = new DmarcMessageHandler(callback, userHandler);
 * dkimParser.setMessageHandler(dmarcHandler);
 * </code></pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see DmarcValidator
 * @see AuthPipeline
 * @see <a href="https://www.rfc-editor.org/rfc/rfc7489">RFC 7489 - DMARC</a>
 */
// The tee also passes on the older events, which are deprecated for removal
@SuppressWarnings("removal")
public class DmarcMessageHandler implements MessageHandler {

    /**
     * Callback interface for receiving the From domain.
     */
    public interface FromDomainCallback {
        /**
         * Called when the From domain is extracted from the message, or to
         * invalidate a previously-reported domain.
         * @param domain the domain part of the sole From address, or
         *      {@code null} if the message contains more than one From
         *      field (RFC 5322 §3.6.2 permits exactly one) and the domain
         *      can therefore no longer be trusted for DMARC alignment
         */
        void onFromDomain(String domain);
    }

    private final FromDomainCallback fromDomainCallback;
    private final MessageHandler delegate;

    /** RFC 5322 §3.6.2 permits exactly one From field; tracks whether one has already been seen. */
    private boolean fromSeen;

    /** The first mailbox of the From header being read has not been seen yet. */
    private boolean awaitingFromMailbox;

    /** An address header has been given by its events; its older event is to follow. */
    private boolean streamed;

    /**
     * Creates a DMARC message handler.
     *
     * @param fromDomainCallback receives the From domain when extracted
     * @param delegate optional delegate handler to receive all events (may be null)
     */
    public DmarcMessageHandler(FromDomainCallback fromDomainCallback, MessageHandler delegate) {
        this.fromDomainCallback = fromDomainCallback;
        this.delegate = delegate;
    }

    @Override
    public boolean wantsLegacyEvents() {
        return delegate != null && delegate.wantsLegacyEvents();
    }

    @Override
    public HeaderDelivery headerDelivery(String name) {
        return delegate != null ? delegate.headerDelivery(name) : HeaderDelivery.UNSTRUCTURED;
    }

    @Override
    public void startAddressHeader(String name) throws MimeParseException {
        // The older addressHeader that follows is for the same header
        streamed = true;
        awaitingFromMailbox = "From".equalsIgnoreCase(name) && fromHeader();
        if (delegate != null) {
            delegate.startAddressHeader(name);
        }
    }

    @Override
    public void mailbox(String displayName, String localPart, String domain) throws MimeParseException {
        if (awaitingFromMailbox) {
            awaitingFromMailbox = false;
            reportDomain(domain);
        }
        if (delegate != null) {
            delegate.mailbox(displayName, localPart, domain);
        }
    }

    @Override
    public void addressHeader(String name, List<EmailAddress> addresses) throws MimeParseException {
        if (streamed) {
            // already dealt with from the mailbox events
            streamed = false;
        } else if ("From".equalsIgnoreCase(name) && fromHeader()
                && addresses != null && !addresses.isEmpty()) {
            reportDomain(addresses.get(0).getDomain());
        }
        if (delegate != null) {
            delegate.addressHeader(name, addresses);
        }
    }

    /**
     * A From header begins.
     * @return true if it is the first, whose domain is to be reported
     */
    private boolean fromHeader() {
        if (fromSeen) {
            // RFC 7489 §7.6 — a second From field is suspect: real MUAs
            // disagree on which occurrence they display, so the domain
            // already reported (if any) can no longer be trusted for
            // DMARC alignment. Invalidate it rather than silently
            // resolving to whichever occurrence happens to be last.
            if (fromDomainCallback != null) {
                fromDomainCallback.onFromDomain(null);
            }
            return false;
        }
        fromSeen = true;
        return true;
    }

    private void reportDomain(String domain) {
        if (domain != null && !domain.isEmpty() && fromDomainCallback != null) {
            fromDomainCallback.onFromDomain(domain);
        }
    }

    // -- Delegate all other events --

    @Override
    public void setLocator(MimeLocator locator) {
        if (delegate != null) {
            delegate.setLocator(locator);
        }
    }

    @Override
    public void startEntity(String boundary) throws MimeParseException {
        if (delegate != null) {
            delegate.startEntity(boundary);
        }
    }

    @Override
    public void startHeader(String name) throws MimeParseException {
        if (delegate != null) {
            delegate.startHeader(name);
        }
    }

    @Override
    public void headerContent(String text) throws MimeParseException {
        if (delegate != null) {
            delegate.headerContent(text);
        }
    }

    @Override
    public void rawHeaderContent(ByteBuffer bytes) throws MimeParseException {
        if (delegate != null) {
            delegate.rawHeaderContent(bytes);
        }
    }

    @Override
    public void endHeader() throws MimeParseException {
        if (delegate != null) {
            delegate.endHeader();
        }
    }

    @Override
    public void startContentType(String primary, String sub) throws MimeParseException {
        if (delegate != null) {
            delegate.startContentType(primary, sub);
        }
    }

    @Override
    public void endContentType() throws MimeParseException {
        if (delegate != null) {
            delegate.endContentType();
        }
    }

    @Override
    public void startContentDisposition(String kind) throws MimeParseException {
        if (delegate != null) {
            delegate.startContentDisposition(kind);
        }
    }

    @Override
    public void endContentDisposition() throws MimeParseException {
        if (delegate != null) {
            delegate.endContentDisposition();
        }
    }

    @Override
    public void startParameter(String name, String language) throws MimeParseException {
        if (delegate != null) {
            delegate.startParameter(name, language);
        }
    }

    @Override
    public void parameterContent(String text) throws MimeParseException {
        if (delegate != null) {
            delegate.parameterContent(text);
        }
    }

    @Override
    public void endParameter() throws MimeParseException {
        if (delegate != null) {
            delegate.endParameter();
        }
    }

    @Override
    public void malformedHeader(String name, String reason) throws MimeParseException {
        if (delegate != null) {
            delegate.malformedHeader(name, reason);
        }
    }

    @Override
    public void contentType(ContentType contentType) throws MimeParseException {
        if (delegate != null) {
            delegate.contentType(contentType);
        }
    }

    @Override
    public void contentDisposition(ContentDisposition contentDisposition) throws MimeParseException {
        if (delegate != null) {
            delegate.contentDisposition(contentDisposition);
        }
    }

    @Override
    public void contentTransferEncoding(String encoding) throws MimeParseException {
        if (delegate != null) {
            delegate.contentTransferEncoding(encoding);
        }
    }

    @Override
    public void contentID(ContentID contentID) throws MimeParseException {
        if (delegate != null) {
            delegate.contentID(contentID);
        }
    }

    @Override
    public void contentID(String localPart, String domain) throws MimeParseException {
        if (delegate != null) {
            delegate.contentID(localPart, domain);
        }
    }

    @Override
    public void contentDescription(String description) throws MimeParseException {
        if (delegate != null) {
            delegate.contentDescription(description);
        }
    }

    @Override
    public void mimeVersion(MimeVersion version) throws MimeParseException {
        if (delegate != null) {
            delegate.mimeVersion(version);
        }
    }

    @Override
    public void mimeVersion(int major, int minor) throws MimeParseException {
        if (delegate != null) {
            delegate.mimeVersion(major, minor);
        }
    }

    @Override
    public void endHeaders() throws MimeParseException {
        if (delegate != null) {
            delegate.endHeaders();
        }
    }

    @Override
    public void bodyContent(ByteBuffer data) throws MimeParseException {
        if (delegate != null) {
            delegate.bodyContent(data);
        }
    }

    @Override
    public void unexpectedContent(ByteBuffer data) throws MimeParseException {
        if (delegate != null) {
            delegate.unexpectedContent(data);
        }
    }

    @Override
    public void endEntity(String boundary) throws MimeParseException {
        if (delegate != null) {
            delegate.endEntity(boundary);
        }
    }

    @Override
    public void header(String name, String value) throws MimeParseException {
        if (delegate != null) {
            delegate.header(name, value);
        }
    }

    @Override
    public void unexpectedHeader(String name, String value) throws MimeParseException {
        if (delegate != null) {
            delegate.unexpectedHeader(name, value);
        }
    }

    @Override
    public void dateHeader(String name, OffsetDateTime date) throws MimeParseException {
        if (delegate != null) {
            delegate.dateHeader(name, date);
        }
    }

    @Override
    public void startGroup(String name) throws MimeParseException {
        if (delegate != null) {
            delegate.startGroup(name);
        }
    }

    @Override
    public void endGroup() throws MimeParseException {
        if (delegate != null) {
            delegate.endGroup();
        }
    }

    @Override
    public void endAddressHeader() throws MimeParseException {
        if (delegate != null) {
            delegate.endAddressHeader();
        }
    }

    @Override
    public void startMessageIDHeader(String name) throws MimeParseException {
        if (delegate != null) {
            delegate.startMessageIDHeader(name);
        }
    }

    @Override
    public void messageID(String localPart, String domain) throws MimeParseException {
        if (delegate != null) {
            delegate.messageID(localPart, domain);
        }
    }

    @Override
    public void endMessageIDHeader() throws MimeParseException {
        if (delegate != null) {
            delegate.endMessageIDHeader();
        }
    }

    @Override
    public void messageIDHeader(String name, List<ContentID> contentIDs) throws MimeParseException {
        if (delegate != null) {
            delegate.messageIDHeader(name, contentIDs);
        }
    }

    @Override
    public void obsoleteStructure(ObsoleteStructureType type) throws MimeParseException {
        if (delegate != null) {
            delegate.obsoleteStructure(type);
        }
    }

}
