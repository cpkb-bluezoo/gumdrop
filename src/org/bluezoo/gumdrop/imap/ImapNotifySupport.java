/*
 * ImapNotifySupport.java
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
import org.bluezoo.gumdrop.TimerHandle;
import org.bluezoo.gumdrop.mailbox.Mailbox;
import org.bluezoo.gumdrop.mailbox.MailboxStore;

import java.io.IOException;
import java.text.ParseException;
import java.util.ResourceBundle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * RFC 5465 NOTIFY subscription state and delivery for one IMAP session.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class ImapNotifySupport {

    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.imap.L10N");

    private static final long NOTIFY_POLL_INTERVAL_MS = 1000L;
    private static final int MAX_WATCHED_MAILBOXES = 128;

    private static final Set<ImapNotifyEventType> SUPPORTED_EVENTS =
            EnumSet.of(
                    ImapNotifyEventType.MESSAGE_NEW,
                    ImapNotifyEventType.MESSAGE_EXPUNGE,
                    ImapNotifyEventType.FLAG_CHANGE,
                    ImapNotifyEventType.MAILBOX_NAME,
                    ImapNotifyEventType.SUBSCRIPTION_CHANGE);

    private static final String SUPPORTED_EVENTS_BADEVENT =
            "BADEVENT (MessageNew MessageExpunge FlagChange MailboxName "
                    + "SubscriptionChange)";

    /**
     * Session callbacks into {@link ImapProtocolHandler}.
     */
    public interface Host {
        ImapListener getServer();

        MailboxStore getStore();

        Mailbox getSelectedMailbox();

        String getSelectedMailboxName();

        boolean isSelectedState();

        boolean isIdling();

        boolean isNotifyEnabled();

        boolean isCondstoreEnabled();

        boolean isQresyncEnabled();

        boolean canDeliverUnsolicited();

        void sendUntagged(String line) throws IOException;

        void sendTaggedOk(String tag, String message) throws IOException;

        void sendTaggedNo(String tag, String message) throws IOException;

        void sendTaggedBad(String tag, String message) throws IOException;

        void sendSelectedMailboxUpdates() throws IOException;

        String quoteMailboxName(String mailboxName);

        Endpoint getEndpoint();
    }

    private final Host host;
    private ImapNotifyRequest subscription;
    private TimerHandle pollTimerHandle;
    private final Map<String, MailboxWatchState> watchStates =
            new HashMap<String, MailboxWatchState>();

    public ImapNotifySupport(Host host) {
        this.host = host;
    }

    public boolean hasActiveSubscription() {
        return subscription != null && subscription.getForm()
                == ImapNotifyRequest.Form.SET;
    }

    public void disconnected() {
        cancelPollTimer();
        subscription = null;
        watchStates.clear();
    }

    public void handleEnableNotify() {
        // Activation flag is set on the handler; nothing else here.
    }

    public void handleNotify(String tag, String args) throws IOException {
        if (!host.getServer().isEnableNOTIFY()) {
            host.sendTaggedBad(tag,
                    L10N.getString("imap.err.unknown_command"));
            return;
        }
        if (!host.isNotifyEnabled()) {
            host.sendTaggedNo(tag,
                    L10N.getString("imap.err.notify_not_enabled"));
            return;
        }
        if (args == null || args.trim().isEmpty()) {
            host.sendTaggedBad(tag,
                    L10N.getString("imap.err.invalid_arguments"));
            return;
        }
        ImapNotifyRequest request;
        try {
            request = new ImapNotifyParser(args).parse();
        } catch (ParseException e) {
            host.sendTaggedBad(tag,
                    L10N.getString("imap.err.notify_syntax"));
            return;
        }
        if (request.getForm() == ImapNotifyRequest.Form.NONE) {
            applySubscription(null);
            host.sendTaggedOk(tag, L10N.getString("imap.notify_complete"));
            return;
        }
        String validationError = validateSetRequest(request);
        if (validationError != null) {
            if (validationError.startsWith("BADEVENT:")) {
                host.sendTaggedNo(tag, validationError.substring(9));
            } else if (validationError.startsWith("BAD:")) {
                host.sendTaggedBad(tag, validationError.substring(4));
            } else if (validationError.startsWith("OVERFLOW")) {
                host.sendTaggedNo(tag,
                        "[NOTIFICATIONOVERFLOW] "
                                + L10N.getString("imap.err.notify_overflow"));
            } else {
                host.sendTaggedNo(tag, validationError);
            }
            return;
        }
        if (request.isStatusIndicator()) {
            sendInitialStatusResponses(request);
        }
        applySubscription(request);
        if (host.isSelectedState() && host.getSelectedMailbox() != null) {
            host.sendSelectedMailboxUpdates();
        }
        host.sendTaggedOk(tag, L10N.getString("imap.notify_complete"));
    }

    public void pollIfNeeded() {
        if (!hasActiveSubscription() || !host.canDeliverUnsolicited()) {
            return;
        }
        if (!host.isIdling() && !host.canDeliverUnsolicited()) {
            return;
        }
        try {
            deliverUpdates();
        } catch (IOException e) {
            // Best effort; connection may be closing.
        }
    }

    public void onIdleTick() throws IOException {
        if (hasActiveSubscription()) {
            deliverUpdates();
        }
    }

    private void applySubscription(ImapNotifyRequest request) {
        cancelPollTimer();
        watchStates.clear();
        subscription = request;
        if (request != null && request.getForm() == ImapNotifyRequest.Form.SET) {
            startPollTimer();
        }
    }

    private void startPollTimer() {
        if (host.getEndpoint() == null) {
            return;
        }
        pollTimerHandle = host.getEndpoint().scheduleTimer(
                NOTIFY_POLL_INTERVAL_MS, new Runnable() {
            @Override
            public void run() {
                pollIfNeeded();
                if (hasActiveSubscription()) {
                    startPollTimer();
                }
            }
        });
    }

    private void cancelPollTimer() {
        if (pollTimerHandle != null) {
            pollTimerHandle.cancel();
            pollTimerHandle = null;
        }
    }

    private String validateSetRequest(ImapNotifyRequest request) {
        boolean sawSelected = false;
        Set<ImapNotifyEventType> requestedUnsupported =
                new LinkedHashSet<ImapNotifyEventType>();
        for (ImapNotifyEventGroup group : request.getEventGroups()) {
            ImapNotifyMailboxFilter filter = group.getMailboxFilter();
            if (filter.affectsSelectedMailbox()) {
                if (sawSelected) {
                    return "BAD:"
                            + L10N.getString("imap.err.notify_one_selected");
                }
                sawSelected = true;
            }
            if (group.isEventsNone()) {
                continue;
            }
            for (ImapNotifyEventSpec spec : group.getEvents()) {
                ImapNotifyEventType type = spec.getType();
                if (!SUPPORTED_EVENTS.contains(type)) {
                    requestedUnsupported.add(type);
                }
                if (filter.affectsSelectedMailbox() && !type.isMessageEvent()) {
                    return "BAD:"
                            + L10N.getString("imap.err.notify_selected_events");
                }
            }
            if (!group.isEventsNone()) {
                String pairError = validateMessageEventPairing(group.getEvents());
                if (pairError != null) {
                    return "BAD:" + pairError;
                }
            }
        }
        if (!requestedUnsupported.isEmpty()) {
            return "BADEVENT:" + SUPPORTED_EVENTS_BADEVENT;
        }
        try {
            Set<String> mailboxes = resolveWatchedMailboxes(request);
            if (mailboxes.size() > MAX_WATCHED_MAILBOXES) {
                return "OVERFLOW";
            }
        } catch (IOException e) {
            return L10N.getString("imap.err.notify_failed");
        }
        return null;
    }

    private static String validateMessageEventPairing(
            List<ImapNotifyEventSpec> events) {
        boolean messageNew = false;
        boolean messageExpunge = false;
        boolean flagChange = false;
        for (ImapNotifyEventSpec spec : events) {
            ImapNotifyEventType type = spec.getType();
            if (type == ImapNotifyEventType.MESSAGE_NEW) {
                messageNew = true;
            } else if (type == ImapNotifyEventType.MESSAGE_EXPUNGE) {
                messageExpunge = true;
            } else if (type == ImapNotifyEventType.FLAG_CHANGE) {
                flagChange = true;
            }
        }
        if ((messageNew || messageExpunge)
                && !(messageNew && messageExpunge)) {
            return L10N.getString("imap.err.notify_message_pair");
        }
        if (flagChange && !(messageNew && messageExpunge)) {
            return L10N.getString("imap.err.notify_flag_requires_messages");
        }
        return null;
    }

    private void sendInitialStatusResponses(ImapNotifyRequest request)
            throws IOException {
        MailboxStore store = host.getStore();
        if (store == null) {
            return;
        }
        String selectedName = host.getSelectedMailboxName();
        Set<String> names = new LinkedHashSet<String>();
        try {
            names.addAll(resolveWatchedMailboxes(request));
        } catch (IOException e) {
            return;
        }
        for (String name : names) {
            if (name.equalsIgnoreCase(selectedName)) {
                continue;
            }
            MailboxWatchState state = snapshotMailbox(store, name);
            if (state == null) {
                continue;
            }
            host.sendUntagged(buildStatusLine(name, state, true, true));
            watchStates.put(normalizeMailboxName(name), state);
        }
    }

    private void deliverUpdates() throws IOException {
        if (subscription == null || subscription.getForm()
                != ImapNotifyRequest.Form.SET) {
            return;
        }
        MailboxStore store = host.getStore();
        if (store == null) {
            return;
        }
        String selectedName = host.getSelectedMailboxName();
        if (host.isSelectedState() && host.getSelectedMailbox() != null
                && wantsSelectedMessageEvents()) {
            host.sendSelectedMailboxUpdates();
        }
        Set<String> names = resolveWatchedMailboxes(subscription);
        for (String name : names) {
            if (host.isSelectedState() && selectedName != null
                    && name.equalsIgnoreCase(selectedName)) {
                continue;
            }
            MailboxWatchState previous = watchStates.get(
                    normalizeMailboxName(name));
            MailboxWatchState current = snapshotMailbox(store, name);
            if (current == null) {
                continue;
            }
            String key = normalizeMailboxName(name);
            if (previous == null) {
                watchStates.put(key, current);
                continue;
            }
            if (wantsMessageEventsFor(name)
                    && (current.messageCount != previous.messageCount
                    || current.uidNext != previous.uidNext)) {
                host.sendUntagged(buildStatusLine(name, current,
                        true, host.isCondstoreEnabled()));
            } else if (wantsFlagChangeFor(name) && host.isCondstoreEnabled()
                    && current.highestModseq != previous.highestModseq) {
                host.sendUntagged(buildStatusLine(name, current,
                        false, true));
            }
            watchStates.put(key, current);
        }
    }

    private boolean wantsSelectedMessageEvents() {
        for (ImapNotifyEventGroup group : subscription.getEventGroups()) {
            if (!group.getMailboxFilter().affectsSelectedMailbox()
                    || group.isEventsNone()) {
                continue;
            }
            for (ImapNotifyEventSpec spec : group.getEvents()) {
                ImapNotifyEventType type = spec.getType();
                if (type == ImapNotifyEventType.MESSAGE_NEW
                        || type == ImapNotifyEventType.MESSAGE_EXPUNGE
                        || type == ImapNotifyEventType.FLAG_CHANGE) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean wantsMessageEventsFor(String mailboxName) {
        return !interestedEvents(mailboxName, true).isEmpty();
    }

    private boolean wantsFlagChangeFor(String mailboxName) {
        for (ImapNotifyEventType type : interestedEvents(mailboxName, false)) {
            if (type == ImapNotifyEventType.FLAG_CHANGE) {
                return true;
            }
        }
        return false;
    }

    private Set<ImapNotifyEventType> interestedEvents(String mailboxName,
            boolean messageEventsOnly) {
        Set<ImapNotifyEventType> result =
                new LinkedHashSet<ImapNotifyEventType>();
        String selected = host.getSelectedMailboxName();
        boolean isSelected = selected != null
                && mailboxName.equalsIgnoreCase(selected);
        for (ImapNotifyEventGroup group : subscription.getEventGroups()) {
            if (group.isEventsNone()) {
                continue;
            }
            if (!mailboxMatchesGroup(mailboxName, group, isSelected)) {
                continue;
            }
            for (ImapNotifyEventSpec spec : group.getEvents()) {
                ImapNotifyEventType type = spec.getType();
                if (messageEventsOnly && !type.isMessageEvent()) {
                    continue;
                }
                result.add(type);
            }
        }
        return result;
    }

    private boolean mailboxMatchesGroup(String mailboxName,
            ImapNotifyEventGroup group, boolean isSelected) {
        ImapNotifyMailboxFilter filter = group.getMailboxFilter();
        if (filter.affectsSelectedMailbox()) {
            return isSelected;
        }
        if (isSelected) {
            return false;
        }
        try {
            return resolveWatchedMailboxes(
                    ImapNotifyRequest.set(false,
                            Collections.singletonList(group)))
                    .contains(mailboxName);
        } catch (IOException e) {
            return false;
        }
    }

    private Set<String> resolveWatchedMailboxes(ImapNotifyRequest request)
            throws IOException {
        MailboxStore store = host.getStore();
        if (store == null) {
            return Collections.emptySet();
        }
        Set<String> result = new LinkedHashSet<String>();
        String selected = host.getSelectedMailboxName();
        for (ImapNotifyEventGroup group : request.getEventGroups()) {
            if (group.isEventsNone()) {
                continue;
            }
            ImapNotifyMailboxFilter filter = group.getMailboxFilter();
            if (filter.affectsSelectedMailbox()) {
                if (selected != null) {
                    result.add(selected);
                }
                continue;
            }
            result.addAll(resolveFilter(store, filter));
        }
        return result;
    }

    private static Set<String> resolveFilter(MailboxStore store,
            ImapNotifyMailboxFilter filter) throws IOException {
        Set<String> names = new LinkedHashSet<String>();
        switch (filter.getKind()) {
            case PERSONAL:
                names.addAll(store.listMailboxes("", "*"));
                break;
            case INBOXES:
                names.addAll(store.listMailboxes("", "*"));
                break;
            case SUBSCRIBED:
                names.addAll(store.listSubscribed("", "*"));
                break;
            case SUBTREE:
                for (String root : filter.getMailboxNames()) {
                    names.add(root);
                    String pattern = root + store.getHierarchyDelimiter() + "%";
                    names.addAll(store.listMailboxes("", pattern));
                }
                break;
            case MAILBOXES:
                for (String name : filter.getMailboxNames()) {
                    try {
                        store.openMailbox(name, true).close(true);
                        names.add(name);
                    } catch (IOException e) {
                        // RFC 5465: ignore non-existent mailboxes.
                    }
                }
                break;
            default:
                break;
        }
        return names;
    }

    private static String normalizeMailboxName(String name) {
        if ("INBOX".equalsIgnoreCase(name)) {
            return "INBOX";
        }
        return name;
    }

    private static MailboxWatchState snapshotMailbox(MailboxStore store,
            String name) {
        try {
            Mailbox mailbox = store.openMailbox(name, true);
            try {
                MailboxWatchState state = new MailboxWatchState();
                state.messageCount = mailbox.getMessageCount();
                state.uidNext = mailbox.getUidNext();
                state.uidValidity = mailbox.getUidValidity();
                state.highestModseq = mailbox.getHighestModSeq();
                return state;
            } finally {
                mailbox.close(true);
            }
        } catch (IOException e) {
            return null;
        }
    }

    private String buildStatusLine(String mailboxName, MailboxWatchState state,
            boolean messageItems, boolean includeModseq) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("STATUS ");
        sb.append(host.quoteMailboxName(mailboxName));
        sb.append(" (");
        boolean first = true;
        if (messageItems) {
            first = appendStatusItem(sb, first, "MESSAGES", state.messageCount);
            first = appendStatusItem(sb, first, "UIDNEXT", state.uidNext);
            first = appendStatusItem(sb, first, "UIDVALIDITY",
                    state.uidValidity);
        }
        if (includeModseq && host.isCondstoreEnabled()) {
            first = appendStatusItem(sb, first, "HIGHESTMODSEQ",
                    state.highestModseq);
        }
        sb.append(')');
        return sb.toString();
    }

    private static boolean appendStatusItem(StringBuilder sb, boolean first,
            String name, long value) {
        if (!first) {
            sb.append(' ');
        }
        sb.append(name).append(' ').append(value);
        return false;
    }

    private static final class MailboxWatchState {
        int messageCount;
        long uidNext;
        long uidValidity;
        long highestModseq;
    }
}
