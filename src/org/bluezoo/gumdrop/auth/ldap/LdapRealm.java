/*
 * LdapRealm.java
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

package org.bluezoo.gumdrop.auth.ldap;

import org.bluezoo.gumdrop.tls.KeystoreFormat;
import org.bluezoo.gumdrop.tls.TlsConfig;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.text.MessageFormat;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.ResourceBundle;
import java.util.logging.Logger;

import javax.security.auth.x500.X500Principal;

import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.auth.RealmCallback;
import org.bluezoo.gumdrop.auth.SaslClientMechanism;
import org.bluezoo.gumdrop.auth.SaslMechanism;
import org.bluezoo.gumdrop.auth.SaslUtils;
import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.TimerHandle;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.ldap.client.BindResultHandler;
import org.bluezoo.gumdrop.ldap.client.LdapClient;
import org.bluezoo.gumdrop.ldap.client.LdapConnected;
import org.bluezoo.gumdrop.ldap.client.LdapPostTLS;
import org.bluezoo.gumdrop.ldap.client.LdapConnectionReady;
import org.bluezoo.gumdrop.ldap.client.LdapConstants;
import org.bluezoo.gumdrop.ldap.client.LdapResult;
import org.bluezoo.gumdrop.ldap.client.LdapResultCode;
import org.bluezoo.gumdrop.ldap.client.LdapSession;
import org.bluezoo.gumdrop.ldap.client.SearchRequest;
import org.bluezoo.gumdrop.ldap.client.SearchResultEntry;
import org.bluezoo.gumdrop.ldap.client.SearchResultHandler;
import org.bluezoo.gumdrop.ldap.client.SearchScope;
import org.bluezoo.gumdrop.ldap.client.StartTLSResultHandler;
import org.bluezoo.gumdrop.telemetry.EventLogger;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;

/**
 * LDAP-backed Realm for authenticating users against a directory server.
 *
 * <p>This realm connects to an LDAP server (Active Directory, OpenLDAP, etc.)
 * to authenticate users and check role membership. It supports both simple
 * bind authentication and search-then-bind patterns.
 *
 * <h3>Configuration</h3>
 * <pre>{@code
 * LdapRealm realm = new LdapRealm()
 *     .host("ldap.example.com")
 *     .port(389)
 *     .baseDN("dc=example,dc=com")
 *     .bindDN("cn=service,dc=example,dc=com")
 *     .bindPassword("secret")
 *     .userFilter("(uid={0})")
 *     .roleAttribute("memberOf");
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
 * Any mechanism {@link SaslUtils#createClient} can create may be named, for
 * example PLAIN, EXTERNAL or GSSAPI; they are implemented using gumdrop's
 * own cryptographic primitives (no JDK SASL dependency). The MD5-based
 * mechanisms (CRAM-MD5, DIGEST-MD5) are not supported and are rejected when
 * configured.
 *
 * <h3>TLS Support</h3>
 * <ul>
 *   <li>LDAPS (port 636): call {@code secure(true)} and, to trust a private CA
 *       or present a client certificate, {@link #tls(TlsConfig)}</li>
 *   <li>STARTTLS (port 389): call {@code startTLS(true)}; each connection is
 *       upgraded before any bind, and if the upgrade fails the operation
 *       fails without sending credentials</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc4511">RFC 4511: LDAPv3</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc4513">RFC 4513: LDAP Authentication Methods</a>
 */
public class LdapRealm implements Realm {

    static final ResourceBundle L10N = ResourceBundle.getBundle("org.bluezoo.gumdrop.auth.L10N",
                org.bluezoo.gumdrop.auth.Realm.class.getModule());
    private static final Logger LOGGER = Logger.getLogger(LdapRealm.class.getName());

    private EventLogger events() {
        return (selectorLoop != null ? selectorLoop.getTelemetryConfig() : new TelemetryConfig()).getLogger(LdapRealm.class, L10N);
    }

