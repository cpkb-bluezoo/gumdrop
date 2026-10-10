/*
 * BasicRealm.java
 * Copyright (C) 2005 Chris Burdess
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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.security.spec.InvalidKeySpecException;
import java.text.MessageFormat;
import java.util.Base64;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.ResourceBundle;
import java.util.logging.Logger;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.util.AbstractXMLHandler;
import org.bluezoo.gumdrop.util.XMLParseUtils;
import org.bluezoo.util.ByteArrays;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;

/**
 * Simple realm composed of static principals declared in an XML
 * configuration resource.
 *
 * <p><strong>Production use:</strong> BasicRealm with plaintext passwords is
 * intended for development and testing only. For production, use
 * {@link org.bluezoo.gumdrop.auth.ldap.LdapRealm} or {@link org.bluezoo.gumdrop.auth.oauth.OAuthRealm}. When hashed passwords are used
 * (RFC 2307 format), only PLAIN and LOGIN SASL mechanisms are supported.
 *
 * <h4>Hashed Passwords</h4>
 * <p>The recommended storage format is {@code {PBKDF2}}, a salted, iterated
 * hash ({@code PBKDF2WithHmacSHA256}) generated with
 * {@link #createPbkdf2Hash(String)}. The encoding is
 * {@code {PBKDF2}<iterations>$<base64-salt>$<base64-hash>}.
 *
 * <p>The LDAP-style RFC 2307 formats {@code {SHA256}} and {@code {SSHA256}}
 * are also accepted (values can be exported from LDAP {@code userPassword}
 * directly), but are <strong>weak</strong>: {@code {SHA256}} is unsalted and
 * both are single-iteration fast hashes vulnerable to offline brute-force
 * attacks. Prefer {@code {PBKDF2}}. The SHA-1 formats {@code {SHA}} and
 * {@code {SSHA}} are not supported.
 *
 * <p>Hashed users support only PLAIN and LOGIN; SCRAM and HTTP Digest
 * require plaintext.
 *
 * <h4>XML Format</h4>
 * <p>The realm XML file supports two formats for defining groups:</p>
 * 
 * <h5>1. Independent Groups (Recommended)</h5>
 * <p>Groups are defined with both an {@code id} (for XML linking via IDREFS)
 * and a {@code name} (the role name checked by {@link #isUserInRole}):</p>
 * <pre>{@code
 * <realm>
 *   <!-- Define groups with id for linking and name for role checking -->
 *   <group id="ftpAdminGroup" name="ftp-admin"/>
 *   <group id="ftpWriteGroup" name="ftp-write"/>
 *   <group id="ftpReadGroup" name="ftp-read"/>
 *   
 *   <!-- Users reference groups by id using IDREFS syntax -->
 *   <user name="admin" password="secret" groups="ftpAdminGroup"/>
 *   <user name="uploader" password="upload123" groups="ftpWriteGroup ftpReadGroup"/>
 *   <user name="guest" password="guest" groups="ftpReadGroup"/>
 * </realm>
 * }</pre>
 * 
 * <p>Note: The {@code id} attribute must be a valid XML ID (no special characters,
 * cannot start with a number). The {@code name} attribute is the actual role name
 * used in authorization checks and can contain any characters.</p>
 * 
 * <h5>2. Nested Groups (Legacy)</h5>
 * <p>Groups contain member users (backward compatible). In this case, the
 * {@code name} attribute serves as both the group identifier and role name:</p>
 * <pre>{@code
 * <realm>
 *   <group name="admin">
 *     <user name="root" password="secret"/>
 *   </group>
 * </realm>
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc4422">RFC 4422: SASL Framework</a>
 */
public class BasicRealm extends AbstractXMLHandler implements SynchronousRealm {

    static final ResourceBundle L10N = ResourceBundle.getBundle("org.bluezoo.gumdrop.auth.L10N");
    static final Logger LOGGER = Logger.getLogger(BasicRealm.class.getName());

    /** Source of cryptographically strong randomness for salt generation. */
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /** PBKDF2 iteration count for on-demand SCRAM credential derivation (OWASP 2023). */
    private static final int SCRAM_ITERATIONS = 210_000;

