/*
 * LdapRealmTest.java
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

package org.bluezoo.gumdrop.auth.ldap;

import org.bluezoo.gumdrop.tls.KeystoreFormat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.Principal;
import java.security.PublicKey;
import java.security.SignatureException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.security.auth.x500.X500Principal;

import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.auth.SaslClientMechanism;
import org.bluezoo.gumdrop.auth.SaslMechanism;
import org.bluezoo.gumdrop.ldap.client.AddResultHandler;
import org.bluezoo.gumdrop.ldap.client.BindResultHandler;
import org.bluezoo.gumdrop.ldap.client.CompareResultHandler;
import org.bluezoo.gumdrop.ldap.client.Control;
import org.bluezoo.gumdrop.ldap.client.DeleteResultHandler;
import org.bluezoo.gumdrop.ldap.client.ExtendedResultHandler;
import org.bluezoo.gumdrop.ldap.client.LdapConnected;
import org.bluezoo.gumdrop.ldap.client.LdapConnectionReady;
import org.bluezoo.gumdrop.ldap.client.LdapResult;
import org.bluezoo.gumdrop.ldap.client.LdapResultCode;
import org.bluezoo.gumdrop.ldap.client.LdapSession;
import org.bluezoo.gumdrop.ldap.client.Modification;
import org.bluezoo.gumdrop.ldap.client.ModifyDNResultHandler;
import org.bluezoo.gumdrop.ldap.client.ModifyResultHandler;
import org.bluezoo.gumdrop.ldap.client.SearchRequest;
import org.bluezoo.gumdrop.ldap.client.SearchResultEntry;
import org.bluezoo.gumdrop.ldap.client.SearchResultHandler;
import org.bluezoo.gumdrop.ldap.client.StartTLSResultHandler;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests {@link LdapRealm} against an in-memory scripted LDAP server that
 * is plugged in through the realm's test seam, so authentication, role
 * lookup and certificate lookup logic run without any network I/O.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class LdapRealmTest {

    private static final LdapResult OK =
            new LdapResult(LdapResultCode.SUCCESS, "", "", null);
    private static final LdapResult DENIED = new LdapResult(
            LdapResultCode.INVALID_CREDENTIALS, "", "denied", null);

    /** What the fake server does and what it saw. */
    private static final class Script {
        boolean connectError;
        boolean neverReady;
        boolean serviceBindOk = true;
        final Map<String, String> passwords = new HashMap<String, String>();
        final List<SearchResultEntry> results =
                new ArrayList<SearchResultEntry>();
        final List<String> binds = new ArrayList<String>();
        final List<SearchRequest> searches = new ArrayList<SearchRequest>();
        int unbinds;
        int connects;
    }

    private static final class FakeSession implements LdapSession {
        private final Script script;

        FakeSession(Script script) {
            this.script = script;
        }

        @Override
        public void search(SearchRequest request, SearchResultHandler cb) {
            script.searches.add(request);
            for (SearchResultEntry e : script.results) {
                cb.handleEntry(e);
            }
            cb.handleDone(OK, this);
        }

        @Override public void modify(String dn, List<Modification> m,
                ModifyResultHandler cb) { }
        @Override public void add(String dn,
                Map<String, List<byte[]>> attrs, AddResultHandler cb) { }
        @Override public void delete(String dn, DeleteResultHandler cb) { }
        @Override public void compare(String dn, String attribute,
                byte[] value, CompareResultHandler cb) { }
        @Override public void modifyDN(String dn, String newRDN,
                boolean deleteOldRDN, ModifyDNResultHandler cb) { }
        @Override public void modifyDN(String dn, String newRDN,
                boolean deleteOldRDN, String newSuperior,
                ModifyDNResultHandler cb) { }
        @Override public void extended(String oid, byte[] value,
                ExtendedResultHandler cb) { }
        @Override public void rebind(String dn, String password,
                BindResultHandler cb) { }
        @Override public void rebindSASL(SaslClientMechanism c,
                BindResultHandler cb) { }
        @Override public void abandon(int messageId) { }
        @Override public void setRequestControls(List<Control> controls) { }
        @Override public void unbind() {
            script.unbinds++;
        }
    }

    private static final class FakeConnection implements LdapConnected {
        private final Script script;

        FakeConnection(Script script) {
            this.script = script;
        }

        @Override
        public void bind(String dn, String password, BindResultHandler cb) {
            script.binds.add("simple:" + dn + ":" + password);
            boolean ok;
            if (script.passwords.containsKey(dn)) {
                ok = script.passwords.get(dn).equals(password);
            } else {
                ok = script.serviceBindOk;
            }
            finishBind(ok, cb);
        }

        @Override
        public void bindAnonymous(BindResultHandler cb) {
            script.binds.add("anonymous");
            finishBind(script.serviceBindOk, cb);
        }

        @Override
        public void bindSASL(SaslClientMechanism mechanism,
                BindResultHandler cb) {
            script.binds.add("sasl");
            finishBind(true, cb);
        }

        private void finishBind(boolean ok, BindResultHandler cb) {
            if (ok) {
                cb.handleBindSuccess(new FakeSession(script));
            } else {
                cb.handleBindFailure(DENIED, this);
            }
        }

        @Override
        public void startTLS(StartTLSResultHandler cb) { }

        @Override
        public void unbind() {
            script.unbinds++;
        }
    }

    private static final class ScriptedRealm extends LdapRealm {
        private final Script script;

        ScriptedRealm(Script script) {
            this.script = script;
        }

        @Override
        void connectClientForTesting(LdapConnectionReady ready) {
            script.connects++;
            if (script.neverReady) {
                return;
            }
            if (script.connectError) {
                ready.onError(new IOException("refused"));
                return;
            }
            ready.handleReady(new FakeConnection(script));
        }
    }

    /** Certificate that only knows its DER bytes and subject. */
    private static final class FakeCert extends X509Certificate {
        private final byte[] der;
        private final X500Principal subject;

        FakeCert(byte[] der, String subject) {
            this.der = der;
            this.subject = new X500Principal(subject);
        }

        @Override public byte[] getEncoded()
                throws CertificateEncodingException { return der; }
        @Override public X500Principal getSubjectX500Principal() {
            return subject;
        }
        @Override public void checkValidity() { }
        @Override public void checkValidity(Date date) { }
        @Override public int getVersion() { return 3; }
        @Override public BigInteger getSerialNumber() {
            return BigInteger.ONE;
        }
        @Override public Principal getIssuerDN() { return subject; }
        @Override public Principal getSubjectDN() { return subject; }
        @Override public Date getNotBefore() { return new Date(0); }
        @Override public Date getNotAfter() { return new Date(0); }
        @Override public byte[] getTBSCertificate() { return der; }
        @Override public byte[] getSignature() { return der; }
        @Override public String getSigAlgName() { return "none"; }
        @Override public String getSigAlgOID() { return "0"; }
        @Override public byte[] getSigAlgParams() { return null; }
        @Override public boolean[] getIssuerUniqueID() { return null; }
        @Override public boolean[] getSubjectUniqueID() { return null; }
        @Override public boolean[] getKeyUsage() { return null; }
        @Override public int getBasicConstraints() { return -1; }
        @Override public void verify(PublicKey key)
                throws CertificateException, NoSuchAlgorithmException,
                InvalidKeyException, NoSuchProviderException,
                SignatureException { }
        @Override public void verify(PublicKey key, String sigProvider)
                throws CertificateException, NoSuchAlgorithmException,
                InvalidKeyException, NoSuchProviderException,
                SignatureException { }
        @Override public String toString() { return "FakeCert"; }
        @Override public PublicKey getPublicKey() { return null; }
        @Override public boolean hasUnsupportedCriticalExtension() {
            return false;
        }
        @Override public Set<String> getCriticalExtensionOIDs() {
            return null;
        }
        @Override public Set<String> getNonCriticalExtensionOIDs() {
            return null;
        }
        @Override public byte[] getExtensionValue(String oid) {
            return null;
        }
    }

    private Script script;
    private ScriptedRealm realm;

    @Before
    public void setUp() {
        script = new Script();
        realm = new ScriptedRealm(script);
        realm.setBaseDN("dc=example,dc=com");
    }

    private static SearchResultEntry entry(String dn, String attribute,
            String... values) {
        Map<String, List<byte[]>> attrs = new HashMap<String, List<byte[]>>();
        List<byte[]> list = new ArrayList<byte[]>();
        for (String v : values) {
            list.add(v.getBytes(StandardCharsets.UTF_8));
        }
        attrs.put(attribute, list);
        return new SearchResultEntry(dn, attrs);
    }

    private void addUser(String uid, String password) {
        String dn = "uid=" + uid + ",dc=example,dc=com";
        script.results.add(entry(dn, "uid", uid));
        script.passwords.put(dn, password);
    }

    // ── passwordMatch ──

    @Test
    public void correctPasswordAuthenticates() {
        addUser("alice", "secret");
        assertTrue(realm.passwordMatch("alice", "secret"));
        assertEquals("anonymous", script.binds.get(0));
        assertEquals("simple:uid=alice,dc=example,dc=com:secret",
                script.binds.get(1));
        SearchRequest search = script.searches.get(0);
        assertEquals("(uid=alice)", search.getFilter());
        assertEquals("dc=example,dc=com", search.getBaseDN());
        assertEquals(1, search.getSizeLimit());
        assertTrue(script.unbinds >= 2);
    }

    @Test
    public void wrongPasswordRejected() {
        addUser("alice", "secret");
        assertFalse(realm.passwordMatch("alice", "guess"));
    }

    @Test
    public void unknownUserRejectedWithoutUserBind() {
        assertFalse(realm.passwordMatch("nobody", "x"));
        assertEquals(1, script.binds.size());
    }

    @Test
    public void nullCredentialsRejectedWithoutConnecting() {
        assertFalse(realm.passwordMatch(null, "x"));
        assertFalse(realm.passwordMatch("alice", null));
        assertEquals(0, script.connects);
    }

    @Test
    public void filterMetacharactersEscaped() {
        realm.passwordMatch("a*(b)\\c" + (char) 0, "x");
        assertEquals("(uid=a\\2a\\28b\\29\\5cc\\00)",
                script.searches.get(0).getFilter());
    }

    @Test
    public void customUserFilterUsed() {
        realm.setUserFilter("(&(objectClass=person)(mail={0}))");
        realm.passwordMatch("a@b.c", "x");
        assertEquals("(&(objectClass=person)(mail=a@b.c))",
                script.searches.get(0).getFilter());
    }

    @Test
    public void serviceAccountBindUsedWhenConfigured() {
        realm.setBindDN("cn=svc,dc=example,dc=com");
        realm.setBindPassword("svcpw");
        addUser("alice", "secret");
        assertTrue(realm.passwordMatch("alice", "secret"));
        assertEquals("simple:cn=svc,dc=example,dc=com:svcpw",
                script.binds.get(0));
    }

    @Test
    public void serviceBindFailureRejects() {
        script.serviceBindOk = false;
        addUser("alice", "secret");
        assertFalse(realm.passwordMatch("alice", "secret"));
    }

    @Test
    public void connectionErrorRejects() {
        script.connectError = true;
        assertFalse(realm.passwordMatch("alice", "secret"));
        assertFalse(realm.userExists("alice"));
        assertFalse(realm.isUserInRole("alice", "admin"));
    }

    @Test
    public void timeoutRejects() {
        script.neverReady = true;
        realm.setTimeout(0);
        assertFalse(realm.passwordMatch("alice", "secret"));
    }

    @Test
    public void saslBindUsedWhenMechanismConfigured() {
        realm.setSaslMechanism("PLAIN");
        realm.setBindDN("cn=svc,dc=example,dc=com");
        realm.setBindPassword("svcpw");
        addUser("alice", "secret");
        realm.passwordMatch("alice", "secret");
        assertEquals("sasl", script.binds.get(0));
    }

    @Test
    public void unknownSaslMechanismFailsBind() {
        realm.setSaslMechanism("NOT-A-MECHANISM");
        realm.setBindDN("cn=svc,dc=example,dc=com");
        addUser("alice", "secret");
        assertFalse(realm.passwordMatch("alice", "secret"));
        assertTrue(script.binds.isEmpty());
    }

    @Test
    public void unconfiguredRealmWithoutRuntimeRejects() {
        LdapRealm bare = new LdapRealm();
        assertFalse(bare.passwordMatch("alice", "x"));
        assertFalse(bare.userExists("alice"));
        assertFalse(bare.isUserInRole("alice", "r"));
    }

    // ── userExists / roles ──

    @Test
    public void userExistsReflectsSearchResult() {
        assertFalse(realm.userExists("alice"));
        addUser("alice", "x");
        assertTrue(realm.userExists("alice"));
        assertFalse(realm.userExists(null));
    }

    @Test
    public void roleMatchedCaseInsensitivelyWithPrefix() {
        realm.setRolePrefix("cn=");
        script.results.add(entry("uid=alice,dc=example,dc=com", "memberOf",
                "CN=Admins,ou=groups", "cn=users,ou=groups"));
        assertTrue(realm.isUserInRole("alice", "admins"));
        assertFalse(realm.isUserInRole("alice", "staff"));
        assertEquals("(uid=alice)", script.searches.get(0).getFilter());
        assertEquals(Arrays.asList("memberOf"),
                script.searches.get(0).getAttributes());
    }

    @Test
    public void customRoleAttributeQueried() {
        realm.setRoleAttribute("groups");
        script.results.add(entry("uid=alice,dc=example,dc=com", "groups",
                "staff"));
        assertTrue(realm.isUserInRole("alice", "staff"));
        assertEquals(Arrays.asList("groups"),
                script.searches.get(0).getAttributes());
    }

    @Test
    public void roleCheckWithNullArgumentsIsFalse() {
        assertFalse(realm.isUserInRole(null, "r"));
        assertFalse(realm.isUserInRole("u", null));
        assertEquals(0, script.connects);
    }

    @Test
    public void roleCheckServiceBindFailureIsFalse() {
        script.serviceBindOk = false;
        assertFalse(realm.isUserInRole("alice", "r"));
    }

    // ── certificate authentication ──

    @Test
    public void certificateAuthenticationDisabledByDefault() {
        assertNull(realm.authenticateCertificate(new FakeCert(
                new byte[] {1}, "CN=alice")));
        assertEquals(0, script.connects);
    }

    @Test
    public void noCertificateModeYieldsNull() {
        assertNull(realm.authenticateCertificate(new FakeCert(
                new byte[] {1}, "CN=alice")));
    }

    @Test
    public void binaryModeSearchesEscapedDerAndReturnsUsername() {
        realm.setCertLookupMode(LdapRealm.CertLookupMode.BINARY);
        script.results.add(entry("uid=alice,dc=example,dc=com", "uid",
                "alice"));
        Realm.CertificateAuthenticationResult r =
                realm.authenticateCertificate(new FakeCert(
                        new byte[] {1, (byte) 0xab}, "CN=alice"));
        assertTrue(r.valid);
        assertEquals("alice", r.username);
        assertEquals("(&(userCertificate;binary=\\01\\ab)"
                + "(objectClass=person))",
                script.searches.get(0).getFilter());
        assertEquals(Arrays.asList("uid"),
                script.searches.get(0).getAttributes());
    }

    @Test
    public void binaryModeNoMatchFails() {
        realm.setCertLookupMode(LdapRealm.CertLookupMode.BINARY);
        assertFalse(realm.authenticateCertificate(new FakeCert(
                new byte[] {1}, "CN=alice")).valid);
    }

    @Test
    public void subjectModeSubstitutesRdnPlaceholders() {
        realm.setCertLookupMode(LdapRealm.CertLookupMode.SUBJECT);
        realm.setCertSubjectFilter("(&(cn={CN})(o={O}))");
        realm.setCertUsernameAttribute("mail");
        script.results.add(entry("uid=alice,dc=example,dc=com", "mail",
                "alice@example.com"));
        Realm.CertificateAuthenticationResult r =
                realm.authenticateCertificate(new FakeCert(new byte[] {1},
                        "CN=alice,O=Acme\\, Inc"));
        assertTrue(r.valid);
        assertEquals("alice@example.com", r.username);
        // RFC 2253 escapes in the DN value must be removed before the
        // value is escaped for use in a filter
        assertEquals("(&(cn=alice)(o=Acme, Inc))",
                script.searches.get(0).getFilter());
    }

    @Test
    public void subjectModeUnescapesSpecialCharactersInRdnValues() {
        realm.setCertLookupMode(LdapRealm.CertLookupMode.SUBJECT);
        realm.setCertSubjectFilter("(cn={CN})");
        script.results.add(entry("uid=x,dc=example,dc=com", "uid", "x"));
        realm.authenticateCertificate(new FakeCert(new byte[] {1},
                "CN=a\\+b"));
        assertEquals("(cn=a+b)", script.searches.get(0).getFilter());
    }

    @Test
    public void subjectModeWithoutFilterFails() {
        realm.setCertLookupMode(LdapRealm.CertLookupMode.SUBJECT);
        assertFalse(realm.authenticateCertificate(new FakeCert(
                new byte[] {1}, "CN=alice")).valid);
        assertEquals(0, script.connects);
    }

    @Test
    public void certificateLookupErrorFails() {
        realm.setCertLookupMode(LdapRealm.CertLookupMode.BINARY);
        script.connectError = true;
        assertFalse(realm.authenticateCertificate(new FakeCert(
                new byte[] {1}, "CN=alice")).valid);
    }

    // ── misc ──

    @Test
    public void staticCapabilities() {
        assertNull(realm.getDigestHA1("alice", "realm"));
        Set<SaslMechanism> mechanisms = realm.getSupportedSASLMechanisms();
        assertTrue(mechanisms.contains(SaslMechanism.PLAIN));
        assertTrue(mechanisms.contains(SaslMechanism.LOGIN));
        assertTrue(mechanisms.contains(SaslMechanism.EXTERNAL));
        assertEquals(3, mechanisms.size());
    }

    @Test
    @SuppressWarnings("deprecation")
    public void passwordRetrievalUnsupported() {
        try {
            realm.getPassword("alice");
            fail("expected UnsupportedOperationException");
        } catch (UnsupportedOperationException expected) {
            // LDAP cannot reveal passwords
        }
    }

    @Test
    public void forSelectorLoopReturnsIndependentCopy() {
        realm.setHost("ldap.example.org");
        Realm copy = realm.forSelectorLoop(null);
        assertNotNull(copy);
        assertNotSame(realm, copy);
        assertTrue(copy instanceof LdapRealm);
    }

    @Test
    public void settersAcceptAllConfiguration() {
        LdapRealm r = new LdapRealm();
        r.setHost("h");
        r.setPort(1389);
        r.setSecure(true);
        r.setStartTLS(true);
        r.setKeystoreFile(Path.of("/tmp/none.p12"));
        r.setKeystoreFile(Path.of("/tmp/none.p12"));
        r.setKeystorePass("pw");
        r.setKeystoreFormat(KeystoreFormat.JKS);
        r.setBindDN("cn=x");
        r.setBindPassword("p");
        r.setSelectorLoop(null);
        assertNotNull(r.forSelectorLoop(null));
    }

    @Test
    public void binaryEscapeFormatsEveryByte() {
        assertEquals("", LdapRealm.toLDAPBinaryEscape(new byte[0]));
        assertEquals("\\00\\7f\\80\\ff", LdapRealm.toLDAPBinaryEscape(
                new byte[] {0, 0x7f, (byte) 0x80, (byte) 0xff}));
    }
}