    /** Default timeout for LDAP operations in seconds. */
    private static final int DEFAULT_TIMEOUT = 30;

    // Configuration
    private String host = "localhost";
    private int port = LdapConstants.DEFAULT_PORT;
    private boolean secure = false;
    private boolean startTLS = false;
    private TlsConfig tls = new TlsConfig();
    private String baseDN = "";
    private String bindDN;
    private String bindPassword;
    private String userFilter = "(uid={0})";
    private String roleAttribute = "memberOf";
    private String rolePrefix = "";
    private int timeout = DEFAULT_TIMEOUT;
    private String saslMechanism;

    private CertLookupMode certLookupMode;
    private String certUsernameAttribute = "uid";
    private String certSubjectFilter;

    // Runtime state
    private SelectorLoop selectorLoop;

    /**
     * Supported SASL mechanisms.
     * LDAP realm only supports PLAIN and LOGIN since it needs the
     * plaintext password to perform LDAP bind.
     */
    private static final Set<SaslMechanism> SUPPORTED_MECHANISMS =
            Collections.unmodifiableSet(EnumSet.of(
                    SaslMechanism.PLAIN,
                    SaslMechanism.LOGIN,
                    SaslMechanism.EXTERNAL
            ));

    /**
     * Creates a new LdapRealm with default settings.
     */
    public LdapRealm() {
    }