    /** Cache of derived SCRAM credentials keyed by username. Invalidated on password change. */
    private final Map<String, Realm.ScramCredentials> scramCache = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Users and their passwords.
     */
    Map<String, String> passwords;

    /**
     * Certificate SHA-256 fingerprints mapped to usernames.
     * Keys are colon-separated hex fingerprints (e.g. "ab:cd:ef:..."),
     * values are the corresponding usernames.
     */
    Map<String, String> certFingerprints;

    /**
     * Users to set of role names (the 'name' attribute from groups).
     */
    Map<String, Set<String>> userRoles;

    /**
     * Maps group id to group name (role name).
     * The id is used for XML IDREFS linking, the name is the role for authorization.
     */
    private Map<String, String> groupIdToName;
    
    /**
     * Current group name during parsing (for nested user elements).
     */
    private transient String currentGroupName;
    
    /**
     * Pending group references to resolve after parsing.
     * Maps username to unparsed groups string (space-separated group ids).
     */
    private transient Map<String, String> pendingGroupRefs;

    public BasicRealm() {
        passwords = new LinkedHashMap<String, String>();
        certFingerprints = new LinkedHashMap<String, String>();
        userRoles = new LinkedHashMap<String, Set<String>>();
        groupIdToName = new LinkedHashMap<String, String>();
    }

    @Override
    public boolean passwordMatch(String username, String password) {
        String stored = passwords.get(username);
        if (stored == null || password == null) {
            return false;
        }
        if (isHashedPassword(stored)) {
            return verifyHashedPassword(stored, password);
        }
        return MessageDigest.isEqual(
                stored.getBytes(StandardCharsets.UTF_8),
                password.getBytes(StandardCharsets.UTF_8));
    }

    /** Default PBKDF2 iteration count for newly generated hashes. */
    private static final int PBKDF2_DEFAULT_ITERATIONS = 210000;

    private static boolean isHashedPassword(String stored) {
        return stored.startsWith("{PBKDF2}") || stored.startsWith("{SHA256}")
                || stored.startsWith("{SSHA256}");
    }

    private static boolean verifyHashedPassword(String stored, String password) {
        try {
            if (stored.startsWith("{PBKDF2}")) {
                return verifyPbkdf2(stored.substring(8), password);
            } else if (stored.startsWith("{SHA256}")) {
                byte[] storedDigest = Base64.getDecoder().decode(stored.substring(8));
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                byte[] computed = md.digest(password.getBytes(StandardCharsets.UTF_8));
                return storedDigest.length == 32 && MessageDigest.isEqual(storedDigest, computed);
            } else if (stored.startsWith("{SSHA256}")) {
                byte[] decoded = Base64.getDecoder().decode(stored.substring(9));
                if (decoded.length < 33) {
                    return false;
                }
                byte[] digest = new byte[32];
                byte[] salt = new byte[decoded.length - 32];
                System.arraycopy(decoded, 0, digest, 0, 32);
                System.arraycopy(decoded, 32, salt, 0, salt.length);
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                md.update(password.getBytes(StandardCharsets.UTF_8));
                md.update(salt);
                byte[] computed = md.digest();
                return MessageDigest.isEqual(digest, computed);
            }
        } catch (IllegalArgumentException | NoSuchAlgorithmException e) {
            return false;
        }
        return false;
    }

