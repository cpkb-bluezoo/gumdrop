/*
 * POP3Example.java
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

import org.bluezoo.gumdrop.Realm;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.pop3.FilesystemMailboxFactory;
import org.bluezoo.gumdrop.pop3.POP3Listener;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.security.Principal;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Example demonstrating how to configure and run a POP3 server.
 * 
 * This example shows:
 * - Setting up a simple in-memory realm for authentication
 * - Configuring a filesystem-based mailbox
 * - Starting both POP3 (port 110) and POP3S (port 995) servers
 * - Creating sample messages for testing
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class POP3Example {

    /**
     * Simple in-memory realm for testing.
     */
    static class SimpleRealm implements Realm {
        private final Map<String, String> users = new HashMap<>();

        public void addUser(String username, String password) {
            users.put(username, password);
        }

        @Override
        public boolean authenticate(String username, String password) {
            String storedPassword = users.get(username);
            return storedPassword != null && storedPassword.equals(password);
        }

        @Override
        public String getPassword(String username) {
            return users.get(username);
        }

        @Override
        public List<Principal> getRoles(String username) {
            return Arrays.asList(new Principal() {
                public String getName() { return "user"; }
            });
        }
    }

    public static void main(String[] args) throws Exception {
        // Create mailbox directory
        File mailboxBase = new File("mailboxes");
        if (!mailboxBase.exists()) {
            mailboxBase.mkdirs();
        }

        // Create sample messages for testing
        createSampleMessages(mailboxBase);

        // Create realm with test users
        SimpleRealm realm = new SimpleRealm();
        realm.addUser("alice", "password123");
        realm.addUser("bob", "secret456");

        // Create mailbox factory
        FilesystemMailboxFactory mailboxFactory = new FilesystemMailboxFactory(mailboxBase);

        // Configure POP3 server (port 110, plaintext with STARTTLS)
        POP3Listener pop3Server = new POP3Listener();
        pop3Server.setPort(110);
        pop3Server.setRealm(realm);
        pop3Server.setMailboxFactory(mailboxFactory);
        pop3Server.setLoginDelay(2000); // 2 second delay after failed auth
        pop3Server.setEnableAPOP(true);
        pop3Server.setEnableUTF8(true);
        
        // Optional: Configure TLS for STARTTLS support
        // pop3Server.setKeystoreFile("/path/to/keystore.p12");
        // pop3Server.setKeystorePass("keystorePassword");

        // Configure POP3S server (port 995, implicit TLS)
        POP3Listener pop3sServer = new POP3Listener();
        pop3sServer.setPort(995);
        pop3sServer.setSecure(true); // Implicit TLS
        pop3sServer.setRealm(realm);
        pop3sServer.setMailboxFactory(mailboxFactory);
        
        // Required for POP3S
        // pop3sServer.setKeystoreFile("/path/to/keystore.p12");
        // pop3sServer.setKeystorePass("keystorePassword");

        // Create and start selector loop
        SelectorLoop loop = new SelectorLoop(Arrays.asList(pop3Server, pop3sServer));
        
        System.out.println("POP3 Example Server Started");
        System.out.println("============================");
        System.out.println("POP3  server on port 110 (cleartext/STARTTLS)");
        System.out.println("POP3S server on port 995 (implicit TLS)");
        System.out.println();
        System.out.println("Test users:");
        System.out.println("  alice / password123");
        System.out.println("  bob   / secret456");
        System.out.println();
        System.out.println("Mailbox directory: " + mailboxBase.getAbsolutePath());
        System.out.println();
        System.out.println("Test with telnet:");
        System.out.println("  telnet localhost 110");
        System.out.println("  USER alice");
        System.out.println("  PASS password123");
        System.out.println("  STAT");
        System.out.println("  LIST");
        System.out.println("  RETR 1");
        System.out.println("  QUIT");
        System.out.println();
        
        loop.run();
    }

    /**
     * Creates sample email messages for testing.
     */
    private static void createSampleMessages(File mailboxBase) throws IOException {
        // Create sample messages for alice
        File aliceDir = new File(mailboxBase, "alice");
        aliceDir.mkdirs();
        
        createMessage(new File(aliceDir, "1.eml"),
            "From: bob@example.com\r\n" +
            "To: alice@example.com\r\n" +
            "Subject: Test Message 1\r\n" +
            "Date: Mon, 1 Jan 2024 12:00:00 +0000\r\n" +
            "Message-ID: <msg1@example.com>\r\n" +
            "\r\n" +
            "This is the first test message.\r\n" +
            "It has multiple lines.\r\n" +
            "..and some lines start with dots.\r\n"
        );
        
        createMessage(new File(aliceDir, "2.eml"),
            "From: system@example.com\r\n" +
            "To: alice@example.com\r\n" +
            "Subject: Welcome to POP3!\r\n" +
            "Date: Tue, 2 Jan 2024 14:30:00 +0000\r\n" +
            "Message-ID: <msg2@example.com>\r\n" +
            "\r\n" +
            "Welcome to the gumdrop POP3 server!\r\n" +
            "\r\n" +
            "This is a test message to demonstrate the POP3 implementation.\r\n"
        );

        // Create sample messages for bob
        File bobDir = new File(mailboxBase, "bob");
        bobDir.mkdirs();
        
        createMessage(new File(bobDir, "1.eml"),
            "From: alice@example.com\r\n" +
            "To: bob@example.com\r\n" +
            "Subject: Hello Bob\r\n" +
            "Date: Wed, 3 Jan 2024 09:15:00 +0000\r\n" +
            "Message-ID: <msg3@example.com>\r\n" +
            "\r\n" +
            "Hi Bob,\r\n" +
            "\r\n" +
            "This is a message for you.\r\n" +
            "\r\n" +
            "Best regards,\r\n" +
            "Alice\r\n"
        );
    }

    /**
     * Creates a message file with the given content.
     */
    private static void createMessage(File file, String content) throws IOException {
        if (!file.exists()) {
            try (FileWriter writer = new FileWriter(file)) {
                writer.write(content);
            }
        }
    }

}

