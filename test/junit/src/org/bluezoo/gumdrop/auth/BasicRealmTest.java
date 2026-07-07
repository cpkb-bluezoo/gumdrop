package org.bluezoo.gumdrop.auth;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link BasicRealm}.
 */
public class BasicRealmTest {

    private BasicRealm realm;

    @Before
    public void setUp() {
        realm = new BasicRealm();
        realm.passwords.put("alice", "secret123");
        realm.passwords.put("bob", "pass456");
        realm.defineGroup("admin");
        realm.defineGroup("user");
        realm.addToRole("alice", "admin");
        realm.addToRole("alice", "user");
        realm.addToRole("bob", "user");
    }

    @Test
    public void testPasswordMatchCorrect() {
        assertTrue(realm.passwordMatch("alice", "secret123"));
        assertTrue(realm.passwordMatch("bob", "pass456"));
    }

    @Test
    public void testPasswordMatchIncorrect() {
        assertFalse(realm.passwordMatch("alice", "wrong"));
        assertFalse(realm.passwordMatch("bob", "wrong"));
    }

    @Test
    public void testPasswordMatchUnknownUser() {
        assertFalse(realm.passwordMatch("unknown", "anything"));
    }

    @Test
    public void testPasswordMatchNullPassword() {
        assertFalse(realm.passwordMatch("alice", null));
    }

    @Test
    public void testUserExists() {
        assertTrue(realm.userExists("alice"));
        assertTrue(realm.userExists("bob"));
        assertFalse(realm.userExists("charlie"));
    }

    @Test
    public void testIsUserInRole() {
        assertTrue(realm.isUserInRole("alice", "admin"));
        assertTrue(realm.isUserInRole("alice", "user"));
        assertFalse(realm.isUserInRole("bob", "admin"));
        assertTrue(realm.isUserInRole("bob", "user"));
    }

    @Test
    public void testIsUserInRoleUnknownUser() {
        assertFalse(realm.isUserInRole("unknown", "admin"));
    }

    @Test
    public void testIsUserInRoleUnknownRole() {
        assertFalse(realm.isUserInRole("alice", "superadmin"));
    }

    @Test
    public void testGetDigestHA1() {
        String ha1 = realm.getDigestHA1("alice", "testrealm");
        assertNotNull(ha1);
        assertTrue(ha1.length() > 0);
        assertTrue(ha1.matches("[0-9a-f]+"));
    }

    @Test
    public void testGetDigestHA1UnknownUser() {
        assertNull(realm.getDigestHA1("unknown", "testrealm"));
    }

    @Test
    public void testGetDigestHA1Consistency() {
        String ha1a = realm.getDigestHA1("alice", "testrealm");
        String ha1b = realm.getDigestHA1("alice", "testrealm");
        assertEquals(ha1a, ha1b);
    }

    @Test
    public void testGetDigestHA1DifferentRealms() {
        String ha1a = realm.getDigestHA1("alice", "realm1");
        String ha1b = realm.getDigestHA1("alice", "realm2");
        assertNotEquals(ha1a, ha1b);
    }

    @Test
    public void testGetCramMD5Response() {
        String challenge = "PDE4OTYuNjk3MTcwOTUyQHBvc3RvZmZpY2UuZXhhbXBsZS5uZXQ+";
        String response = realm.getCramMD5Response("alice", challenge);
        assertNotNull(response);
    }

    @Test
    public void testGetCramMD5ResponseUnknownUser() {
        assertNull(realm.getCramMD5Response("unknown", "challenge"));
    }

    @Test
    public void testGetApopResponse() {
        String timestamp = "<1896.697170952@example.com>";
        String response = realm.getApopResponse("alice", timestamp);
        assertNotNull(response);
        assertTrue(response.matches("[0-9a-f]+"));
    }

    @Test
    public void testGetApopResponseConsistency() {
        String timestamp = "<1896.697170952@example.com>";
        String r1 = realm.getApopResponse("alice", timestamp);
        String r2 = realm.getApopResponse("alice", timestamp);
        assertEquals(r1, r2);
    }

    @Test
    public void testGetApopResponseUnknownUser() {
        assertNull(realm.getApopResponse("unknown", "<timestamp>"));
    }

    @Test
    public void testGetPassword() {
        assertEquals("secret123", realm.getPassword("alice"));
        assertNull(realm.getPassword("unknown"));
    }

    @Test
    public void testSupportedSASLMechanisms() {
        assertNotNull(realm.getSupportedSASLMechanisms());
        assertTrue(realm.getSupportedSASLMechanisms().contains(SASLMechanism.PLAIN));
        assertTrue(realm.getSupportedSASLMechanisms().contains(SASLMechanism.CRAM_MD5));
        assertTrue(realm.getSupportedSASLMechanisms().contains(SASLMechanism.DIGEST_MD5));
    }

    @Test
    public void testDefineGroupWithSeparateIdAndName() {
        realm.defineGroup("adminGrp", "administrators");
        realm.addToRole("bob", "administrators");
        assertTrue(realm.isUserInRole("bob", "administrators"));
    }

    @Test
    public void testGetScramCredentials() {
        assertNotNull(realm.getScramCredentials("alice"));
        assertNull(realm.getScramCredentials("unknown"));
    }

    @Test
    public void testCertFingerprintAuth() {
        realm.certFingerprints.put("ab:cd:ef:01:23", "certuser");
        assertTrue(realm.userExists("certuser"));
    }

    // ===== PBKDF2 hashed passwords =====

    @Test
    public void testPbkdf2HashRoundTrip() {
        String hash = BasicRealm.createPbkdf2Hash("hunter2");
        assertTrue(hash.startsWith("{PBKDF2}"));
        realm.passwords.put("carol", hash);
        assertTrue(realm.passwordMatch("carol", "hunter2"));
        assertFalse(realm.passwordMatch("carol", "wrong"));
        assertFalse(realm.passwordMatch("carol", null));
    }

    @Test
    public void testPbkdf2HashUsesRandomSalt() {
        // Two hashes of the same password must differ (random salt).
        String h1 = BasicRealm.createPbkdf2Hash("samePassword");
        String h2 = BasicRealm.createPbkdf2Hash("samePassword");
        assertNotEquals(h1, h2);
    }

    @Test
    public void testPbkdf2KnownVector() {
        // Deterministic salt/iterations for a stable verification vector.
        byte[] salt = new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};
        String hash = BasicRealm.createPbkdf2Hash("correct horse", salt, 1000);
        realm.passwords.put("dave", hash);
        assertTrue(realm.passwordMatch("dave", "correct horse"));
        assertFalse(realm.passwordMatch("dave", "battery staple"));
    }

    @Test
    public void testMalformedPbkdf2HashRejected() {
        realm.passwords.put("eve", "{PBKDF2}not-a-valid-spec");
        assertFalse(realm.passwordMatch("eve", "anything"));
    }

    @Test
    public void testScramCredentialsAreCached() {
        // Salt must be stable across calls so clients can cache SaltedPassword
        // (RFC 5802 §5.1). A fresh random salt is generated once and reused.
        Realm.ScramCredentials c1 = realm.getScramCredentials("alice");
        Realm.ScramCredentials c2 = realm.getScramCredentials("alice");
        assertNotNull(c1);
        assertNotNull(c2);
        assertEquals("SCRAM salt must be stable across calls for the same user", c1.salt, c2.salt);
    }

    @Test
    public void testForSelectorLoopReturnsSelf() {
        assertSame(realm, realm.forSelectorLoop(null));
    }
}
