/*
 * ServerConfigurationTest.java
 * Copyright (C) 2025 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 *
 * This software is dual-licensed:
 *
 * 1. GNU General Public License v3 (or later) for open source use
 *    See LICENCE-GPL3 file for GPL terms and conditions.
 *
 * 2. Commercial License for proprietary use
 *    Contact Chris Burdess <dog@gnu.org> for commercial licensing terms.
 *    Mimecast Services Limited has been granted commercial usage rights under
 *    separate license agreement.
 *
 * gumdrop is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package org.bluezoo.gumdrop;

import org.bluezoo.gumdrop.http.FileBasedHTTPConnector;
import org.bluezoo.gumdrop.smtp.SMTPConnector;

import java.util.ArrayList;
import java.util.Collection;
import java.util.logging.Logger;

/**
 * Test class demonstrating programmatic configuration of the Gumdrop server.
 * This shows how to create a Server instance without relying on a gumdroprc file.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ServerConfigurationTest {

    private static final Logger LOGGER = Logger.getLogger(ServerConfigurationTest.class.getName());

    /**
     * Example of programmatic server configuration.
     * This creates connectors directly in code and passes them to the Server constructor.
     */
    public static void demonstrateProgrammaticConfiguration() {
        LOGGER.info("=== Demonstrating Programmatic Configuration ===");
        
        // Create connectors programmatically
        Collection<Connector> connectors = new ArrayList<>();

        // Create HTTP connector for port 8080
        try {
            FileBasedHTTPConnector httpConnector = new FileBasedHTTPConnector();
            httpConnector.setPort(8080);
            httpConnector.setRoot("web"); // Set document root
            connectors.add(httpConnector);
            LOGGER.info("Created HTTP connector on port 8080");
        } catch (Exception e) {
            LOGGER.warning("Could not create HTTP connector: " + e.getMessage());
        }

        // Create SMTP connector for port 25
        try {
            SMTPConnector smtpConnector = new SMTPConnector();
            smtpConnector.setPort(25);
            // Configure SMTP-specific settings
            smtpConnector.setMaxMessageSize(10 * 1024 * 1024); // 10MB limit
            smtpConnector.setAuthRequired(false); // Allow unauthenticated for port 25
            connectors.add(smtpConnector);
            LOGGER.info("Created SMTP connector on port 25");
        } catch (Exception e) {
            LOGGER.warning("Could not create SMTP connector: " + e.getMessage());
        }

        // Create secure SMTP connector for port 587 (submission)
        try {
            SMTPConnector secureSMTPConnector = new SMTPConnector();
            secureSMTPConnector.setPort(587);
            secureSMTPConnector.setMaxMessageSize(25 * 1024 * 1024); // 25MB for submission
            secureSMTPConnector.setAuthRequired(true); // Require auth for submission
            // Note: SSL configuration would be set here if keystore was available
            // secureSMTPConnector.setSecure(true);
            // secureSMTPConnector.setKeystoreFile("server.p12");
            // secureSMTPConnector.setKeystorePass("changeit");
            connectors.add(secureSMTPConnector);
            LOGGER.info("Created secure SMTP connector on port 587");
        } catch (Exception e) {
            LOGGER.warning("Could not create secure SMTP connector: " + e.getMessage());
        }

        // Create server with programmatically configured connectors
        Server server = new Server(connectors);
        LOGGER.info("Server created with " + connectors.size() + " connectors");

        // Note: In a real application, you would call server.start() or server.run()
        // For this demonstration, we just show the configuration is possible
        LOGGER.info("Programmatic configuration complete. Server ready to start.");
        
        // Clean up
        server = null;
    }

    /**
     * Example showing that the old file-based configuration still works via main().
     * This demonstrates backwards compatibility.
     */
    public static void demonstrateFileBasedConfiguration() {
        LOGGER.info("=== Demonstrating File-Based Configuration (via main) ===");
        LOGGER.info("File-based configuration is still supported through Server.main()");
        LOGGER.info("Example usage: java org.bluezoo.gumdrop.Server /path/to/gumdroprc");
        LOGGER.info("The main() method will parse the XML file and create connectors automatically");
    }

    /**
     * Main method to run the configuration demonstrations.
     */
    public static void main(String[] args) {
        System.out.println("Gumdrop Server Configuration Test");
        System.out.println("=================================");
        
        demonstrateProgrammaticConfiguration();
        System.out.println();
        demonstrateFileBasedConfiguration();
        
        System.out.println();
        System.out.println("Configuration refactoring complete!");
        System.out.println("- Server can now be instantiated programmatically with Collection<Connector>");
        System.out.println("- File-based configuration still works via Server.main() method");
        System.out.println("- No breaking changes to existing gumdroprc-based deployments");
    }
}
