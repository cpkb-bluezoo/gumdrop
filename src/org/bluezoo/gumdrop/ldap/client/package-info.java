/*
 * package-info.java
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

/**
 * Asynchronous LDAP client for Gumdrop's non-blocking I/O framework.
 *
 * <p>This package provides a fully asynchronous LDAP client that integrates
 * with Gumdrop's event-driven architecture. All operations use callbacks
 * rather than blocking, making it suitable for high-concurrency scenarios.
 *
 * <h2>Stateful Handler Pattern</h2>
 *
 * <p>The LDAP client uses a stateful handler pattern where interfaces
 * guide the handler through valid operation sequences:
 *
 * <ul>
 *   <li>{@link org.bluezoo.gumdrop.ldap.client.LDAPConnectionReady} - Entry point,
 *       receives connection ready notification</li>
 *   <li>{@link org.bluezoo.gumdrop.ldap.client.LDAPConnected} - Initial state,
 *       allows bind and STARTTLS</li>
 *   <li>{@link org.bluezoo.gumdrop.ldap.client.LDAPPostTLS} - After STARTTLS,
 *       must bind before operations</li>
 *   <li>{@link org.bluezoo.gumdrop.ldap.client.LDAPSession} - After bind,
 *       full directory operations available</li>
 * </ul>
 *
 * <p>This pattern provides compile-time enforcement of valid operation sequences,
 * preventing common mistakes like searching before binding.
 *
 * <h2>Result Handler Interfaces</h2>
 *
 * <p>Each operation has a corresponding result handler interface:
 *
 * <ul>
 *   <li>{@link org.bluezoo.gumdrop.ldap.client.BindResultHandler} - Bind results</li>
 *   <li>{@link org.bluezoo.gumdrop.ldap.client.StartTLSResultHandler} - STARTTLS results</li>
 *   <li>{@link org.bluezoo.gumdrop.ldap.client.SearchResultHandler} - Search entries and completion</li>
 *   <li>{@link org.bluezoo.gumdrop.ldap.client.ModifyResultHandler} - Modify results</li>
 *   <li>{@link org.bluezoo.gumdrop.ldap.client.AddResultHandler} - Add results</li>
 *   <li>{@link org.bluezoo.gumdrop.ldap.client.DeleteResultHandler} - Delete results</li>
 *   <li>{@link org.bluezoo.gumdrop.ldap.client.CompareResultHandler} - Compare results</li>
 *   <li>{@link org.bluezoo.gumdrop.ldap.client.ModifyDNResultHandler} - Rename/move results</li>
 *   <li>{@link org.bluezoo.gumdrop.ldap.client.ExtendedResultHandler} - Extended operation results</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 *
 * <pre>{@code
 * LDAPClient client = new LDAPClient(selectorLoop, "ldap.example.com", 389);
 *
 * client.connect(new LDAPConnectionReady() {
 *     public void handleReady(LDAPConnected connection) {
 *         connection.bind("cn=admin,dc=example,dc=com", "secret",
 *             new BindResultHandler() {
 *                 public void handleBindSuccess(LDAPSession session) {
 *                     // Now we can search
 *                     SearchRequest search = new SearchRequest();
 *                     search.setBaseDN("dc=example,dc=com");
 *                     search.setScope(SearchScope.SUBTREE);
 *                     search.setFilter("(uid=jdoe)");
 *                     
 *                     session.search(search, new SearchResultHandler() {
 *                         public void handleEntry(SearchResultEntry entry) {
 *                             System.out.println("Found: " + entry.getDN());
 *                         }
 *                         public void handleReference(String[] urls) {
 *                             // Handle referrals if needed
 *                         }
 *                         public void handleDone(LDAPResult result, LDAPSession session) {
 *                             session.unbind();
 *                         }
 *                     });
 *                 }
 *                 
 *                 public void handleBindFailure(LDAPResult result, LDAPConnected conn) {
 *                     System.err.println("Bind failed: " + result.getDiagnosticMessage());
 *                     conn.unbind();
 *                 }
 *             });
 *     }
 *     
 *     public void onConnected() { }
 *     public void onDisconnected() { }
 *     public void onTLSStarted() { }
 *     public void onError(Exception e) { e.printStackTrace(); }
 * });
 * }</pre>
 *
 * <h2>STARTTLS Example</h2>
 *
 * <pre>{@code
 * LDAPClient client = new LDAPClient(selectorLoop, "ldap.example.com", 389);
 * client.setSSLContext(sslContext);  // Configure TLS (not setSecure)
 *
 * client.connect(new LDAPConnectionReady() {
 *     public void handleReady(LDAPConnected connection) {
 *         // Upgrade to TLS before binding
 *         connection.startTLS(new StartTLSResultHandler() {
 *             public void handleTLSEstablished(LDAPPostTLS postTLS) {
 *                 // Now secure, proceed with bind
 *                 postTLS.bind("cn=admin,dc=example,dc=com", "secret",
 *                     new MyBindHandler());
 *             }
 *             
 *             public void handleStartTLSFailure(LDAPResult result, LDAPConnected conn) {
 *                 // TLS failed - decide whether to continue insecure
 *                 conn.unbind();
 *             }
 *         });
 *     }
 *     // ... other callbacks
 * });
 * }</pre>
 *
 * <h2>LDAPS (Implicit TLS)</h2>
 *
 * <pre>{@code
 * LDAPClient client = new LDAPClient(selectorLoop, "ldap.example.com", 636);
 * client.setSecure(true);
 * client.setKeystoreFile("/path/to/truststore.p12");
 * client.connect(handler);  // Already secure, bind directly
 * }</pre>
 *
 * <h2>Search Filters</h2>
 *
 * <p>Standard LDAP filter syntax (RFC 4515):</p>
 * <ul>
 *   <li>{@code (uid=jdoe)} - Equality</li>
 *   <li>{@code (cn=John*)} - Substring</li>
 *   <li>{@code (age>=21)} - Greater or equal</li>
 *   <li>{@code (&(objectClass=person)(uid=jdoe))} - AND</li>
 *   <li>{@code (|(uid=jdoe)(uid=jsmith))} - OR</li>
 *   <li>{@code (!(objectClass=computer))} - NOT</li>
 * </ul>
 *
 * <h2>Modification Operations</h2>
 *
 * <pre>{@code
 * List<Modification> mods = new ArrayList<>();
 * mods.add(Modification.replace("mail", "newemail@example.com"));
 * mods.add(Modification.add("description", "Updated user"));
 * mods.add(Modification.delete("oldAttr"));
 *
 * session.modify("cn=user,dc=example,dc=com", mods, new ModifyResultHandler() {
 *     public void handleModifyResult(LDAPResult result, LDAPSession session) {
 *         if (result.isSuccess()) {
 *             // Modification applied
 *         }
 *     }
 * });
 * }</pre>
 *
 * <h2>Result Codes</h2>
 *
 * <p>Common LDAP result codes (see {@link org.bluezoo.gumdrop.ldap.client.LDAPResultCode}):</p>
 * <ul>
 *   <li>{@code SUCCESS} (0) - Operation completed successfully</li>
 *   <li>{@code NO_SUCH_OBJECT} (32) - Entry does not exist</li>
 *   <li>{@code INVALID_CREDENTIALS} (49) - Wrong password</li>
 *   <li>{@code INSUFFICIENT_ACCESS_RIGHTS} (50) - Not authorized</li>
 *   <li>{@code COMPARE_TRUE} (6) - Compare matched</li>
 *   <li>{@code COMPARE_FALSE} (5) - Compare did not match</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 *
 * <p>The LDAP client is designed for single-threaded use within a
 * {@link org.bluezoo.gumdrop.SelectorLoop}. All callbacks are invoked
 * on the selector thread.</p>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see org.bluezoo.gumdrop.ldap.asn1
 * @see org.bluezoo.gumdrop.auth.Realm
 * @see <a href="https://www.rfc-editor.org/rfc/rfc4511">RFC 4511 - LDAP Protocol</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc4513">RFC 4513 - LDAP Authentication</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc4515">RFC 4515 - LDAP Search Filters</a>
 */
package org.bluezoo.gumdrop.ldap.client;


