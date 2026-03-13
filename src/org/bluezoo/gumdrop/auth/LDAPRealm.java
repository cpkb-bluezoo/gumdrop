/*
 * LDAPRealm.java
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

package org.bluezoo.gumdrop.auth;


import java.io.IOException;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.text.MessageFormat;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.ResourceBundle;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.security.auth.x500.X500Principal;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.ldap.client.BindResultHandler;
import org.bluezoo.gumdrop.ldap.client.LDAPClient;
import org.bluezoo.gumdrop.ldap.client.LDAPConnected;
import org.bluezoo.gumdrop.ldap.client.LDAPConnectionReady;
import org.bluezoo.gumdrop.ldap.client.LDAPConstants;
import org.bluezoo.gumdrop.ldap.client.LDAPResult;
import org.bluezoo.gumdrop.ldap.client.LDAPResultCode;
import org.bluezoo.gumdrop.ldap.client.LDAPSession;
import org.bluezoo.gumdrop.ldap.client.SearchRequest;
import org.bluezoo.gumdrop.ldap.client.SearchResultEntry;
import org.bluezoo.gumdrop.ldap.client.SearchResultHandler;
import org.bluezoo.gumdrop.ldap.client.SearchScope;

/**
 * LDAP-backed Realm for authenticating users against a directory server.
 *
 * <p>This realm connects to an LDAP server (Active Directory, OpenLDAP, etc.)
 * to authenticate users and check role membership. It supports both simple
 * bind authentication and search-then-bind patterns.
 *
 * <h3>Configuration</h3>
 * <pre>{@code
 * <realm class="org.bluezoo.gumdrop.auth.LDAPRealm">
 *   <host>ldap.example.com</host>
 *   <port>389</port>
 *   <baseDN>dc=example,dc=com</baseDN>
 *   <bindDN>cn=service,dc=example,dc=com</bindDN>
 *   <bindPassword>secret</bindPassword>
 *   <userFilter>(uid={0})</userFilter>
 *   <roleAttribute>memberOf</roleAttribute>
 * </realm>
 * }</pre>
 *
 * <h3>Authentication Flow</h3>
 * <ol>
 *   <li>Bind to LDAP using service account (bindDN/bindPassword)</li>
 *   <li>Search for user using userFilter with username substituted</li>
 *   <li>Re-bind as the found user DN with their password</li>
 *   <li>If successful, user is authenticated</li>
 * </ol>
 *
 * <h3>SASL Bind (RFC 4513 §5.2)</h3>
 * <p>Optionally set {@code saslMechanism} to use SASL instead of simple bind.
 * Supported mechanisms: PLAIN, CRAM-MD5, DIGEST-MD5, EXTERNAL — implemented
 * via {@link SASLUtils#createClient} using gumdrop's own cryptographic
 * primitives (non-blocking, no JDK SASL dependency).
 *
 * <h3>TLS Support</h3>
 * <ul>
 *   <li>LDAPS (port 636): Set {@code secure="true"} and configure {@code sslContext}</li>
 *   <li>STARTTLS: Set {@code startTLS="true"} and configure {@code sslContext}</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc4511">RFC 4511: LDAPv3</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc4513">RFC 4513: LDAP Authentication Methods</a>
 */
public class LDAPRealm implements Realm {

    static final ResourceBundle L10N = ResourceBundle.getBundle("org.bluezoo.gumdrop.auth.L10N");
    private static final Logger LOGGER = Logger.getLogger(LDAPRealm.class.getName());

    /** Default timeout for LDAP operations in seconds. */
    private static final int DEFAULT_TIMEOUT = 30;

    // Configuration
    private String host = "localhost";
    private int port = LDAPConstants.DEFAULT_PORT;
    private boolean secure = false;
    private boolean startTLS = false;
    private Path keystoreFile;
    private String keystorePass;
    private String keystoreFormat = "PKCS12";
    private String baseDN = "";
    private String bindDN;
    private String bindPassword;
    private String userFilter = "(uid={0})";
    private String roleAttribute = "memberOf";
    private String rolePrefix = "";
    private int timeout = DEFAULT_TIMEOUT;
    private String saslMechanism;

    private String certLookupMode;
    private String certUsernameAttribute = "uid";
    private String certSubjectFilter;

    // Runtime state
    private SelectorLoop selectorLoop;

