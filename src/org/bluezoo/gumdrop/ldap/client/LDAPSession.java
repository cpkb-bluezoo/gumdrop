/*
 * LDAPSession.java
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

package org.bluezoo.gumdrop.ldap.client;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.bluezoo.gumdrop.auth.SASLClientMechanism;

/**
 * Operations available in an authenticated LDAP session (RFC 4511).
 * 
 * <p>This interface is provided to the handler in
 * {@link BindResultHandler#handleBindSuccess} after a successful bind.
 * It provides access to all LDAP directory operations:
 * <ul>
 *   <li>Search — RFC 4511 section 4.5</li>
 *   <li>Modify — RFC 4511 section 4.6</li>
 *   <li>Add — RFC 4511 section 4.7</li>
 *   <li>Delete — RFC 4511 section 4.8</li>
 *   <li>ModifyDN — RFC 4511 section 4.9</li>
 *   <li>Compare — RFC 4511 section 4.10</li>
 *   <li>Extended — RFC 4511 section 4.12</li>
 * </ul>
 * 
 * <p>LDAP supports concurrent operations — multiple requests can be
 * outstanding at the same time, each identified by a message ID
 * (RFC 4511 section 4.1.1). The connection handles message ID
 * correlation automatically.
 * 
 * <p>After each operation completes, the callback receives this
 * interface again, allowing the handler to continue with more
 * operations or close the connection.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see BindResultHandler#handleBindSuccess
 * @see <a href="https://www.rfc-editor.org/rfc/rfc4511#section-4.5">RFC 4511 §4.5–4.12</a>
 */
public interface LDAPSession {

    /**
     * Performs a search operation.
     * 
     * <p>Search results are delivered incrementally:
     * <ul>
     * <li>{@link SearchResultHandler#handleEntry} for each matching entry</li>
     * <li>{@link SearchResultHandler#handleReference} for referrals</li>
     * <li>{@link SearchResultHandler#handleDone} when search completes</li>
     * </ul>
     * 
     * @param request the search request parameters
     * @param callback receives search results
     */
    void search(SearchRequest request, SearchResultHandler callback);

    /**
     * Modifies an existing entry.
     * 
     * <p>The modifications map contains attribute names as keys and
     * modification operations as values. Each modification specifies
     * an operation type (add, delete, replace) and values.
     * 
     * @param dn the distinguished name of the entry to modify
     * @param modifications the modifications to apply
     * @param callback receives the modify result
     */
    void modify(String dn, List<Modification> modifications, ModifyResultHandler callback);

    /**
     * Adds a new entry to the directory.
     * 
     * @param dn the distinguished name for the new entry
     * @param attributes the attributes for the new entry
     * @param callback receives the add result
     */
    void add(String dn, Map<String, List<byte[]>> attributes, AddResultHandler callback);

    /**
     * Deletes an entry from the directory.
     * 
     * @param dn the distinguished name of the entry to delete
     * @param callback receives the delete result
     */
    void delete(String dn, DeleteResultHandler callback);

    /**
     * Compares an attribute value in an entry.
     * 
     * <p>The result indicates whether the specified attribute in the
     * entry contains the given value:
     * <ul>
     * <li>{@link CompareResultHandler#handleCompareTrue} if it matches</li>
     * <li>{@link CompareResultHandler#handleCompareFalse} if it doesn't</li>
     * </ul>
     * 
     * @param dn the distinguished name of the entry
     * @param attribute the attribute name to compare
     * @param value the value to compare against
     * @param callback receives the compare result
     */
    void compare(String dn, String attribute, byte[] value, CompareResultHandler callback);

    /**
     * Renames or moves an entry.
     * 
     * @param dn the current distinguished name of the entry
     * @param newRDN the new relative distinguished name
     * @param deleteOldRDN whether to delete the old RDN attribute value
     * @param callback receives the modify DN result
     */
    void modifyDN(String dn, String newRDN, boolean deleteOldRDN, ModifyDNResultHandler callback);

    /**
     * Renames or moves an entry to a new parent.
     * 
     * @param dn the current distinguished name of the entry
     * @param newRDN the new relative distinguished name
     * @param deleteOldRDN whether to delete the old RDN attribute value
     * @param newSuperior the new parent DN (null to keep same parent)
     * @param callback receives the modify DN result
     */
    void modifyDN(String dn, String newRDN, boolean deleteOldRDN, String newSuperior,
                  ModifyDNResultHandler callback);

    /**
     * Performs an extended operation.
     * 
     * <p>Extended operations allow protocol extensions beyond the core
     * LDAP operations. Common examples include password modify and
     * whoami operations.
     * 
     * @param oid the OID identifying the extended operation
     * @param value the request value (may be null)
     * @param callback receives the extended operation result
     */
    void extended(String oid, byte[] value, ExtendedResultHandler callback);

    /**
     * Re-binds with different credentials.
     * 
     * <p>This allows switching to a different user identity within
     * the same connection. On success, a new {@link LDAPSession} is
     * provided with the new identity.
     * 
     * @param dn the distinguished name to bind as
     * @param password the password for authentication
     * @param callback receives the bind result
     */
    void rebind(String dn, String password, BindResultHandler callback);

    /**
     * Re-binds with a SASL mechanism (RFC 4513 section 5.2).
     *
     * @param saslClient the pre-created SASL client mechanism
     * @param callback receives the bind result
     */
    void rebindSASL(SASLClientMechanism saslClient, BindResultHandler callback);

    /**
     * Abandons an in-progress operation (RFC 4511 section 4.11).
     *
     * <p>Sends an AbandonRequest for the given message ID. The server
     * SHOULD abandon the operation but is not required to send a
     * response. Any pending callback for the abandoned operation is
     * removed.
     *
     * @param messageId the message ID of the operation to abandon
     */
    void abandon(int messageId);

    /**
     * Sets controls to be sent with the next request (RFC 4511 section 4.1.11).
     *
     * <p>The controls are consumed when the next operation is sent.
     * Call this method before each operation that needs controls.
     *
     * @param controls the controls to attach (null clears)
     */
    void setRequestControls(List<Control> controls);

    /**
     * Returns controls received with the last response (RFC 4511 section 4.1.11).
     *
     * @return unmodifiable list of response controls, empty if none
     */
    default List<Control> getResponseControls() {
        return Collections.emptyList();
    }

    /**
     * Closes the connection.
     * 
     * <p>Sends an unbind request and closes the connection. After
     * calling this method, no further operations should be issued.
     */
    void unbind();

}