    /**
     * Verifies a password against a {@code {PBKDF2}} hash.
     *
     * <p>The encoded specification (without the {@code {PBKDF2}} prefix) has
     * the form {@code <iterations>$<base64-salt>$<base64-hash>} using
     * {@code PBKDF2WithHmacSHA256}.
     *
     * @param spec the encoded specification (prefix already stripped)
     * @param password the plaintext password to verify
     * @return true if the password matches
     */
    private static boolean verifyPbkdf2(String spec, String password) {
        try {
            int i1 = spec.indexOf('$');
            int i2 = spec.indexOf('$', i1 + 1);
            if (i1 < 0 || i2 < 0) {
                return false;
            }
            int iterations = Integer.parseInt(spec.substring(0, i1));
            if (iterations < 1) {
                return false;
            }
            byte[] salt = Base64.getDecoder().decode(spec.substring(i1 + 1, i2));
            byte[] expected = Base64.getDecoder().decode(spec.substring(i2 + 1));
            byte[] computed = pbkdf2(password, salt, iterations, expected.length * 8);
            return MessageDigest.isEqual(expected, computed);
        } catch (IllegalArgumentException | NoSuchAlgorithmException
                | InvalidKeySpecException e) {
            return false;
        }
    }

    private static byte[] pbkdf2(String password, byte[] salt, int iterations,
            int keyLengthBits) throws NoSuchAlgorithmException, InvalidKeySpecException {
        PBEKeySpec keySpec = new PBEKeySpec(
                password.toCharArray(), salt, iterations, keyLengthBits);
        try {
            SecretKeyFactory skf =
                    SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return skf.generateSecret(keySpec).getEncoded();
        } finally {
            keySpec.clearPassword();
        }
    }

    /**
     * Produces a salted PBKDF2 password hash in the {@code {PBKDF2}} format
     * understood by {@link #passwordMatch}. This is the recommended format
     * for storing passwords in a realm configuration.
     *
     * @param password the plaintext password
     * @return the encoded {@code {PBKDF2}} hash string
     */
    public static String createPbkdf2Hash(String password) {
        byte[] salt = new byte[16];
        SECURE_RANDOM.nextBytes(salt);
        return createPbkdf2Hash(password, salt, PBKDF2_DEFAULT_ITERATIONS);
    }

    static String createPbkdf2Hash(String password, byte[] salt, int iterations) {
        try {
            byte[] hash = pbkdf2(password, salt, iterations, 256);
            Base64.Encoder enc = Base64.getEncoder();
            return "{PBKDF2}" + iterations + "$" + enc.encodeToString(salt)
                    + "$" + enc.encodeToString(hash);
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new RuntimeException("PBKDF2WithHmacSHA256 not available", e);
        }
    }