    /**
     * Supported SASL mechanisms.
     * LDAP realm only supports PLAIN and LOGIN since it needs the
     * plaintext password to perform LDAP bind.
     */
    private static final Set<SASLMechanism> SUPPORTED_MECHANISMS =
            Collections.unmodifiableSet(EnumSet.of(
                    SASLMechanism.PLAIN,
                    SASLMechanism.LOGIN,
                    SASLMechanism.EXTERNAL
            ));

    /**
     * Creates a new LDAPRealm with default settings.
     */
    public LDAPRealm() {
    }

    /**
     * Copy constructor for forSelectorLoop.
     */
    private LDAPRealm(LDAPRealm source, SelectorLoop loop) {
        this.host = source.host;
        this.port = source.port;
        this.secure = source.secure;
        this.startTLS = source.startTLS;
        this.keystoreFile = source.keystoreFile;
        this.keystorePass = source.keystorePass;
        this.keystoreFormat = source.keystoreFormat;
        this.baseDN = source.baseDN;
        this.bindDN = source.bindDN;
        this.bindPassword = source.bindPassword;
        this.userFilter = source.userFilter;
        this.roleAttribute = source.roleAttribute;
        this.rolePrefix = source.rolePrefix;
        this.timeout = source.timeout;
        this.saslMechanism = source.saslMechanism;
        this.certLookupMode = source.certLookupMode;
        this.certUsernameAttribute = source.certUsernameAttribute;
        this.certSubjectFilter = source.certSubjectFilter;
        this.selectorLoop = loop;
    }

    // Configuration setters

