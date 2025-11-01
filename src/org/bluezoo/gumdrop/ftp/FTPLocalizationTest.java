/*
 * FTPLocalizationTest.java
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

package org.bluezoo.gumdrop.ftp;

import java.text.MessageFormat;
import java.time.Instant;
import java.util.ResourceBundle;

/**
 * Test class demonstrating FTP localization functionality.
 * This shows how the FTP implementation uses ResourceBundle for internationalization.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class FTPLocalizationTest {

    public static void demonstrateLocalization() {
        System.out.println("FTP Localization Demonstration");
        System.out.println("==============================");
        
        ResourceBundle L10N = ResourceBundle.getBundle("org.bluezoo.gumdrop.ftp.L10N");
        
        System.out.println("\n1. FTP Response Messages:");
        System.out.println("  Welcome: " + L10N.getString("ftp.welcome_banner"));
        System.out.println("  Login Success: " + L10N.getString("ftp.login_successful"));
        System.out.println("  Transfer Complete: " + L10N.getString("ftp.transfer_complete"));
        
        System.out.println("\n2. Error Messages with Parameters:");
        String command = "INVALID";
        String args = "bad,arguments";
        String unrecognizedMsg = L10N.getString("ftp.err.command_unrecognized");
        String portErrorMsg = L10N.getString("ftp.err.invalid_port_arguments");
        
        System.out.println("  Unrecognized: " + MessageFormat.format(unrecognizedMsg, command));
        System.out.println("  Port Error: " + MessageFormat.format(portErrorMsg, args));
        
        System.out.println("\n3. File Listing Defaults:");
        System.out.println("  Default Owner: " + L10N.getString("file.default_owner"));
        System.out.println("  Default Group: " + L10N.getString("file.default_group"));
        System.out.println("  Default Date: " + L10N.getString("file.default_date"));
        
        System.out.println("\n4. Log Messages:");
        System.out.println("  New Connection: " + L10N.getString("log.new_connection"));
        System.out.println("  Auth Success: " + L10N.getString("log.authentication_success"));
        
        System.out.println("\n5. File Operations:");
        System.out.println("  Upload: " + L10N.getString("transfer.upload"));
        System.out.println("  Download: " + L10N.getString("transfer.download"));
        System.out.println("  List: " + L10N.getString("operation.list"));
        
        System.out.println("\n6. Demonstrate File Listing with Localized Defaults:");
        FTPFileInfo testFile = new FTPFileInfo("test.txt", 1024, Instant.now(), null, null, "rw-r--r--");
        System.out.println("  " + testFile.formatAsListingLine());
        
        FTPFileInfo testDir = new FTPFileInfo("testdir", Instant.now(), null, null, "rwxr-xr-x");
        System.out.println("  " + testDir.formatAsListingLine());
    }
    
    public static void demonstrateExtensibility() {
        System.out.println("\nLocalization Features:");
        System.out.println("=====================");
        
        System.out.println("✓ Separate L10N.properties file for FTP module");
        System.out.println("✓ ResourceBundle pattern consistent with Server and Servlet modules");
        System.out.println("✓ MessageFormat support for parameterized messages");
        System.out.println("✓ Client-facing FTP responses are localizable");
        System.out.println("✓ Log messages are localizable for administrators");
        System.out.println("✓ File listing defaults are localizable");
        System.out.println("✓ Ready for additional language variants (e.g., L10N_fr.properties)");
        
        System.out.println("\nLocalizable String Categories:");
        System.out.println("• FTP protocol responses (220, 230, 250, 331, 500, 501, 530, 550, etc.)");
        System.out.println("• Error messages for various failure scenarios");
        System.out.println("• Log messages for connection events and operations");
        System.out.println("• File listing format defaults (owner, group, date)");
        System.out.println("• Operation names (upload, download, list, delete, etc.)");
        System.out.println("• Status descriptions (success, failed, completed, etc.)");
        
        System.out.println("\nExtension Examples:");
        System.out.println("• Create L10N_fr.properties for French translations");
        System.out.println("• Create L10N_es.properties for Spanish translations");
        System.out.println("• Locale-specific date/time formatting in file listings");
        System.out.println("• Cultural customization of default file ownership");
    }

    public static void main(String[] args) {
        demonstrateLocalization();
        System.out.println();
        demonstrateExtensibility();
    }
}