    /**
     * Copy constructor for forSelectorLoop.
     */
    private LdapRealm(LdapRealm source, SelectorLoop loop) {
        this.host = source.host;
        this.port = source.port;
        this.secure = source.secure;
        this.startTLS = source.startTLS;
        this.tls = new TlsConfig().copyFrom(source.tls);
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

    public LdapRealm host(String host) {
        this.host = host;
        return this;
    }

    public LdapRealm port(int port) {
        this.port = port;
        return this;
    }

    public LdapRealm secure(boolean secure) {
        this.secure = secure;
        return this;
    }

    /**
     * Sets the TLS settings used for the realm's LDAP connections: trust
     * material for verifying the server, and a client identity if the server
     * wants one. The settings are copied. Whether TLS is used is decided by
     * {@link #secure(boolean)} (LDAPS) or {@link #startTLS(boolean)}.
     *
     * @param tls the TLS configuration
     * @return this realm
     */
    public LdapRealm tls(TlsConfig tls) {
        this.tls = new TlsConfig().copyFrom(tls);
        return this;
    }

    public LdapRealm startTLS(boolean startTLS) {
        this.startTLS = startTLS;
        return this;
    }

    public LdapRealm baseDN(String baseDN) {
        this.baseDN = baseDN;
        return this;
    }

    public LdapRealm bindDN(String bindDN) {
        this.bindDN = bindDN;
        return this;
    }

    public LdapRealm bindPassword(String bindPassword) {
        this.bindPassword = bindPassword;
        return this;
    }

    /** @see <a href="https://www.rfc-editor.org/rfc/rfc4515">RFC 4515: LDAP Search Filters</a> */
    public LdapRealm userFilter(String userFilter) {
        this.userFilter = userFilter;
        return this;
    }

    public LdapRealm roleAttribute(String roleAttribute) {
        this.roleAttribute = roleAttribute;
        return this;
    }

    public LdapRealm rolePrefix(String rolePrefix) {
        this.rolePrefix = rolePrefix;
        return this;
    }

    public LdapRealm timeout(int timeout) {
        this.timeout = timeout;
        return this;
    }

    /**
     * Sets the SASL mechanism to use for binding to the LDAP server.
     *
     * <p>When set, all LDAP binds (service account and user
     * authentication) use SASL instead of simple bind. Common
     * mechanisms include {@code GSSAPI} and {@code EXTERNAL}.
     *
     * @param mechanism the SASL mechanism name, or null for simple bind
     * @throws IllegalArgumentException for {@code CRAM-MD5} or
     *         {@code DIGEST-MD5}, which are not supported
     * @see <a href="https://www.rfc-editor.org/rfc/rfc4513#section-5.2">RFC 4513 §5.2</a>
     * @return this realm
     */
    public LdapRealm saslMechanism(String mechanism) {
        if ("DIGEST-MD5".equalsIgnoreCase(mechanism)
                || "CRAM-MD5".equalsIgnoreCase(mechanism)) {
            throw new IllegalArgumentException("SASL mechanism not supported "
                    + "(MD5-based mechanisms were removed): " + mechanism);
        }
        this.saslMechanism = mechanism;
        return this;
    }

    public LdapRealm selectorLoop(SelectorLoop selectorLoop) {
        this.selectorLoop = selectorLoop;
        return this;
    }

    /** How a client certificate is matched to a directory entry. */
    public enum CertLookupMode {
        /** Match the DER-encoded certificate against a binary attribute. */
        BINARY,
        /** Extract the CN from the certificate's Subject DN and search on it. */
        SUBJECT
    }

    /**
     * Sets the certificate lookup mode.
     *
     * @param mode {@link CertLookupMode#BINARY} to match by DER-encoded
     *             certificate, or {@link CertLookupMode#SUBJECT} to extract
     *             CN from the certificate's
     *             Subject DN
     * @return this realm
     */
    public LdapRealm certLookupMode(CertLookupMode mode) {
        this.certLookupMode = mode;
        return this;
    }

    /**
     * Sets the LDAP attribute to read as the username from a matched
     * certificate entry.
     *
     * @param attribute the LDAP attribute name (default: "uid")
     * @return this realm
     */
    public LdapRealm certUsernameAttribute(String attribute) {
        this.certUsernameAttribute = attribute;
        return this;
    }

    /**
     * Sets the LDAP filter template for subject-mode certificate
     * lookup. Placeholders like {CN}, {O}, {OU} are replaced with
     * the corresponding RDN values from the certificate's Subject DN.
     *
     * @param filter the filter template, e.g. "(uid={CN})"
     * @return this realm
     */
    public LdapRealm certSubjectFilter(String filter) {
        this.certSubjectFilter = filter;
        return this;
    }

    // Realm interface implementation

    @Override
    public Realm forSelectorLoop(SelectorLoop loop) {
        return new LdapRealm(this, loop);
    }

    @Override
    public Set<SaslMechanism> getSupportedSASLMechanisms() {
        return SUPPORTED_MECHANISMS;
    }

    /**
     * RFC 4513 §5.1/§5.2 — authenticates via simple or SASL bind: binds as
     * the service account, finds the user's DN, then re-binds as that DN with
     * the password, all on one connection. A wrong password or unknown user
     * completes with {@code false}; an unreachable or slow directory fails.
     */
    @Override
    public void passwordMatch(String username, String password,
                              RealmCallback<Boolean> callback) {
        if (username == null || password == null) {
            callback.completed(Boolean.FALSE);
            return;
        }
        new PasswordMatch(username, password, callback).start();
    }

    /** LDAP cannot supply H(A1) without the plaintext password. */
    @Override
    public void getDigestHA1(String username, String realmName,
                             RealmCallback<String> callback) {
        callback.completed(null);
    }

    @Override
    public void isUserInRole(String username, String role,
                             RealmCallback<Boolean> callback) {
        if (username == null || role == null) {
            callback.completed(Boolean.FALSE);
            return;
        }
        new RoleCheck(username, role, callback).start();
    }

    @Override
    public void userExists(String username, RealmCallback<Boolean> callback) {
        if (username == null) {
            callback.completed(Boolean.FALSE);
            return;
        }
        new UserLookup(username, callback).start();
    }

    /** Certificate-to-user mapping via LDAP search. */
    @Override
    public void authenticateCertificate(X509Certificate certificate,
            RealmCallback<CertificateAuthenticationResult> callback) {
        if (certLookupMode == null) {
            callback.completed(null);
            return;
        }
        String filter;
        try {
            if (certLookupMode == CertLookupMode.BINARY) {
                filter = "(&(userCertificate;binary="
                        + toLDAPBinaryEscape(certificate.getEncoded())
                        + ")(objectClass=person))";
            } else {
                if (certSubjectFilter == null) {
                    events().warn("warn.cert_subject_filter_not_configured").emit();
                    callback.completed(CertificateAuthenticationResult.failure());
                    return;
                }
                String dn = certificate.getSubjectX500Principal()
                        .getName(X500Principal.RFC2253);
                filter = replaceRDNPlaceholders(certSubjectFilter, dn);
            }
        } catch (Exception e) {
            events().warn("warn.certificate_authentication_error").thrown(e).emit();
            callback.failed(e);
            return;
        }
        new CertificateLookup(filter, callback).start();
    }

    // LDAP operations
    //
    // Each realm operation is a small state machine driven by the LDAP
    // client's callbacks, all of which run on this realm's selector loop:
    // connect, bind as the service account, do the search, and complete.
    // Nothing waits. A timer started with the operation fails it if the
    // directory is too slow.

    /** One realm operation on one LDAP connection. */
    private abstract class Operation<T> implements LdapConnectionReady {

        private final RealmCallback<T> callback;
        private final String warnKey;
        private boolean done;
        private TimerHandle timeoutHandle;
        private LdapConnected connection;
        private LdapSession session;

        Operation(RealmCallback<T> callback, String warnKey) {
            this.callback = callback;
            this.warnKey = warnKey;
        }

        /** Called once bound as the service account. */
        abstract void bound(LdapSession session);

        final void start() {
            timeoutHandle = scheduleTimeout(timeout * 1000L, new Runnable() {
                @Override
                public void run() {
                    fail(new IOException(L10N.getString("err.ldap_timeout")));
                }
            });
            connectClientForTesting(this);
        }

        @Override
        public void handleReady(LdapConnected ready) {
            if (done) {
                ready.unbind();
                return;
            }
            connection = ready;
            performServiceBind(ready, new BindResultHandler() {
                @Override
                public void handleBindSuccess(LdapSession bound) {
                    session = bound;
                    connection = null;
                    if (!done) {
                        bound(bound);
                    }
                }

                @Override
                public void handleBindFailure(LdapResult result, LdapConnected conn) {
                    fail(new IOException(MessageFormat.format(
                            L10N.getString("err.ldap_bind"), result)));
                }
            });
        }

        @Override
        public void onConnected(Endpoint endpoint) {
        }

        @Override
        public void onSecurityEstablished(SecurityInfo info) {
        }

        @Override
        public void onError(Exception cause) {
            fail(cause);
        }

        @Override
        public void onDisconnected() {
            fail(new IOException("LDAP connection closed"));
        }

        /** Searches the subtree below the base DN, for at most one entry. */
        final void search(LdapSession on, String filter, String attribute,
                          SearchResultHandler handler) {
            SearchRequest search = new SearchRequest();
            search.setBaseDN(baseDN);
            search.setScope(SearchScope.SUBTREE);
            search.setFilter(filter);
            search.setAttributes(attribute);
            search.setSizeLimit(1);
            on.search(search, handler);
        }

        final LdapSession session() {
            return session;
        }

        final void succeed(T result) {
            if (done) {
                return;
            }
            finish();
            callback.completed(result);
        }

        final void fail(Throwable cause) {
            if (done) {
                return;
            }
            finish();
            events().warn(warnKey).thrown(cause).emit();
            callback.failed(cause);
        }

        private void finish() {
            done = true;
            if (timeoutHandle != null) {
                timeoutHandle.cancel();
            }
            LdapSession s = session;
            LdapConnected c = connection;
            session = null;
            connection = null;
            if (s != null) {
                s.unbind();
            } else if (c != null) {
                c.unbind();
            }
        }
    }

    /** Ignores referrals and finishes with the entries seen so far. */
    private abstract class SearchHandler implements SearchResultHandler {
        @Override
        public void handleReference(String[] referralUrls) {
        }
    }

    private final class PasswordMatch extends Operation<Boolean> {

        private final String username;
        private final String password;
        private String userDN;

        PasswordMatch(String username, String password, RealmCallback<Boolean> callback) {
            super(callback, "warn.ldap_auth_error");
            this.username = username;
            this.password = password;
        }

        @Override
        void bound(LdapSession on) {
            String filter = userFilter.replace("{0}", escapeLDAPFilter(username));
            search(on, filter, "dn", new SearchHandler() {
                @Override
                public void handleEntry(SearchResultEntry entry) {
                    if (userDN == null) {
                        userDN = entry.getDN();
                    }
                }

                @Override
                public void handleDone(LdapResult result, LdapSession sess) {
                    if (userDN == null) {
                        succeed(Boolean.FALSE);
                        return;
                    }
                    bindAsUser(sess);
                }
            });
        }

        /** Re-binds the connection as the user: success means the password is right. */
        private void bindAsUser(LdapSession on) {
            BindResultHandler handler = new BindResultHandler() {
                @Override
                public void handleBindSuccess(LdapSession bound) {
                    succeed(Boolean.TRUE);
                }

                @Override
                public void handleBindFailure(LdapResult result, LdapConnected conn) {
                    LdapResultCode code = result.getResultCode();
                    if (code == LdapResultCode.UNAVAILABLE || code == LdapResultCode.BUSY) {
                        fail(new IOException(MessageFormat.format(
                                L10N.getString("err.ldap_bind"), result)));
                        return;
                    }
                    succeed(Boolean.FALSE);
                }
            };
            if (saslMechanism == null) {
                on.rebind(userDN, password, handler);
                return;
            }
            SaslClientMechanism mechanism =
                    SaslUtils.createClient(saslMechanism, userDN, password, host);
            if (mechanism == null) {
                fail(new IllegalStateException(
                        "SASL mechanism not available: " + saslMechanism));
                return;
            }
            on.rebindSASL(mechanism, handler);
        }
    }

    private final class RoleCheck extends Operation<Boolean> {

        private final String username;
        private final String targetRole;
        private boolean hasRole;

        RoleCheck(String username, String role, RealmCallback<Boolean> callback) {
            super(callback, "warn.ldap_role_error");
            this.username = username;
            this.targetRole = rolePrefix + role;
        }

        @Override
        void bound(LdapSession on) {
            String filter = userFilter.replace("{0}", escapeLDAPFilter(username));
            search(on, filter, roleAttribute, new SearchHandler() {
                @Override
                public void handleEntry(SearchResultEntry entry) {
                    // memberOf typically holds full DNs such as
                    // "cn=admins,ou=groups,dc=example,dc=com": the role
                    // matches if its name appears in a value
                    for (String value : entry.getAttributeStringValues(roleAttribute)) {
                        if (value.toLowerCase().contains(targetRole.toLowerCase())) {
                            hasRole = true;
                            break;
                        }
                    }
                }

                @Override
                public void handleDone(LdapResult result, LdapSession sess) {
                    succeed(Boolean.valueOf(hasRole));
                }
            });
        }
    }

    private final class UserLookup extends Operation<Boolean> {

        private final String username;
        private boolean found;

        UserLookup(String username, RealmCallback<Boolean> callback) {
            super(callback, "warn.ldap_user_error");
            this.username = username;
        }

        @Override
        void bound(LdapSession on) {
            String filter = userFilter.replace("{0}", escapeLDAPFilter(username));
            search(on, filter, "dn", new SearchHandler() {
                @Override
                public void handleEntry(SearchResultEntry entry) {
                    found = true;
                }

                @Override
                public void handleDone(LdapResult result, LdapSession sess) {
                    succeed(Boolean.valueOf(found));
                }
            });
        }
    }

    private final class CertificateLookup
            extends Operation<CertificateAuthenticationResult> {

        private final String filter;
        private String foundUsername;

        CertificateLookup(String filter,
                          RealmCallback<CertificateAuthenticationResult> callback) {
            super(callback, "warn.certificate_authentication_error");
            this.filter = filter;
        }

        @Override
        void bound(LdapSession on) {
            search(on, filter, certUsernameAttribute, new SearchHandler() {
                @Override
                public void handleEntry(SearchResultEntry entry) {
                    List<String> values =
                            entry.getAttributeStringValues(certUsernameAttribute);
                    if (values != null && !values.isEmpty()) {
                        foundUsername = values.get(0);
                    }
                }

                @Override
                public void handleDone(LdapResult result, LdapSession sess) {
                    if (foundUsername != null) {
                        succeed(CertificateAuthenticationResult.success(foundUsername));
                    } else {
                        succeed(CertificateAuthenticationResult.failure());
                    }
                }
            });
        }
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
                    escapeLDAPFilter(unescapeRDNValue(value)));
            start = comma < len ? comma + 1 : len;
        }
        return result;
    }

    /**
     * Removes RFC 4514 escaping from an RDN value: {@code \X} yields the
     * character X and {@code \HH} sequences are UTF-8 encoded bytes.
     */
    private static String unescapeRDNValue(String value) {
        if (value.indexOf('\\') < 0) {
            return value;
        }
        StringBuilder sb = new StringBuilder(value.length());
        ByteArrayOutputStream pending = new ByteArrayOutputStream();
        int len = value.length();
        int i = 0;
        while (i < len) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < len) {
                int hi = i + 2 < len ? Character.digit(value.charAt(i + 1), 16) : -1;
                int lo = hi >= 0 ? Character.digit(value.charAt(i + 2), 16) : -1;
                if (hi >= 0 && lo >= 0) {
                    pending.write((hi << 4) | lo);
                    i += 3;
                    continue;
                }
                flushPending(sb, pending);
                sb.append(value.charAt(i + 1));
                i += 2;
            } else {
                flushPending(sb, pending);
                sb.append(c);
                i++;
            }
        }
        flushPending(sb, pending);
        return sb.toString();
    }

    private static void flushPending(StringBuilder sb,
                                     ByteArrayOutputStream pending) {
        if (pending.size() > 0) {
            sb.append(new String(pending.toByteArray(), StandardCharsets.UTF_8));
            pending.reset();
        }
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
    // based on the saslMechanism configuration property, upgrading the
    // connection with STARTTLS first when startTLS is configured.

    /** The bind operations common to a fresh and a TLS-upgraded connection. */
    private interface Binder {
        void bind(String dn, String password, BindResultHandler handler);

        void bindSASL(SaslClientMechanism mechanism, BindResultHandler handler);

        void bindAnonymous(BindResultHandler handler);
    }

    /** A step to run once the connection is ready to bind. */
    private interface BindStep {
        void run(Binder binder, LdapConnected connection);
    }

    private static Binder binderFor(final LdapConnected connection) {
        return new Binder() {
            @Override
            public void bind(String dn, String password, BindResultHandler handler) {
                connection.bind(dn, password, handler);
            }

            @Override
            public void bindSASL(SaslClientMechanism mechanism, BindResultHandler handler) {
                connection.bindSASL(mechanism, handler);
            }

            @Override
            public void bindAnonymous(BindResultHandler handler) {
                connection.bindAnonymous(handler);
            }
        };
    }

    private static Binder binderFor(final LdapPostTLS connection) {
        return new Binder() {
            @Override
            public void bind(String dn, String password, BindResultHandler handler) {
                connection.bind(dn, password, handler);
            }

            @Override
            public void bindSASL(SaslClientMechanism mechanism, BindResultHandler handler) {
                connection.bindSASL(mechanism, handler);
            }

            @Override
            public void bindAnonymous(BindResultHandler handler) {
                connection.bindAnonymous(handler);
            }
        };
    }

    /**
     * Runs {@code step} once the connection can bind: immediately, or after
     * a STARTTLS upgrade when {@link #startTLS} is set and the connection is
     * not already secure. If the upgrade fails the step is never run, so no
     * credentials are sent in the clear, and {@code handler} receives the
     * failure.
     */
    private void whenReady(final LdapConnected connection, final BindResultHandler handler,
                           final BindStep step) {
        if (!startTLS || secure) {
            step.run(binderFor(connection), connection);
            return;
        }
        connection.startTLS(new StartTLSResultHandler() {
            @Override
            public void handleTLSEstablished(LdapPostTLS secured) {
                step.run(binderFor(secured), connection);
            }

            @Override
            public void handleStartTLSFailure(LdapResult result, LdapConnected conn) {
                handler.handleBindFailure(result, conn);
            }
        });
    }

    private void performSASLBind(Binder binder, LdapConnected connection,
                                  String username, String password,
                                  BindResultHandler handler) {
        SaslClientMechanism mechanism =
                SaslUtils.createClient(saslMechanism, username, password, host);
        if (mechanism == null) {
            handler.handleBindFailure(
                    new LdapResult(
                            LdapResultCode.AUTH_METHOD_NOT_SUPPORTED,
                            "", "SASL mechanism not available: "
                                    + saslMechanism, null),
                    connection);
            return;
        }
        binder.bindSASL(mechanism, handler);
    }

    private void performServiceBind(LdapConnected connection,
                                    final BindResultHandler handler) {
        whenReady(connection, handler, new BindStep() {
            @Override
            public void run(Binder binder, LdapConnected connection) {
                if (bindDN != null && !bindDN.isEmpty()) {
                    if (saslMechanism != null) {
                        performSASLBind(binder, connection, bindDN, bindPassword, handler);
                    } else {
                        binder.bind(bindDN, bindPassword, handler);
                    }
                } else {
                    binder.bindAnonymous(handler);
                }
            }
        });
    }

    /**
     * Test seam: starts the operation's timeout. The timer fires on this
     * realm's loop. Unit tests override this to fire it by hand.
     *
     * @param delayMs the delay in milliseconds
     * @param task what to run on timeout
     * @return a handle that cancels the timer
     */
    TimerHandle scheduleTimeout(long delayMs, final Runnable task) {
        final SelectorLoop loop = selectorLoop;
        Gumdrop gumdrop = (loop != null) ? loop.getGumdrop() : null;
        if (gumdrop == null) {
            return new TimerHandle() {
                @Override
                public void cancel() {
                }

                @Override
                public boolean isCancelled() {
                    return false;
                }
            };
        }
        return gumdrop.scheduleTimer(null, delayMs, new Runnable() {
            @Override
            public void run() {
                loop.invokeLater(task);
            }
        });
    }

    /**
     * Creates a new LDAP client with current configuration.
     */
    /**
     * Test seam: opens a connection to the LDAP server and reports the
     * outcome to {@code ready}. Production behaviour is to create a
     * client and connect it on this realm's runtime; unit tests override
     * this to supply an in-memory server without any network I/O.
     *
     * @param ready receives the connection, or the connection error
     */
    void connectClientForTesting(LdapConnectionReady ready) {
        if (selectorLoop == null) {
            ready.onError(new IllegalStateException(
                    L10N.getString("err.ldap_no_selectorloop")));
            return;
        }
        createClient().connect(selectorLoop.getGumdrop(), ready);
    }

    private LdapClient createClient() {
        LdapClient client = new LdapClient(selectorLoop, host, port);
        client.secure(secure);
        client.tls(tls);
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
