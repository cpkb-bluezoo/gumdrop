/*
 * ImapMetadataHandlerHost.java
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

package org.bluezoo.gumdrop.imap;

import org.bluezoo.gumdrop.StorageExecutor;
import org.bluezoo.gumdrop.mailbox.Mailbox;
import org.bluezoo.gumdrop.mailbox.MailboxStore;

import java.io.IOException;
import java.util.concurrent.Callable;

/**
 * Adapts {@link ImapProtocolHandler} to {@link ImapMetadataSupport.Host}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class ImapMetadataHandlerHost implements ImapMetadataSupport.Host {

    private final ImapProtocolHandler handler;

    ImapMetadataHandlerHost(ImapProtocolHandler handler) {
        this.handler = handler;
    }

    @Override
    public ImapListener getServer() {
        return handler.getServer();
    }

    @Override
    public MailboxStore getStore() {
        return handler.getMailboxStore();
    }

    @Override
    public ImapMetadataFileStore getMetadataStore() {
        return handler.getMetadataFileStore();
    }

    @Override
    public void sendUntagged(String line) throws IOException {
        handler.notifySendUntagged(line);
    }

    @Override
    public void sendTaggedOk(String tag, String message) throws IOException {
        handler.notifySendTaggedOk(tag, message);
    }

    @Override
    public void sendTaggedNo(String tag, String message) throws IOException {
        handler.notifySendTaggedNo(tag, message);
    }

    @Override
    public void sendTaggedBad(String tag, String message) throws IOException {
        handler.notifySendTaggedBad(tag, message);
    }

    @Override
    public String quoteMailboxName(String mailboxName) {
        return handler.quoteMailboxName(mailboxName);
    }

    @Override
    public String quoteMetadataValue(String value) {
        return handler.quoteImapString(value);
    }

    @Override
    public boolean mailboxExists(String mailboxName) throws IOException {
        MailboxStore st = handler.getMailboxStore();
        if (st == null) {
            return false;
        }
        Mailbox mailbox = st.openMailbox(mailboxName, true);
        try {
            return mailbox != null;
        } finally {
            mailbox.close(true);
        }
    }

    @Override
    public <T> void submitStorage(Callable<T> work,
            StorageExecutor.Callback<T> callback) {
        handler.submitMetadataStorage(work, callback);
    }
}
