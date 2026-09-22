/*
 * SyncReplDispatcher.java
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

package org.bluezoo.gumdrop.ldap.client;

import java.text.MessageFormat;
import java.util.List;
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.ldap.asn1.Asn1Exception;

/**
 * Adapts a {@link SyncReplHandler} to the raw {@link SearchResultHandler}/
 * {@link IntermediateResponseHandler} callbacks {@link LdapClientProtocolHandler}
 * actually dispatches to, extracting and parsing the RFC 4533 controls/
 * intermediate response along the way so a {@link SyncReplHandler}
 * implementation never has to touch BER or control OIDs itself.
 *
 * <p>Pass an instance of this class (not the {@link SyncReplHandler}
 * directly) as the search's callback:
 *
 * <pre>{@code
 * SyncRequestValue syncRequest = new SyncRequestValue(SyncRequestMode.REFRESH_AND_PERSIST, cookie);
 * session.setRequestControls(Collections.singletonList(syncRequest.toControl()));
 * session.search(request, new SyncReplDispatcher(syncHandler));
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc4533">RFC 4533 — LDAP Content Synchronization Operation</a>
 */
public final class SyncReplDispatcher implements SearchResultHandler, IntermediateResponseHandler {

    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.ldap.client.L10N");
    private static final Logger logger = Logger.getLogger(SyncReplDispatcher.class.getName());

    private final SyncReplHandler handler;

    /**
     * Creates a dispatcher wrapping the given handler.
     *
     * @param handler the handler to receive parsed sync events
     */
    public SyncReplDispatcher(SyncReplHandler handler) {
        if (handler == null) {
            throw new IllegalArgumentException("handler must not be null");
        }
        this.handler = handler;
    }

    @Override
    public void handleEntry(SearchResultEntry entry) {
        SyncStateValue syncState = null;
        if (entry.hasControls()) {
            for (Control control : entry.getControls()) {
                if (Control.OID_SYNC_STATE.equals(control.getOID())) {
                    try {
                        syncState = SyncStateValue.parse(control.getValue());
                    } catch (Asn1Exception e) {
                        logger.log(Level.WARNING, MessageFormat.format(
                                L10N.getString("warn.malformed_sync_state_value"), entry.getDN(), e), e);
                    }
                    break;
                }
            }
        }
        handler.syncEntry(entry, syncState);
    }

    @Override
    public void handleReference(String[] referralUrls) {
        handler.syncReference(referralUrls);
    }

    @Override
    public void handleDone(LdapResult result, LdapSession session) {
        SyncDoneValue syncDone = SyncDoneValue.EMPTY;
        if (result.hasControls()) {
            for (Control control : result.getControls()) {
                if (Control.OID_SYNC_DONE.equals(control.getOID())) {
                    try {
                        syncDone = SyncDoneValue.parse(control.getValue());
                    } catch (Asn1Exception e) {
                        logger.log(Level.WARNING, MessageFormat.format(
                                L10N.getString("warn.malformed_sync_done_value"), e), e);
                    }
                    break;
                }
            }
        }
        handler.syncDone(result, syncDone);
    }

    @Override
    public void handleIntermediateResponse(String responseName, byte[] responseValue) {
        if (!LdapConstants.OID_SYNC_INFO.equals(responseName)) {
            return;
        }
        SyncInfoValue info;
        try {
            info = SyncInfoValue.parse(responseValue);
        } catch (Asn1Exception e) {
            logger.log(Level.WARNING, MessageFormat.format(
                    L10N.getString("warn.malformed_sync_info_value"), e), e);
            return;
        }
        switch (info.getKind()) {
            case NEW_COOKIE:
                handler.syncNewCookie(info.getCookie());
                break;
            case REFRESH_DELETE:
                handler.syncRefreshDelete(info.getCookie(), info.isRefreshDone());
                break;
            case REFRESH_PRESENT:
                handler.syncRefreshPresent(info.getCookie(), info.isRefreshDone());
                break;
            case SYNC_ID_SET:
                List<byte[]> entryUUIDs = info.getEntryUUIDs();
                handler.syncIdSet(info.getCookie(), info.isRefreshDeletes(), entryUUIDs);
                break;
            default:
                logger.warning(MessageFormat.format(
                        L10N.getString("warn.unhandled_sync_info_kind"), info.getKind()));
        }
    }
}
