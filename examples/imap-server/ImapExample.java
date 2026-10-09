/*
 * ImapExample.java
 * Example demonstrating how to configure and run an IMAP server.
 */

import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.auth.SaslMechanism;
import org.bluezoo.gumdrop.auth.SynchronousRealm;
import org.bluezoo.gumdrop.imap.ImapListener;
import org.bluezoo.gumdrop.imap.server.DefaultIMAPHandler;
import org.bluezoo.gumdrop.imap.server.DeleteState;
import org.bluezoo.gumdrop.imap.server.ImapServer;
import org.bluezoo.gumdrop.imap.server.ImapServerSessionProvider;
import org.bluezoo.gumdrop.imap.server.ClientConnected;
import org.bluezoo.gumdrop.imap.server.SelectState;
import org.bluezoo.gumdrop.mailbox.MailboxStore;
import org.bluezoo.gumdrop.mailbox.mbox.MboxMailboxFactory;
import org.bluezoo.gumdrop.TcpListener;
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
 * Example demonstrating how to configure and run an IMAP server.
 *
 * <p>Uses {@link Gumdrop#boot()}, {@link ImapServer#compose()} and the
 * checked-in mbox fixture under {@code test/integration/mailbox/mbox}, copied
 * to a temporary directory so the source tree is not modified. The session
 * provider returns a {@link DefaultIMAPHandler} subclass that adds two
 * policies: INBOX cannot be deleted, and every SELECT is logged.
 *
 * <p>Default: cleartext IMAP on port 1143 (no root privileges), with LOGIN
 * allowed over the cleartext connection for testing. Pass
 * {@code etc/tls/cert.pem etc/tls/key.pem} after {@code ant tls-certs} to also
 * listen for IMAPS on port 1993.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ImapExample {

    private static final Path MBOX_FIXTURE =
            Paths.get("test/integration/mailbox/mbox");

    private static final String DEMO_USER = "editor";
    private static final String DEMO_PASS = "editor";

    private static final int IMAP_PORT = 1143;
    private static final int IMAPS_PORT = 1993;

    /** Adds site policy to the default handler, which accepts everything. */
    private static final class PolicyHandler extends DefaultIMAPHandler {

        @Override
        public void select(SelectState state, MailboxStore store, String mailboxName) {
            System.out.println("SELECT " + mailboxName);
            state.proceed(this);
        }

        @Override
        public void delete(DeleteState state, MailboxStore store, String mailboxName) {
            if ("INBOX".equalsIgnoreCase(mailboxName)) {
                state.cannotDelete("INBOX cannot be deleted", this);
            } else {
                state.proceed(this);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        final Path mailboxBase = copyFixtureTree(MBOX_FIXTURE);
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                deleteRecursively(mailboxBase);
            }
        }));

        final MboxMailboxFactory mailboxFactory = new MboxMailboxFactory(mailboxBase);
        ImapServerSessionProvider provider = new ImapServerSessionProvider() {
            @Override
            public ClientConnected openSession(TcpListener listener) {
                // Sessions open the mailbox store of an authenticated user
                // from the factory configured on the listener
                ((ImapListener) listener).mailboxFactory(mailboxFactory);
                return new PolicyHandler();
            }
        };

        ImapServer.Composer composer = ImapServer.compose()
                .listener(new ImapListener().port(IMAP_PORT))
                .sessionProvider(provider)
                .realm(new DemoRealm(DEMO_USER, DEMO_PASS))
                .allowPlaintextLogin(true);

        if (args.length >= 2 && args[0].endsWith(".pem") && args[1].endsWith(".pem")) {
            TlsConfig tls = TlsConfig.pem(Paths.get(args[0]), Paths.get(args[1]));
            composer.listener(new ImapListener()
                    .port(IMAPS_PORT)
                    .secure(true)
                    .tls(tls));
        }

        Gumdrop gumdrop = Gumdrop.boot();
        gumdrop.addServer(composer.server());

        System.out.println("IMAP Example Server");
        System.out.println("===================");
        System.out.println("IMAP on port " + IMAP_PORT + " (cleartext LOGIN allowed for testing)");
        System.out.println("Mailbox copy: " + mailboxBase.toAbsolutePath());
        System.out.println("Test user: " + DEMO_USER + " / " + DEMO_PASS);
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
        final Path dest = Files.createTempDirectory("gumdrop-imap-example-");
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
    static final class DemoRealm implements SynchronousRealm {

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
        public boolean isUserInRole(String user, String role) {
            return false;
        }
    }

    private ImapExample() {
    }

}