    @Override
    public String getDigestHA1(String username, String realmName) {
        String password = passwords.get(username);
        if (password == null || isHashedPassword(password)) {
            return null; // User doesn't exist or hashed (requires plaintext)
        }

        try {
            // Compute SHA-256(username:realm:password) (RFC 7616 section 3.4.2)
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(username.getBytes(StandardCharsets.US_ASCII));
            md.update((byte) ':');
            md.update(realmName.getBytes(StandardCharsets.US_ASCII));
            md.update((byte) ':');
            md.update(password.getBytes(StandardCharsets.US_ASCII));
            
            byte[] hash = md.digest();
            return ByteArrays.toHexString(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required of every JRE
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    @Override
    public boolean supportsDigestHA1() {
        return true;
    }

    @Override
    public boolean isUserInRole(String username, String role) {
        Set<String> roles = userRoles.get(username);
        return (roles != null && roles.contains(role));
    }

    @Override
    public boolean userExists(String username) {
        if (passwords.containsKey(username)) {
            return true;
        }
        return certFingerprints.containsValue(username);
    }

    /**
     * Supported SASL mechanisms for BasicRealm.
     * Since BasicRealm stores plaintext passwords, it supports all
     * password-based mechanisms.
     */
    private static final Set<SaslMechanism> SUPPORTED_MECHANISMS =
            Collections.unmodifiableSet(EnumSet.of(
                    SaslMechanism.PLAIN,
                    SaslMechanism.LOGIN,
                    SaslMechanism.SCRAM_SHA_256,
                    SaslMechanism.EXTERNAL
            ));

    @Override
    public Set<SaslMechanism> getSupportedSASLMechanisms() {
        return SUPPORTED_MECHANISMS;
    }

    /**
     * Returns a view of this realm for a connection's loop. Lookups are in
     * memory and complete at once, except a password check against a hashed
     * password and the first derivation of a user's SCRAM credentials, which
     * run a deliberately slow key derivation: those run on the
     * {@link org.bluezoo.gumdrop.StorageExecutor} and complete on the loop,
     * so the loop thread never does the work. Used directly, without a
     * loop, they run on the calling thread.
     */
    @Override
    public Realm forSelectorLoop(SelectorLoop loop) {
        if (loop == null) {
            return this;
        }
        return new LoopBound(loop);
    }

    /** True if checking this user's password runs a slow hash. */
    private boolean passwordCheckIsSlow(String username) {
        String stored = passwords.get(username);
        return stored != null && isHashedPassword(stored);
    }

    /** True if this user's SCRAM credentials are plaintext-derived and not yet cached. */
    private boolean scramDerivationIsPending(String username) {
        String password = passwords.get(username);
        return password != null && !isHashedPassword(password)
                && !scramCache.containsKey(username);
    }

    /** This realm as seen from one loop; see {@link #forSelectorLoop}. */
    private final class LoopBound implements Realm {

        private final SelectorLoop loop;

        LoopBound(SelectorLoop loop) {
            this.loop = loop;
        }

        /** Runs {@code work} on the storage executor and completes on the loop. */
        private <T> void offload(Callable<T> work, final RealmCallback<T> callback) {
            org.bluezoo.gumdrop.Gumdrop gumdrop = loop.getGumdrop();
            org.bluezoo.gumdrop.StorageExecutor executor =
                    (gumdrop != null) ? gumdrop.getStorageExecutor() : null;
            if (executor == null) {
                T result;
                try {
                    result = work.call();
                } catch (Exception e) {
                    callback.failed(e);
                    return;
                }
                callback.completed(result);
                return;
            }
            executor.submit(new Executor() {
                @Override
                public void execute(Runnable task) {
                    loop.invokeLater(task);
                }
            }, work, new org.bluezoo.gumdrop.StorageExecutor.Callback<T>() {
                @Override
                public void completed(T result) {
                    callback.completed(result);
                }

                @Override
                public void failed(Throwable cause) {
                    callback.failed(cause);
                }
            });
        }

        @Override
        public Realm forSelectorLoop(SelectorLoop other) {
            return BasicRealm.this.forSelectorLoop(other);
        }

        @Override
        public Set<SaslMechanism> getSupportedSASLMechanisms() {
            return BasicRealm.this.getSupportedSASLMechanisms();
        }

        @Override
        public void passwordMatch(final String username, final String password,
                RealmCallback<Boolean> callback) {
            if (!passwordCheckIsSlow(username)) {
                BasicRealm.this.passwordMatch(username, password, callback);
                return;
            }
            offload(new Callable<Boolean>() {
                @Override
                public Boolean call() {
                    return Boolean.valueOf(
                            BasicRealm.this.passwordMatch(username, password));
                }
            }, callback);
        }

        @Override
        public void getScramCredentials(final String username,
                RealmCallback<Realm.ScramCredentials> callback) {
            if (!scramDerivationIsPending(username)) {
                BasicRealm.this.getScramCredentials(username, callback);
                return;
            }
            offload(new Callable<Realm.ScramCredentials>() {
                @Override
                public Realm.ScramCredentials call() {
                    return BasicRealm.this.getScramCredentials(username);
                }
            }, callback);
        }

        @Override
        public void getDigestHA1(String username, String realmName,
                RealmCallback<String> callback) {
            BasicRealm.this.getDigestHA1(username, realmName, callback);
        }

        @Override
        public boolean supportsDigestHA1() {
            return BasicRealm.this.supportsDigestHA1();
        }

        @Override
        public void isUserInRole(String username, String role,
                RealmCallback<Boolean> callback) {
            BasicRealm.this.isUserInRole(username, role, callback);
        }

        @Override
        public void userExists(String username, RealmCallback<Boolean> callback) {
            BasicRealm.this.userExists(username, callback);
        }

        @Override
        public void mapKerberosPrincipal(String gssName,
                RealmCallback<String> callback) {
            BasicRealm.this.mapKerberosPrincipal(gssName, callback);
        }
    }

    @Override
    public ScramCredentials getScramCredentials(String username) {
        String password = passwords.get(username);
        if (password == null || isHashedPassword(password)) {
            return null; // User doesn't exist or hashed (requires plaintext)
        }
        // Return a cached derivation so the salt remains stable across exchanges
        // (RFC 5802 §5.1: clients may cache SaltedPassword keyed on salt+iterations).
        // The cache entry is keyed by username; a password change must invalidate it.
        Realm.ScramCredentials cached = scramCache.get(username);
        if (cached != null) {
            return cached;
        }
        byte[] salt = new byte[16];
        SECURE_RANDOM.nextBytes(salt);
        Realm.ScramCredentials creds = Realm.ScramCredentials.derive(password, salt, SCRAM_ITERATIONS, "SHA-256");
        scramCache.put(username, creds);
        return creds;
    }

    @Override
    public CertificateAuthenticationResult authenticateCertificate(
            X509Certificate certificate) {
        if (certFingerprints.isEmpty()) {
            return null;
        }
        String fingerprint = computeSHA256Fingerprint(certificate);
        if (fingerprint == null) {
            return CertificateAuthenticationResult.failure();
        }
        String username = certFingerprints.get(fingerprint);
        if (username != null) {
            return CertificateAuthenticationResult.success(username);
        }
        return CertificateAuthenticationResult.failure();
    }

    static String computeSHA256Fingerprint(X509Certificate certificate) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(certificate.getEncoded());
            StringBuilder sb = new StringBuilder(digest.length * 3 - 1);
            for (int i = 0; i < digest.length; i++) {
                if (i > 0) {
                    sb.append(':');
                }
                sb.append(String.format("%02x", digest[i] & 0xff));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException | CertificateEncodingException e) {
            LOGGER.warning(MessageFormat.format(
                    L10N.getString("warn.cert_fingerprint_failed"), e.getMessage()));
            return null;
        }
    }

    public BasicRealm href(String href) {
        pendingGroupRefs = new LinkedHashMap<String, String>();
        try {
            URL cwd = new File(".").toURI().toURL();
            URL url = new URL(cwd, href);
            XMLParseUtils.parseURL(url, this);
            
            // Resolve pending group references after parsing
            resolvePendingGroupReferences();
            logPlaintextPasswordWarning();
            
        } catch (IOException | SAXException e) {
            RuntimeException e2 = new RuntimeException("Failed to parse realm configuration: " + href);
            e2.initCause(e);
            throw e2;
        } finally {
            currentGroupName = null;
            pendingGroupRefs = null;
        }
        return this;
    }

    public BasicRealm href(Path path) {
        pendingGroupRefs = new LinkedHashMap<String, String>();
        try {
            try (InputStream in = Files.newInputStream(path)) {
                XMLParseUtils.parseStream(in, this, path.toUri().toString(), null);
            }
            resolvePendingGroupReferences();
            logPlaintextPasswordWarning();
        } catch (IOException | SAXException e) {
            RuntimeException e2 = new RuntimeException("Failed to parse realm configuration: " + path);
            e2.initCause(e);
            throw e2;
        } finally {
            currentGroupName = null;
            pendingGroupRefs = null;
        }
        return this;
    }

    public void startElement(String uri, String localName, String qName, Attributes atts)
            throws SAXException {
        if ("user".equals(qName)) {
            String username = atts.getValue("name");
            String password = atts.getValue("password");
            String groups = atts.getValue("groups");
            String certFp = atts.getValue("cert-fingerprint");
            
            if (password != null) {
                passwords.put(username, password);
                scramCache.remove(username);
            }
            
            if (certFp != null && !certFp.isEmpty()) {
                // Strip optional "SHA-256:" prefix, normalize to lowercase
                String fp = certFp;
                if (fp.startsWith("SHA-256:")) {
                    fp = fp.substring(8);
                }
                certFingerprints.put(fp.toLowerCase(), username);
            }
            
            // If user is nested in a group, add to that group's role
            if (currentGroupName != null) {
                addUserToRole(username, currentGroupName);
            }
            
            // If user has groups attribute (IDREFS), queue for resolution
            if (groups != null && !groups.trim().isEmpty()) {
                pendingGroupRefs.put(username, groups);
            }
            
        } else if ("group".equals(qName)) {
            // id is for XML IDREFS linking, name is the role for authorization
            String groupId = atts.getValue("id");
            String groupName = atts.getValue("name");
            
            if (groupId != null && groupName != null) {
                // New style: id for linking, name for role checking
                groupIdToName.put(groupId, groupName);
            } else if (groupId != null) {
                // id only - use id as the role name too
                groupIdToName.put(groupId, groupId);
            } else if (groupName != null) {
                // Legacy style: name serves as both id and role name
                groupIdToName.put(groupName, groupName);
                currentGroupName = groupName;
            }
            
        } else if ("member".equals(qName)) {
            // Legacy: add member (user) to current group
            String memberName = atts.getValue("name");
            if (currentGroupName != null && memberName != null) {
                addUserToRole(memberName, currentGroupName);
            }
        }
    }
    
    @Override
    public void endElement(String uri, String localName, String qName) throws SAXException {
        if ("group".equals(qName)) {
            currentGroupName = null;
        }
    }
    
    /**
     * Adds a user to a role.
     * 
     * @param username the username
     * @param roleName the role name to add
     */
    private void addUserToRole(String username, String roleName) {
        Set<String> roles = userRoles.get(username);
        if (roles == null) {
            roles = new LinkedHashSet<String>();
            userRoles.put(username, roles);
        }
        roles.add(roleName);
    }
    
    private void logPlaintextPasswordWarning() {
        for (String pwd : passwords.values()) {
            if (pwd != null && !isHashedPassword(pwd)) {
                LOGGER.warning(L10N.getString("warn.plaintext_passwords"));
                return;
            }
        }
    }

    /**
     * Resolves pending group references (IDREFS syntax: "groupId1 groupId2").
     * Looks up the group id to find the corresponding role name.
     */
    private void resolvePendingGroupReferences() {
        if (pendingGroupRefs == null) {
            return;
        }
        
        for (Map.Entry<String, String> entry : pendingGroupRefs.entrySet()) {
            String username = entry.getKey();
            String groupsAttr = entry.getValue();
            
            // Parse space-separated group id references (IDREFS format)
            int idStart = 0;
            int attrLen = groupsAttr.length();
            while (idStart < attrLen) {
                // Skip whitespace
                while (idStart < attrLen && Character.isWhitespace(groupsAttr.charAt(idStart))) {
                    idStart++;
                }
                if (idStart >= attrLen) {
                    break;
                }
                // Find end of token
                int idEnd = idStart;
                while (idEnd < attrLen && !Character.isWhitespace(groupsAttr.charAt(idEnd))) {
                    idEnd++;
                }
                String groupId = groupsAttr.substring(idStart, idEnd);
                idStart = idEnd;
                
                // Look up the role name for this group id
                String roleName = groupIdToName.get(groupId);
                if (roleName == null) {
                    String msg = MessageFormat.format(L10N.getString("warn.undefined_group"), username, groupId);
                    LOGGER.warning(msg);
                    // Use the id as the role name as fallback
                    roleName = groupId;
                }
                
                addUserToRole(username, roleName);
            }
        }
    }
    
    /**
     * Programmatically defines a group with the same id and name.
     * 
     * @param groupName the group name (used as both id and role name)
     */
    public void defineGroup(String groupName) {
        groupIdToName.put(groupName, groupName);
    }
    
    /**
     * Programmatically defines a group with separate id and name.
     * 
     * @param groupId the group id (for linking)
     * @param roleName the role name (for authorization checks)
     */
    public void defineGroup(String groupId, String roleName) {
        groupIdToName.put(groupId, roleName);
    }
    
    /**
     * Programmatically adds a user to a role.
     * 
     * @param username the username
     * @param roleName the role name to add
     */
    public void addToRole(String username, String roleName) {
        addUserToRole(username, roleName);
    }

}
