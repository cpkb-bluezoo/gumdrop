/*
 * POP3Example.java
 * Example demonstrating how to configure and run a POP3 server.
 */

import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.auth.SaslMechanism;
import org.bluezoo.gumdrop.mailbox.mbox.MboxMailboxFactory;
import org.bluezoo.gumdrop.pop3.Pop3Listener;
import org.bluezoo.gumdrop.pop3.server.Pop3Server;
import org.bluezoo.gumdrop.pop3.server.Pop3ServerSessionProviders;
import org.bluezoo.gumdrop.tls.TlsConfig;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.EnumSet;
import java.util.Set;

/**
 * Example demonstrating how to configure and run a POP3 server.
 *
 * <p>Uses {@link Gumdrop#boot()}, {@link Pop3Server#compose()}, and the
 * checked-in mbox fixture under {@code test/integration/mailbox/mbox}. The
 * fixture is copied to a temporary directory so opening mailboxes does not
 * mutate the source tree (same idea as {@code MailboxFixtures} in integration
 * tests).
 *
 * <p>Default: cleartext POP3 on port 1110 (no root privileges). Pass
 * {@code etc/tls/cert.pem etc/tls/key.pem} after {@code ant tls-certs} to
 * also listen for POP3S on port 1995.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class POP3Example {

    private static final Path MBOX_FIXTURE =
            Paths.get("test/integration/mailbox/mbox");

    private static final String DEMO_USER = "editor";
    private static final String DEMO_PASS = "editor";

    private static final int POP3_PORT = 1110;
    private static final int POP3S_PORT = 1995;

    public static void main(String[] args) throws Exception {
        Path fixtureSource = MBOX_FIXTURE;
        if (args.length >= 1 && !args[0].endsWith(".pem")) {
            fixtureSource = Paths.get(args[0]);
        }

        final Path mailboxBase = copyFixtureTree(fixtureSource);
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                deleteRecursively(mailboxBase);
            }
        }));

        DemoRealm realm = new DemoRealm(DEMO_USER, DEMO_PASS);
        MboxMailboxFactory mailboxFactory = new MboxMailboxFactory(mailboxBase);

        Pop3Server.Composer composer = Pop3Server.compose()
                .listener(new Pop3Listener().port(POP3_PORT))
                .sessionProvider(Pop3ServerSessionProviders.mailbox(mailboxFactory))
                .realm(realm)
                .loginDelayMs(2000)
                .enableAPOP(true)
                .enableUTF8(true);

        String cert = null;
        String key = null;
        int tlsIndex = 0;
        if (args.length >= 1 && !args[0].endsWith(".pem")) {
            tlsIndex = 1;
        }
        if (args.length >= tlsIndex + 2
                && args[tlsIndex].endsWith(".pem")
                && args[tlsIndex + 1].endsWith(".pem")) {
            cert = args[tlsIndex];
            key = args[tlsIndex + 1];
        }
        if (cert != null && key != null) {
            TlsConfig tls = TlsConfig.pem(cert, key);
            composer.listener(new Pop3Listener()
                    .port(POP3S_PORT)
                    .secure(true)
                    .tls(tls));
        }

        Pop3Server server = composer.server();

        Gumdrop gumdrop = Gumdrop.boot();
        gumdrop.addServer(server);

        System.out.println("POP3 Example Server");
        System.out.println("===================");
        System.out.println("POP3  on port " + POP3_PORT + " (cleartext / STARTTLS when TLS is configured)");
        if (cert != null) {
            System.out.println("POP3S on port " + POP3S_PORT + " (implicit TLS)");
        } else {
            System.out.println("POP3S not started (pass cert.pem key.pem to enable)");
        }
        System.out.println();
        System.out.println("Fixture source: " + fixtureSource.toAbsolutePath());
        System.out.println("Mailbox copy:   " + mailboxBase.toAbsolutePath());
        System.out.println();
        System.out.println("Test user (matches integration mbox fixture):");
        System.out.println("  " + DEMO_USER + " / " + DEMO_PASS);
        System.out.println();
        System.out.println("  telnet localhost " + POP3_PORT);
        System.out.println("  USER " + DEMO_USER);
        System.out.println("  PASS " + DEMO_PASS);
        System.out.println("  STAT");
        System.out.println("  LIST");
        System.out.println("  RETR 1");
        System.out.println("  QUIT");
        System.out.println();

        gumdrop.join();
    }

    /**
     * Copies a fixture directory into a fresh temp directory.
     */
    private static Path copyFixtureTree(Path source) throws IOException {
        if (!Files.isDirectory(source)) {
            throw new IOException("Mailbox fixture not found: " + source.toAbsolutePath());
        }
        final Path dest = Files.createTempDirectory("gumdrop-pop3-example-");
        Files.walkFileTree(source, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                    throws IOException {
                Files.createDirectories(dest.resolve(source.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                Files.copy(file, dest.resolve(source.relativize(file)),
                        StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
        return dest;
    }

    private static void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                        throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc)
                        throws IOException {
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            // Best-effort cleanup on shutdown
        }
    }

    /**
     * Minimal realm for the fixture user.
     */
    static final class DemoRealm implements Realm {

        private static final Set<SaslMechanism> MECHANISMS =
                EnumSet.of(SaslMechanism.PLAIN, SaslMechanism.LOGIN);

        private final String username;
        private final String password;

        DemoRealm(String username, String password) {
            this.username = username;
            this.password = password;
        }

        @Override
        public boolean passwordMatch(String user, String pass) {
            return username.equals(user) && password.equals(pass);
        }

        @Override
        public boolean userExists(String user) {
            return username.equals(user);
        }

        @Override
        public Set<SaslMechanism> getSupportedSASLMechanisms() {
            return MECHANISMS;
        }

        @Override
        public String getDigestHA1(String user, String realmName) {
            return null;
        }

        @Override
        public Realm forSelectorLoop(SelectorLoop loop) {
            return this;
        }

        @Override
        @SuppressWarnings("deprecation")
        public String getPassword(String user) {
            if (username.equals(user)) {
                return password;
            }
            return null;
        }

        @Override
        public boolean isUserInRole(String user, String role) {
            return false;
        }
    }

    private POP3Example() {
    }

}
