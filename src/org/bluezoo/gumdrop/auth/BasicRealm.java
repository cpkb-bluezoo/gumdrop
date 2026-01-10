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
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.text.MessageFormat;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.ResourceBundle;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.util.XMLParseUtils;
import org.bluezoo.util.ByteArrays;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Simple realm composed of static principals declared in an XML
 * configuration resource.
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
 */
public class BasicRealm extends DefaultHandler implements Realm {

    static final ResourceBundle L10N = ResourceBundle.getBundle("org.bluezoo.gumdrop.auth.L10N");
    static final Logger LOGGER = Logger.getLogger(BasicRealm.class.getName());

    /**
     * Users and their passwords.
     */
    Map<String, String> passwords;

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
        userRoles = new LinkedHashMap<String, Set<String>>();
        groupIdToName = new LinkedHashMap<String, String>();
    }

    @Override
    public boolean passwordMatch(String username, String password) {
        String storedPassword = passwords.get(username);
        return storedPassword != null && storedPassword.equals(password);
    }

    @Override
    public String getDigestHA1(String username, String realmName) {
        String password = passwords.get(username);
        if (password == null) {
            return null; // User doesn't exist
        }

        try {
            // Compute MD5(username:realm:password)
            MessageDigest md = MessageDigest.getInstance("MD5");
            md.update(username.getBytes(StandardCharsets.US_ASCII));
            md.update((byte) ':');
            md.update(realmName.getBytes(StandardCharsets.US_ASCII));
            md.update((byte) ':');
            md.update(password.getBytes(StandardCharsets.US_ASCII));
            
            byte[] hash = md.digest();
            return ByteArrays.toHexString(hash);
        } catch (NoSuchAlgorithmException e) {
            // MD5 should always be available
            throw new RuntimeException("MD5 algorithm not available", e);
        }
    }

    @Override
    @Deprecated
    public String getPassword(String username) {
        return passwords.get(username);
    }

    @Override
    public boolean isUserInRole(String username, String role) {
        Set<String> roles = userRoles.get(username);
        return (roles != null && roles.contains(role));
    }

    @Override
    public boolean userExists(String username) {
        return passwords.containsKey(username);
    }

    /**
     * Supported SASL mechanisms for BasicRealm.
     * Since BasicRealm stores plaintext passwords, it supports all
     * password-based mechanisms.
     */
    private static final Set<SASLMechanism> SUPPORTED_MECHANISMS =
            Collections.unmodifiableSet(EnumSet.of(
                    SASLMechanism.PLAIN,
                    SASLMechanism.LOGIN,
                    SASLMechanism.CRAM_MD5,
                    SASLMechanism.DIGEST_MD5,
                    SASLMechanism.SCRAM_SHA_256,
                    SASLMechanism.EXTERNAL
            ));

    @Override
    public Set<SASLMechanism> getSupportedSASLMechanisms() {
        return SUPPORTED_MECHANISMS;
    }

    @Override
    public Realm forSelectorLoop(SelectorLoop loop) {
        // BasicRealm is synchronous and doesn't need client connections
        return this;
    }

    @Override
    public String getCramMD5Response(String username, String challenge) {
        String password = passwords.get(username);
        if (password == null) {
            return null; // User doesn't exist
        }
        return SASLUtils.computeCramMD5Response(password, challenge);
    }

    @Override
    public String getApopResponse(String username, String timestamp) {
        String password = passwords.get(username);
        if (password == null) {
            return null; // User doesn't exist
        }
        // APOP uses MD5(timestamp + password)
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            md.update(timestamp.getBytes(StandardCharsets.US_ASCII));
            md.update(password.getBytes(StandardCharsets.US_ASCII));
            return ByteArrays.toHexString(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(L10N.getString("err.no_md5"), e);
        }
    }

    @Override
    public ScramCredentials getScramCredentials(String username) {
        String password = passwords.get(username);
        if (password == null) {
            return null; // User doesn't exist
        }
        // Generate credentials on demand with a consistent salt derived from username
        // In production, credentials should be pre-computed and stored
        byte[] salt = new byte[16];
        new SecureRandom(username.getBytes(StandardCharsets.UTF_8)).nextBytes(salt);
        return ScramCredentials.derive(password, salt, 4096, "SHA-256");
    }

    public void setHref(String href) {
        pendingGroupRefs = new LinkedHashMap<String, String>();
        try {
            URL cwd = new File(".").toURL();
            URL url = new URL(cwd, href);
            XMLParseUtils.parseURL(url, this, null);
            
            // Resolve pending group references after parsing
            resolvePendingGroupReferences();
            
        } catch (IOException | SAXException e) {
            RuntimeException e2 = new RuntimeException("Failed to parse realm configuration: " + href);
            e2.initCause(e);
            throw e2;
        } finally {
            currentGroupName = null;
            pendingGroupRefs = null;
        }
    }

    public void startElement(String uri, String localName, String qName, Attributes atts)
            throws SAXException {
        if ("user".equals(qName)) {
            String username = atts.getValue("name");
            String password = atts.getValue("password");
            String groups = atts.getValue("groups");
            
            passwords.put(username, password);
            
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
