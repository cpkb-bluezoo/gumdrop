/*
 * DMARCMessageHandler.java
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

import java.nio.ByteBuffer;
import java.time.OffsetDateTime;
import java.util.List;

import org.bluezoo.gumdrop.mime.ContentDisposition;
import org.bluezoo.gumdrop.mime.ContentID;
import org.bluezoo.gumdrop.mime.ContentType;
import org.bluezoo.gumdrop.mime.MIMELocator;
import org.bluezoo.gumdrop.mime.MIMEParseException;
import org.bluezoo.gumdrop.mime.MIMEVersion;
import org.bluezoo.gumdrop.mime.rfc5322.EmailAddress;
import org.bluezoo.gumdrop.mime.rfc5322.MessageHandler;
import org.bluezoo.gumdrop.mime.rfc5322.ObsoleteStructureType;

/**
 * A tee MessageHandler that intercepts the From header for DMARC validation.
 *
 * <p>This handler:
 * <ul>
 *   <li>Intercepts {@code addressHeader("From", ...)} to extract the From domain</li>
 *   <li>Delivers the domain to a registered consumer (typically DMARCValidator)</li>
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
 * DMARCMessageHandler dmarcHandler = new DMARCMessageHandler(callback, userHandler);
 * dkimParser.setMessageHandler(dmarcHandler);
 * </code></pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see DMARCValidator
 * @see AuthPipeline
 */
public class DMARCMessageHandler implements MessageHandler {

    /**
     * Callback interface for receiving the From domain.
     */
    public interface FromDomainCallback {
        /**
         * Called when the From domain is extracted from the message.
         * @param domain the domain part of the first From address
         */
        void onFromDomain(String domain);
    }

    private final FromDomainCallback fromDomainCallback;
    private final MessageHandler delegate;

    /**
     * Creates a DMARC message handler.
     *
     * @param fromDomainCallback receives the From domain when extracted
     * @param delegate optional delegate handler to receive all events (may be null)
     */
    public DMARCMessageHandler(FromDomainCallback fromDomainCallback, MessageHandler delegate) {
        this.fromDomainCallback = fromDomainCallback;
        this.delegate = delegate;
    }

    @Override
    public void addressHeader(String name, List<EmailAddress> addresses) throws MIMEParseException {
        // Intercept From header
        if ("From".equalsIgnoreCase(name) && addresses != null && !addresses.isEmpty()) {
            String domain = addresses.get(0).getDomain();
            if (domain != null && !domain.isEmpty() && fromDomainCallback != null) {
                fromDomainCallback.onFromDomain(domain);
            }
        }

        // Delegate
        if (delegate != null) {
            delegate.addressHeader(name, addresses);
        }
    }

    // -- Delegate all other events --

    @Override
    public void setLocator(MIMELocator locator) {
        if (delegate != null) {
            delegate.setLocator(locator);
        }
    }

    @Override
    public void startEntity(String boundary) throws MIMEParseException {
        if (delegate != null) {
            delegate.startEntity(boundary);
        }
    }

    @Override
    public void contentType(ContentType contentType) throws MIMEParseException {
        if (delegate != null) {
            delegate.contentType(contentType);
        }
    }

    @Override
    public void contentDisposition(ContentDisposition contentDisposition) throws MIMEParseException {
        if (delegate != null) {
            delegate.contentDisposition(contentDisposition);
        }
    }

    @Override
    public void contentTransferEncoding(String encoding) throws MIMEParseException {
        if (delegate != null) {
            delegate.contentTransferEncoding(encoding);
        }
    }

    @Override
    public void contentID(ContentID contentID) throws MIMEParseException {
        if (delegate != null) {
            delegate.contentID(contentID);
        }
    }

    @Override
    public void contentDescription(String description) throws MIMEParseException {
        if (delegate != null) {
            delegate.contentDescription(description);
        }
    }

    @Override
    public void mimeVersion(MIMEVersion version) throws MIMEParseException {
        if (delegate != null) {
            delegate.mimeVersion(version);
        }
    }

    @Override
    public void endHeaders() throws MIMEParseException {
        if (delegate != null) {
            delegate.endHeaders();
        }
    }

    @Override
    public void bodyContent(ByteBuffer data) throws MIMEParseException {
        if (delegate != null) {
            delegate.bodyContent(data);
        }
    }

    @Override
    public void unexpectedContent(ByteBuffer data) throws MIMEParseException {
        if (delegate != null) {
            delegate.unexpectedContent(data);
        }
    }

    @Override
    public void endEntity(String boundary) throws MIMEParseException {
        if (delegate != null) {
            delegate.endEntity(boundary);
        }
    }

    @Override
    public void header(String name, String value) throws MIMEParseException {
        if (delegate != null) {
            delegate.header(name, value);
        }
    }

    @Override
    public void unexpectedHeader(String name, String value) throws MIMEParseException {
        if (delegate != null) {
            delegate.unexpectedHeader(name, value);
        }
    }

    @Override
    public void dateHeader(String name, OffsetDateTime date) throws MIMEParseException {
        if (delegate != null) {
            delegate.dateHeader(name, date);
        }
    }

    @Override
    public void messageIDHeader(String name, List<ContentID> contentIDs) throws MIMEParseException {
        if (delegate != null) {
            delegate.messageIDHeader(name, contentIDs);
        }
    }

    @Override
    public void obsoleteStructure(ObsoleteStructureType type) throws MIMEParseException {
        if (delegate != null) {
            delegate.obsoleteStructure(type);
        }
    }

}

