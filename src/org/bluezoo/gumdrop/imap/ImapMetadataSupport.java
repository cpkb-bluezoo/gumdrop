/*
 * ImapMetadataSupport.java
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
import org.bluezoo.gumdrop.mailbox.MailboxStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.concurrent.Callable;

/**
 * RFC 5464 GETMETADATA / SETMETADATA for one IMAP session.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class ImapMetadataSupport {

    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.imap.L10N");

    public interface Host {
        ImapListener getServer();

        MailboxStore getStore();

        ImapMetadataFileStore getMetadataStore();

        void sendUntagged(String line) throws IOException;

        void sendTaggedOk(String tag, String message) throws IOException;

        void sendTaggedNo(String tag, String message) throws IOException;

        void sendTaggedBad(String tag, String message) throws IOException;

        String quoteMailboxName(String mailboxName);

        String quoteMetadataValue(String value);

        boolean mailboxExists(String mailboxName) throws IOException;

        <T> void submitStorage(Callable<T> work,
                StorageExecutor.Callback<T> callback);
    }

    private static final class GetOutcome {
        final String untaggedLine;
        final String taggedOk;
        final String taggedNo;

        GetOutcome(String untaggedLine, String taggedOk, String taggedNo) {
            this.untaggedLine = untaggedLine;
            this.taggedOk = taggedOk;
            this.taggedNo = taggedNo;
        }
    }

    private static final class SetOutcome {
        final String taggedOk;
        final String taggedNo;

        SetOutcome(String taggedOk, String taggedNo) {
            this.taggedOk = taggedOk;
            this.taggedNo = taggedNo;
        }
    }

    private final Host host;

    public ImapMetadataSupport(Host host) {
        this.host = host;
    }

    public void handleGetMetadata(String tag, String args) throws IOException {
        if (!host.getServer().isEnableMETADATA()) {
            host.sendTaggedBad(tag,
                    L10N.getString("imap.err.unknown_command"));
            return;
        }
        ImapMetadataGetRequest request;
        try {
            request = new ImapMetadataParser(args).parseGet();
        } catch (ParseException e) {
            host.sendTaggedBad(tag,
                    L10N.getString("imap.err.metadata_syntax"));
            return;
        }
        for (String entry : request.getEntryNames()) {
            if (!ImapMetadataEntryNames.isValidEntryName(entry)) {
                host.sendTaggedBad(tag,
                        L10N.getString("imap.err.metadata_invalid_entry"));
                return;
            }
        }
        if (!request.isServerMetadata() && !host.mailboxExists(
                request.getMailboxName())) {
            host.sendTaggedNo(tag,
                    L10N.getString("imap.err.mailbox_not_found"));
            return;
        }
        final ImapMetadataGetRequest req = request;
        final String cmdTag = tag;
        host.submitStorage(new Callable<GetOutcome>() {
            @Override
            public GetOutcome call() throws Exception {
                return buildGetOutcome(req);
            }
        }, new StorageExecutor.Callback<GetOutcome>() {
            @Override
            public void completed(GetOutcome outcome) {
                try {
                    if (outcome.untaggedLine != null) {
                        host.sendUntagged(outcome.untaggedLine);
                    }
                    if (outcome.taggedOk != null) {
                        host.sendTaggedOk(cmdTag, outcome.taggedOk);
                    } else if (outcome.taggedNo != null) {
                        host.sendTaggedNo(cmdTag, outcome.taggedNo);
                    }
                } catch (IOException e) {
                    // connection closing
                }
            }

            @Override
            public void failed(Throwable error) {
                try {
                    host.sendTaggedNo(cmdTag,
                            L10N.getString("imap.err.metadata_failed"));
                } catch (IOException e) {
                    // connection closing
                }
            }
        });
    }

    private GetOutcome buildGetOutcome(ImapMetadataGetRequest request)
            throws IOException {
        ImapMetadataFileStore store = host.getMetadataStore();
        if (store == null) {
            return new GetOutcome(null, null,
                    L10N.getString("imap.err.metadata_not_configured"));
        }
        Map<String, String> values = new LinkedHashMap<String, String>();
        int largestOmitted = -1;
        for (String entryName : request.getEntryNames()) {
            Map<String, String> chunk;
            if (request.getDepth() != 0) {
                chunk = store.listWithDepth(request.getMailboxName(),
                        entryName, request.getDepth());
            } else {
                chunk = store.getEntries(request.getMailboxName(),
                        singleList(entryName));
            }
            for (Map.Entry<String, String> e : chunk.entrySet()) {
                String val = e.getValue();
                if (val == null) {
                    continue;
                }
                if (ImapMetadataEntryNames.isReadOnly(e.getKey())) {
                    val = defaultReadOnlyValue(e.getKey());
                }
                int bytes = val.getBytes(StandardCharsets.UTF_8).length;
                if (request.getMaxSize() >= 0 && bytes > request.getMaxSize()) {
                    if (bytes > largestOmitted) {
                        largestOmitted = bytes;
                    }
                    continue;
                }
                values.put(e.getKey(), val);
            }
        }
        String line = formatMetadataResponse(request.getMailboxName(), values);
        if (largestOmitted >= 0) {
            return new GetOutcome(line,
                    "[METADATA LONGENTRIES " + largestOmitted + "] "
                            + L10N.getString("imap.metadata_get_complete"),
                    null);
        }
        return new GetOutcome(line,
                L10N.getString("imap.metadata_get_complete"), null);
    }

    public void handleSetMetadata(String tag, String args) throws IOException {
        if (!host.getServer().isEnableMETADATA()) {
            host.sendTaggedBad(tag,
                    L10N.getString("imap.err.unknown_command"));
            return;
        }
        ImapMetadataSetRequest request;
        try {
            request = new ImapMetadataParser(args).parseSet();
        } catch (ParseException e) {
            host.sendTaggedBad(tag,
                    L10N.getString("imap.err.metadata_syntax"));
            return;
        }
        for (ImapMetadataSetRequest.EntryValue ev : request.getEntries()) {
            if (!ImapMetadataEntryNames.isValidEntryName(ev.entryName)) {
                host.sendTaggedBad(tag,
                        L10N.getString("imap.err.metadata_invalid_entry"));
                return;
            }
            if (ImapMetadataEntryNames.isReadOnly(ev.entryName)) {
                host.sendTaggedNo(tag,
                        L10N.getString("imap.err.metadata_readonly"));
                return;
            }
        }
        if (!request.getMailboxName().isEmpty()
                && !host.mailboxExists(request.getMailboxName())) {
            host.sendTaggedNo(tag,
                    L10N.getString("imap.err.mailbox_not_found"));
            return;
        }
        final ImapMetadataSetRequest req = request;
        final String cmdTag = tag;
        host.submitStorage(new Callable<SetOutcome>() {
            @Override
            public SetOutcome call() throws Exception {
                return buildSetOutcome(req);
            }
        }, new StorageExecutor.Callback<SetOutcome>() {
            @Override
            public void completed(SetOutcome outcome) {
                try {
                    if (outcome.taggedOk != null) {
                        host.sendTaggedOk(cmdTag, outcome.taggedOk);
                    } else if (outcome.taggedNo != null) {
                        host.sendTaggedNo(cmdTag, outcome.taggedNo);
                    }
                } catch (IOException e) {
                    // connection closing
                }
            }

            @Override
            public void failed(Throwable error) {
                try {
                    host.sendTaggedNo(cmdTag,
                            L10N.getString("imap.err.metadata_failed"));
                } catch (IOException e) {
                    // connection closing
                }
            }
        });
    }

    private SetOutcome buildSetOutcome(ImapMetadataSetRequest request)
            throws IOException {
        ImapMetadataFileStore store = host.getMetadataStore();
        if (store == null) {
            return new SetOutcome(null,
                    L10N.getString("imap.err.metadata_not_configured"));
        }
        try {
            for (ImapMetadataSetRequest.EntryValue ev : request.getEntries()) {
                store.set(request.getMailboxName(), ev.entryName, ev.value);
            }
        } catch (IOException e) {
            String msg = e.getMessage();
            if ("too many".equals(msg)) {
                return new SetOutcome(null,
                        "[METADATA TOOMANY] "
                                + L10N.getString("imap.err.metadata_failed"));
            }
            if ("too large".equals(msg)) {
                return new SetOutcome(null,
                        "[METADATA MAXSIZE "
                                + ImapMetadataFileStore.MAX_VALUE_BYTES + "] "
                                + L10N.getString("imap.err.metadata_failed"));
            }
            return new SetOutcome(null,
                    L10N.getString("imap.err.metadata_failed"));
        }
        return new SetOutcome(
                L10N.getString("imap.metadata_set_complete"), null);
    }

    public void onMailboxDeleted(String mailboxName) {
        ImapMetadataFileStore store = host.getMetadataStore();
        if (store == null) {
            return;
        }
        try {
            store.deleteMailbox(mailboxName);
        } catch (IOException e) {
            // best effort
        }
    }

    public void onMailboxRenamed(String oldName, String newName) {
        ImapMetadataFileStore store = host.getMetadataStore();
        if (store == null) {
            return;
        }
        try {
            store.renameMailbox(oldName, newName);
        } catch (IOException e) {
            // best effort
        }
    }

    private String formatMetadataResponse(String mailboxName,
            Map<String, String> values) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("METADATA ");
        sb.append(host.quoteMailboxName(mailboxName));
        sb.append(" (");
        boolean first = true;
        for (Map.Entry<String, String> e : values.entrySet()) {
            if (!first) {
                sb.append(' ');
            }
            first = false;
            sb.append(e.getKey());
            sb.append(' ');
            sb.append(host.quoteMetadataValue(e.getValue()));
        }
        sb.append(')');
        return sb.toString();
    }

    private static String defaultReadOnlyValue(String entryName) {
        if (ImapMetadataEntryNames.SHARED_ADMIN.equalsIgnoreCase(entryName)) {
            return "mailto:postmaster@localhost";
        }
        return "";
    }

    private static List<String> singleList(String entry) {
        List<String> list = new ArrayList<String>(1);
        list.add(entry);
        return list;
    }
}
