/*
 * RecordingSmtpStates
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

package org.bluezoo.gumdrop.smtp.server;

import java.util.ArrayList;
import java.util.List;

/**
 * Records which state method an SMTP server handler invoked, with the message
 * of the last policy or message rejection.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class RecordingSmtpStates implements ConnectedState, HelloState,
        AuthenticateState, MailFromState, ResetState, RecipientState,
        MessageStartState, MessageEndState {
    final List<String> calls = new ArrayList<String>();
    String policyMessage;
    String lastMessage;

    public void acceptConnection(String greeting, HelloHandler handler) {
        calls.add("acceptConnection:" + greeting);
    }
    public void rejectConnection() { calls.add("rejectConnection"); }
    public void rejectConnection(String message) { calls.add("rejectConnection"); }
    public void serverShuttingDown() { calls.add("serverShuttingDown"); }
    public void acceptHello(MailFromHandler handler) { calls.add("acceptHello"); }
    public void rejectHelloTemporary(String m, HelloHandler h) { calls.add("x"); }
    public void rejectHello(String m, HelloHandler h) { calls.add("x"); }
    public void rejectHelloAndClose(String m) { calls.add("x"); }
    public void accept(MailFromHandler handler) { calls.add("accept"); }
    public void reject(HelloHandler handler) { calls.add("x"); }
    public void rejectAndClose() { calls.add("x"); }
    public void acceptSender(RecipientHandler handler) { calls.add("acceptSender"); }
    public void rejectSenderGreylist(MailFromHandler h) { calls.add("x"); }
    public void rejectSenderRateLimit(MailFromHandler h) { calls.add("x"); }
    public void rejectSenderStorageFull(MailFromHandler h) { calls.add("x"); }
    public void rejectSenderBlockedDomain(MailFromHandler h) { calls.add("x"); }
    public void rejectSenderInvalidDomain(MailFromHandler h) { calls.add("x"); }
    public void rejectSenderPolicy(String message, MailFromHandler h) {
        policyMessage = message;
        calls.add("rejectSenderPolicy");
    }
    public void rejectSenderSpam(MailFromHandler h) { calls.add("x"); }
    public void rejectSenderSyntax(MailFromHandler h) { calls.add("x"); }
    public void acceptReset(MailFromHandler handler) { calls.add("acceptReset"); }
    public void acceptRecipient(RecipientHandler handler) { calls.add("acceptRecipient"); }
    public void acceptRecipientForward(String f, RecipientHandler h) { calls.add("x"); }
    public void rejectRecipientUnavailable(RecipientHandler h) { calls.add("x"); }
    public void rejectRecipientSystemError(RecipientHandler h) { calls.add("x"); }
    public void rejectRecipientStorageFull(RecipientHandler h) { calls.add("x"); }
    public void rejectRecipientNotFound(RecipientHandler h) { calls.add("x"); }
    public void rejectRecipientNotLocal(RecipientHandler h) { calls.add("x"); }
    public void rejectRecipientQuota(RecipientHandler h) { calls.add("x"); }
    public void rejectRecipientInvalid(RecipientHandler h) { calls.add("x"); }
    public void rejectRecipientRelayDenied(RecipientHandler h) { calls.add("rejectRecipientRelayDenied"); }
    public void rejectRecipientPolicy(String m, RecipientHandler h) { calls.add("x"); }
    public void acceptMessage(MessageDataHandler handler) { calls.add("acceptMessage"); }
    public void rejectMessageStorageFull(RecipientHandler h) { calls.add("x"); }
    public void rejectMessageProcessingError(RecipientHandler h) { calls.add("x"); }
    public void rejectMessage(String m, MailFromHandler h) {
        lastMessage = m;
        calls.add("rejectMessage");
    }
    public void acceptMessageDelivery(String q, MailFromHandler h) {
        calls.add("acceptMessageDelivery");
    }
    public void rejectMessageTemporary(String m, MailFromHandler h) {
        lastMessage = m;
        calls.add("rejectMessageTemporary");
    }
    public void rejectMessagePermanent(String m, MailFromHandler h) { calls.add("x"); }
    public void rejectMessagePolicy(String m, MailFromHandler h) { calls.add("x"); }
}