    public void setHost(String host) {
        this.host = host;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public void setSecure(boolean secure) {
        this.secure = secure;
    }

    public void setStartTLS(boolean startTLS) {
        this.startTLS = startTLS;
    }

    public void setKeystoreFile(Path keystoreFile) {
        this.keystoreFile = keystoreFile;
    }

    public void setKeystoreFile(String keystoreFile) {
        this.keystoreFile = Path.of(keystoreFile);
    }

    public void setKeystorePass(String keystorePass) {
        this.keystorePass = keystorePass;
    }

    public void setKeystoreFormat(String keystoreFormat) {
        this.keystoreFormat = keystoreFormat;
    }

    public void setBaseDN(String baseDN) {
        this.baseDN = baseDN;
    }

    public void setBindDN(String bindDN) {
        this.bindDN = bindDN;
    }

    public void setBindPassword(String bindPassword) {
        this.bindPassword = bindPassword;
    }

    /** @see <a href="https://www.rfc-editor.org/rfc/rfc4515">RFC 4515: LDAP Search Filters</a> */
    public void setUserFilter(String userFilter) {
        this.userFilter = userFilter;
    }

    public void setRoleAttribute(String roleAttribute) {
        this.roleAttribute = roleAttribute;
    }

    public void setRolePrefix(String rolePrefix) {
        this.rolePrefix = rolePrefix;
    }

    public void setTimeout(int timeout) {
        this.timeout = timeout;
    }

    /**
     * Sets the SASL mechanism to use for binding to the LDAP server.
     *
     * <p>When set, all LDAP binds (service account and user
     * authentication) use SASL instead of simple bind. Common
     * mechanisms include {@code DIGEST-MD5}, {@code CRAM-MD5},
     * {@code GSSAPI}, and {@code EXTERNAL}.
     *
     * @param mechanism the SASL mechanism name, or null for simple bind
     * @see <a href="https://www.rfc-editor.org/rfc/rfc4513#section-5.2">RFC 4513 §5.2</a>
     */
    public void setSaslMechanism(String mechanism) {
        this.saslMechanism = mechanism;
    }

    public void setSelectorLoop(SelectorLoop selectorLoop) {
        this.selectorLoop = selectorLoop;
    }

    /**
     * Sets the certificate lookup mode.
     *
     * @param mode "binary" to match by DER-encoded certificate,
     *             or "subject" to extract CN from the certificate's
     *             Subject DN
     */
    public void setCertLookupMode(String mode) {
        this.certLookupMode = mode;
    }

    /**
     * Sets the LDAP attribute to read as the username from a matched
     * certificate entry.
     *
     * @param attribute the LDAP attribute name (default: "uid")
     */
    public void setCertUsernameAttribute(String attribute) {
        this.certUsernameAttribute = attribute;
    }

    /**
     * Sets the LDAP filter template for subject-mode certificate
     * lookup. Placeholders like {CN}, {O}, {OU} are replaced with
     * the corresponding RDN values from the certificate's Subject DN.
     *
     * @param filter the filter template, e.g. "(uid={CN})"
     */
    public void setCertSubjectFilter(String filter) {
        this.certSubjectFilter = filter;
    }

    // Realm interface implementation

    @Override
    public Realm forSelectorLoop(SelectorLoop loop) {
        return new LDAPRealm(this, loop);
    }

    @Override
    public Set<SASLMechanism> getSupportedSASLMechanisms() {
        return SUPPORTED_MECHANISMS;
    }

    /** RFC 4513 §5.1/§5.2 — authenticate via simple or SASL bind. */
    @Override
    public boolean passwordMatch(String username, String password) {
        if (username == null || password == null) {
            return false;
        }

        try {
            // First, find the user's DN
            String userDN = findUserDN(username);
            if (userDN == null) {
                LOGGER.fine("User not found: " + username);
                return false;
            }

            // Now try to bind as the user
            return attemptBind(userDN, password);
        } catch (Exception e) {
            String msg = MessageFormat.format(L10N.getString("warn.ldap_auth_error"), username);
            LOGGER.log(Level.WARNING, msg, e);
            return false;
        }
    }

    @Override
    public String getDigestHA1(String username, String realmName) {
        // LDAP realm cannot provide H(A1) without plaintext password
        return null;
    }

    @Override
    @Deprecated
    public String getPassword(String username) {
        throw new UnsupportedOperationException("LDAP realm does not support password retrieval");
    }

    @Override
    public boolean isUserInRole(String username, String role) {
        if (username == null || role == null) {
            return false;
        }

        try {
            return checkUserRole(username, role);
        } catch (Exception e) {
            String msg = MessageFormat.format(L10N.getString("warn.ldap_role_error"), username);
            LOGGER.log(Level.WARNING, msg, e);
            return false;
        }
    }

    @Override
    public boolean userExists(String username) {
        if (username == null) {
            return false;
        }

        try {
            return findUserDN(username) != null;
        } catch (Exception e) {
            String msg = MessageFormat.format(L10N.getString("warn.ldap_user_error"), username);
            LOGGER.log(Level.WARNING, msg, e);
            return false;
        }
    }

    /** Certificate-to-user mapping via LDAP search. */
    @Override
    public CertificateAuthenticationResult authenticateCertificate(
            X509Certificate certificate) {
        if (certLookupMode == null) {
            return null;
        }

        try {
            String username;
            if ("binary".equals(certLookupMode)) {
                username = findUserByBinaryCert(certificate);
            } else if ("subject".equals(certLookupMode)) {
                username = findUserBySubjectDN(certificate);
            } else {
                LOGGER.warning("Unknown certLookupMode: "
                        + certLookupMode);
                return null;
            }

            if (username != null) {
                return CertificateAuthenticationResult.success(username);
            }
            return CertificateAuthenticationResult.failure();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING,
                    "Certificate authentication error", e);
            return CertificateAuthenticationResult.failure();
        }
    }

    // LDAP operations

