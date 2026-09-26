/*
 * ImapNotifyHandlerHost.java
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

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.mailbox.Mailbox;
import org.bluezoo.gumdrop.mailbox.MailboxStore;

import java.io.IOException;

/**
 * Adapts {@link ImapProtocolHandler} to {@link ImapNotifySupport.Host}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class ImapNotifyHandlerHost implements ImapNotifySupport.Host {

    private final ImapProtocolHandler handler;

    ImapNotifyHandlerHost(ImapProtocolHandler handler) {
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
    public Mailbox getSelectedMailbox() {
        return handler.getSelectedMailbox();
    }

    @Override
    public String getSelectedMailboxName() {
        return handler.getSelectedMailboxName();
    }

    @Override
    public boolean isSelectedState() {
        return handler.isSelectedState();
    }

    @Override
    public boolean isIdling() {
        return handler.isIdling();
    }

    @Override
    public boolean isNotifyEnabled() {
        return handler.isNotifyEnabled();
    }

    @Override
    public boolean isCondstoreEnabled() {
        return handler.isCondstoreEnabled();
    }

    @Override
    public boolean isQresyncEnabled() {
        return handler.isQresyncEnabled();
    }

    @Override
    public boolean canDeliverUnsolicited() {
        return handler.canDeliverUnsolicitedNotify();
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
    public void sendSelectedMailboxUpdates() throws IOException {
        handler.sendSelectedMailboxUpdates();
    }

    @Override
    public String quoteMailboxName(String mailboxName) {
        return handler.quoteMailboxName(mailboxName);
    }

    @Override
    public Endpoint getEndpoint() {
        return handler.getEndpoint();
    }
}