    /**
     * Finds the DN for a username by searching LDAP.
     */
    private String findUserDN(String username) throws Exception {
        final String filter = userFilter.replace("{0}", escapeLDAPFilter(username));

        final AtomicReference<String> foundDN = new AtomicReference<>();
        final AtomicReference<Exception> error = new AtomicReference<>();
        final CountDownLatch latch = new CountDownLatch(1);

        LDAPClient client = createClient();
        client.connect(new LDAPConnectionReady() {
            @Override
            public void handleReady(LDAPConnected connection) {
                BindResultHandler bindHandler = new BindResultHandler() {
                    @Override
                    public void handleBindSuccess(LDAPSession session) {
                        // Service bind succeeded, now search for user
                        SearchRequest search = new SearchRequest();
                        search.setBaseDN(baseDN);
                        search.setScope(SearchScope.SUBTREE);
                        search.setFilter(filter);
                        search.setAttributes("dn");
                        search.setSizeLimit(1);
                        session.search(search, new SearchResultHandler() {
                            @Override
                            public void handleEntry(SearchResultEntry entry) {
                                foundDN.set(entry.getDN());
                            }

                            @Override
                            public void handleReference(String[] referralUrls) {
                                // Ignore referrals
                            }

                            @Override
                            public void handleDone(LDAPResult result, LDAPSession sess) {
                                sess.unbind();
                                latch.countDown();
                            }
                        });
                    }

                    @Override
                    public void handleBindFailure(LDAPResult result, LDAPConnected conn) {
                        String msg = MessageFormat.format(L10N.getString("err.ldap_bind"), result);
                        error.set(new Exception(msg));
                        conn.unbind();
                        latch.countDown();
                    }
                };
                
                performServiceBind(connection, bindHandler);
            }

            @Override
            public void onConnected(Endpoint endpoint) {
                // Connection established
            }

            @Override
            public void onError(Exception cause) {
                error.set(cause);
                latch.countDown();
            }

            @Override
            public void onDisconnected() {
                latch.countDown();
            }

            @Override
            public void onSecurityEstablished(SecurityInfo info) {
                // Security established
            }
        });

        if (!latch.await(timeout, TimeUnit.SECONDS)) {
            throw new Exception(L10N.getString("err.ldap_timeout"));
        }

        if (error.get() != null) {
            throw error.get();
        }

        return foundDN.get();
    }

    /**
     * Attempts to bind to LDAP with the given DN and password.
     */
    private boolean attemptBind(String dn, String password) throws Exception {
        final AtomicBoolean success = new AtomicBoolean(false);
        final AtomicReference<Exception> error = new AtomicReference<>();
        final CountDownLatch latch = new CountDownLatch(1);

        LDAPClient client = createClient();
        client.connect(new LDAPConnectionReady() {
            @Override
            public void handleReady(LDAPConnected connection) {
                performUserBind(connection, dn, password, new BindResultHandler() {
                    @Override
                    public void handleBindSuccess(LDAPSession session) {
                        success.set(true);
                        session.unbind();
                        latch.countDown();
                    }

                    @Override
                    public void handleBindFailure(LDAPResult result, LDAPConnected conn) {
                        success.set(false);
                        conn.unbind();
                        latch.countDown();
                    }
                });
            }

            @Override
            public void onConnected(Endpoint endpoint) {
                // Connection established
            }

            @Override
            public void onError(Exception cause) {
                error.set(cause);
                latch.countDown();
            }

            @Override
            public void onDisconnected() {
                latch.countDown();
            }

            @Override
            public void onSecurityEstablished(SecurityInfo info) {
                // Security established
            }
        });

        if (!latch.await(timeout, TimeUnit.SECONDS)) {
            throw new Exception(L10N.getString("err.ldap_timeout"));
        }

        if (error.get() != null) {
            throw error.get();
        }

        return success.get();
    }

    /**
     * Checks if a user has a specific role by querying their memberOf attribute.
     */
    private boolean checkUserRole(String username, String role) throws Exception {
        final String filter = userFilter.replace("{0}", escapeLDAPFilter(username));
        final String targetRole = rolePrefix + role;

        final AtomicBoolean hasRole = new AtomicBoolean(false);
        final AtomicReference<Exception> error = new AtomicReference<>();
        final CountDownLatch latch = new CountDownLatch(1);

        LDAPClient client = createClient();
        client.connect(new LDAPConnectionReady() {
            @Override
            public void handleReady(LDAPConnected connection) {
                BindResultHandler bindHandler = new BindResultHandler() {
                    @Override
                    public void handleBindSuccess(LDAPSession session) {
                        SearchRequest search = new SearchRequest();
                        search.setBaseDN(baseDN);
                        search.setScope(SearchScope.SUBTREE);
                        search.setFilter(filter);
                        search.setAttributes(roleAttribute);
                        search.setSizeLimit(1);
                        session.search(search, new SearchResultHandler() {
                            @Override
                            public void handleEntry(SearchResultEntry entry) {
                                // Check if user has the role
                                for (String value : entry.getAttributeStringValues(roleAttribute)) {
                                    // memberOf typically contains full DNs like "cn=admins,ou=groups,dc=example,dc=com"
                                    // We check if the role name appears in the value
                                    if (value.toLowerCase().contains(targetRole.toLowerCase())) {
                                        hasRole.set(true);
                                        break;
                                    }
                                }
                            }

                            @Override
                            public void handleReference(String[] referralUrls) {
                                // Ignore referrals
                            }

                            @Override
                            public void handleDone(LDAPResult result, LDAPSession sess) {
                                sess.unbind();
                                latch.countDown();
                            }
                        });
                    }

                    @Override
                    public void handleBindFailure(LDAPResult result, LDAPConnected conn) {
                        String msg = MessageFormat.format(L10N.getString("err.ldap_bind"), result);
                        error.set(new Exception(msg));
                        conn.unbind();
                        latch.countDown();
                    }
                };

                performServiceBind(connection, bindHandler);
            }

            @Override
            public void onConnected(Endpoint endpoint) {
                // Connection established
            }

            @Override
            public void onError(Exception cause) {
                error.set(cause);
                latch.countDown();
            }

            @Override
            public void onDisconnected() {
                latch.countDown();
            }

            @Override
            public void onSecurityEstablished(SecurityInfo info) {
                // Security established
            }
        });

        if (!latch.await(timeout, TimeUnit.SECONDS)) {
            throw new Exception(L10N.getString("err.ldap_timeout"));
        }

        if (error.get() != null) {
            throw error.get();
        }

        return hasRole.get();
    }

    /**
     * Finds a username by searching LDAP for an entry whose
     * {@code userCertificate;binary} attribute matches the
     * DER encoding of the presented certificate.
     */
    private String findUserByBinaryCert(X509Certificate certificate)
            throws Exception {
        byte[] der = certificate.getEncoded();
        String derHex = toLDAPBinaryEscape(der);
        String filter = "(&(userCertificate;binary=" + derHex
                + ")(objectClass=person))";

        return searchForUsername(filter);
    }

    /**
     * Finds a username by extracting RDN components from the
     * certificate's Subject DN and searching LDAP with a configured
     * filter template.
     */
    private String findUserBySubjectDN(X509Certificate certificate)
            throws Exception {
        if (certSubjectFilter == null) {
            LOGGER.warning("certSubjectFilter not configured for "
                    + "subject lookup mode");
            return null;
        }

        String dn = certificate.getSubjectX500Principal()
                .getName(X500Principal.RFC2253);
        String filter = certSubjectFilter;
        // Replace {CN}, {O}, {OU} etc. with RDN values
        filter = replaceRDNPlaceholders(filter, dn);

        return searchForUsername(filter);
    }

    /**
     * Searches LDAP with the given filter and returns the value of
     * the certUsernameAttribute from the first matched entry.
     */
    private String searchForUsername(String filter) throws Exception {
        final AtomicReference<String> foundUsername =
                new AtomicReference<>();
        final AtomicReference<Exception> error = new AtomicReference<>();
        final CountDownLatch latch = new CountDownLatch(1);

        LDAPClient client = createClient();
        client.connect(new LDAPConnectionReady() {
            @Override
            public void handleReady(LDAPConnected connection) {
                BindResultHandler bindHandler = new BindResultHandler() {
                    @Override
                    public void handleBindSuccess(LDAPSession session) {
                        SearchRequest search = new SearchRequest();
                        search.setBaseDN(baseDN);
                        search.setScope(SearchScope.SUBTREE);
                        search.setFilter(filter);
                        search.setAttributes(certUsernameAttribute);
                        search.setSizeLimit(1);
                        session.search(search,
                                new SearchResultHandler() {
                            @Override
                            public void handleEntry(
                                    SearchResultEntry entry) {
                                List<String> vals =
                                        entry.getAttributeStringValues(
                                                certUsernameAttribute);
                                if (vals != null && !vals.isEmpty()) {
                                    foundUsername.set(vals.get(0));
                                }
                            }

                            @Override
                            public void handleReference(
                                    String[] referralUrls) {
                            }

                            @Override
                            public void handleDone(LDAPResult result,
                                                   LDAPSession sess) {
                                sess.unbind();
                                latch.countDown();
                            }
                        });
                    }

                    @Override
                    public void handleBindFailure(LDAPResult result,
                                                  LDAPConnected conn) {
                        String msg = MessageFormat.format(
                                L10N.getString("err.ldap_bind"), result);
                        error.set(new Exception(msg));
                        conn.unbind();
                        latch.countDown();
                    }
                };

                performServiceBind(connection, bindHandler);
            }

            @Override
            public void onConnected(Endpoint endpoint) {
            }

            @Override
            public void onError(Exception cause) {
                error.set(cause);
                latch.countDown();
            }

            @Override
            public void onDisconnected() {
                latch.countDown();
            }

            @Override
            public void onSecurityEstablished(SecurityInfo info) {
            }
        });

        if (!latch.await(timeout, TimeUnit.SECONDS)) {
            throw new Exception(L10N.getString("err.ldap_timeout"));
        }

        if (error.get() != null) {
            throw error.get();
        }

        return foundUsername.get();
    }

    /**
     * Converts DER-encoded bytes to LDAP binary escape format
     * for use in search filters (RFC 4515 section 3).
     */
    static String toLDAPBinaryEscape(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 3);
        for (byte b : data) {
            sb.append('\\');
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    /**
     * Replaces {CN}, {O}, {OU} etc. placeholders in a filter template
     * with the corresponding RDN values from an RFC 2253 DN string.
     */
    private static String replaceRDNPlaceholders(String template,
                                                  String dn) {
        String result = template;
        // Parse RDN components from the DN
        int start = 0;
        int len = dn.length();
        while (start < len) {
            int eq = dn.indexOf('=', start);
            if (eq < 0) {
                break;
            }
            String type = dn.substring(start, eq).trim().toUpperCase();
            int valStart = eq + 1;
            int comma = findUnescapedComma(dn, valStart);
            String value = dn.substring(valStart, comma).trim();
            result = result.replace("{" + type + "}",
                    escapeLDAPFilter(value));
            start = comma < len ? comma + 1 : len;
        }
        return result;
    }

    private static int findUnescapedComma(String s, int from) {
        int i = from;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\\') {
                i += 2;
            } else if (c == ',') {
                return i;
            } else {
                i++;
            }
        }
        return s.length();
    }

    // Bind helpers — choose between simple bind and SASL bind
    // based on the saslMechanism configuration property.

    private void performServiceBind(LDAPConnected connection,
                                    BindResultHandler handler) {
        if (bindDN != null && !bindDN.isEmpty()) {
            if (saslMechanism != null) {
                performSASLBind(connection, bindDN, bindPassword, handler);
            } else {
                connection.bind(bindDN, bindPassword, handler);
            }
        } else {
            connection.bindAnonymous(handler);
        }
    }

    private void performUserBind(LDAPConnected connection,
                                 String dn, String password,
                                 BindResultHandler handler) {
        if (saslMechanism != null) {
            performSASLBind(connection, dn, password, handler);
        } else {
            connection.bind(dn, password, handler);
        }
    }

    private void performSASLBind(LDAPConnected connection,
                                 String username, String password,
                                 BindResultHandler handler) {
        SASLClientMechanism mechanism =
                SASLUtils.createClient(saslMechanism, username, password, host);
        if (mechanism == null) {
            handler.handleBindFailure(
                    new LDAPResult(
                            LDAPResultCode.AUTH_METHOD_NOT_SUPPORTED,
                            "", "SASL mechanism not available: "
                                    + saslMechanism, null),
                    connection);
            return;
        }
        connection.bindSASL(mechanism, handler);
    }

    /**
     * Creates a new LDAP client with current configuration.
     */
    private LDAPClient createClient() {
        if (selectorLoop == null) {
            throw new IllegalStateException(L10N.getString("err.ldap_no_selectorloop"));
        }
        LDAPClient client = new LDAPClient(selectorLoop, host, port);
        client.setSecure(secure);
        if (keystoreFile != null) {
            client.setKeystoreFile(keystoreFile);
            client.setKeystorePass(keystorePass);
            client.setKeystoreFormat(keystoreFormat);
        }
        return client;
    }

    /**
     * Escapes special characters in LDAP filter values (RFC 4515).
     */
    private static String escapeLDAPFilter(String value) {
        StringBuilder sb = new StringBuilder();
        for (char c : value.toCharArray()) {
            switch (c) {
                case '\\':
                    sb.append("\\5c");
                    break;
                case '*':
                    sb.append("\\2a");
                    break;
                case '(':
                    sb.append("\\28");
                    break;
                case ')':
                    sb.append("\\29");
                    break;
                case '\u0000':
                    sb.append("\\00");
                    break;
                default:
                    sb.append(c);
            }
        }
        return sb.toString();
    }

}
