import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.Principal;
import java.util.ArrayList;
import java.util.List;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.TcpListener;
import org.bluezoo.gumdrop.dns.client.DnsResolver;
import org.bluezoo.gumdrop.mailbox.MailboxFactory;
import org.bluezoo.gumdrop.mime.rfc5322.EmailAddress;
import org.bluezoo.gumdrop.smtp.DeliveryRequirements;
import org.bluezoo.gumdrop.smtp.SmtpPipeline;
import org.bluezoo.gumdrop.smtp.auth.AuthPipeline;
import org.bluezoo.gumdrop.smtp.auth.DkimCallback;
import org.bluezoo.gumdrop.smtp.auth.DkimResult;
import org.bluezoo.gumdrop.smtp.auth.DmarcCallback;
import org.bluezoo.gumdrop.smtp.auth.DmarcPolicy;
import org.bluezoo.gumdrop.smtp.auth.DmarcResult;
import org.bluezoo.gumdrop.smtp.auth.AuthVerdict;
import org.bluezoo.gumdrop.smtp.auth.SpfCallback;
import org.bluezoo.gumdrop.smtp.auth.SpfResult;
import org.bluezoo.gumdrop.smtp.server.AuthenticateState;
import org.bluezoo.gumdrop.smtp.server.ClientConnected;
import org.bluezoo.gumdrop.smtp.server.ConnectedState;
import org.bluezoo.gumdrop.smtp.server.HelloHandler;
import org.bluezoo.gumdrop.smtp.server.HelloState;
import org.bluezoo.gumdrop.smtp.server.MailFromHandler;
import org.bluezoo.gumdrop.smtp.server.MailFromState;
import org.bluezoo.gumdrop.smtp.server.MessageDataHandler;
import org.bluezoo.gumdrop.smtp.server.MessageEndState;
import org.bluezoo.gumdrop.smtp.server.MessageStartState;
import org.bluezoo.gumdrop.smtp.server.RecipientHandler;
import org.bluezoo.gumdrop.smtp.server.RecipientState;
import org.bluezoo.gumdrop.smtp.server.ResetState;
import org.bluezoo.gumdrop.smtp.server.SmtpServer;
import org.bluezoo.gumdrop.smtp.server.SmtpServerSessionProvider;
import org.bluezoo.gumdrop.smtp.SmtpListener;

/**
 * Example SMTP server for one local domain.
 *
 * <p>Accepts mail for {@code example.test}, refuses everything else as relay
 * denied, runs SPF, DKIM and DMARC checks through {@link AuthPipeline} and
 * writes each accepted message to a spool directory. The handler is one
 * object per connection that implements every stage of the staged pipeline:
 * {@code ClientConnected}, {@code HelloHandler}, {@code MailFromHandler},
 * {@code RecipientHandler} and {@code MessageDataHandler}.
 *
 * <p>Listens on port 2525 (no root privileges). Pass a spool directory as the
 * first argument; the default is a fresh temporary directory.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SmtpExample {

    private static final int SMTP_PORT = 2525;
    private static final String LOCAL_DOMAIN = "example.test";

    public static void main(String[] args) throws Exception {
        Path spool;
        if (args.length >= 1) {
            spool = Paths.get(args[0]);
            Files.createDirectories(spool);
        } else {
            spool = Files.createTempDirectory("gumdrop-smtp-spool");
        }

        final Path spoolDir = spool;
        SmtpServerSessionProvider provider = new SmtpServerSessionProvider() {
            @Override
            public ClientConnected openSession(TcpListener listener) {
                return new SpoolHandler(spoolDir);
            }
        };

        SmtpServer server = SmtpServer.compose()
                .listener(new SmtpListener().port(SMTP_PORT))
                .sessionProvider(provider)
                .maxMessageSize(10L * 1024 * 1024)
                .maxRecipients(50)
                .server();

        Gumdrop gumdrop = Gumdrop.boot();
        gumdrop.addServer(server);

        System.out.println("SMTP Example Server");
        System.out.println("===================");
        System.out.println("Listening on port " + SMTP_PORT + " for @" + LOCAL_DOMAIN);
        System.out.println("Spool directory: " + spool.toAbsolutePath());
        System.out.println();
        gumdrop.join();
    }

    /**
     * Handles one SMTP connection. Each stage interface is implemented by the
     * same object, so the state the transaction needs lives in plain fields.
     */
    private static final class SpoolHandler implements ClientConnected,
            HelloHandler, MailFromHandler, RecipientHandler,
            MessageDataHandler {

        private final Path spool;
        private Endpoint endpoint;
        private String heloHost;

        private EmailAddress sender;
        private final List<EmailAddress> recipients =
                new ArrayList<EmailAddress>();
        private java.io.ByteArrayOutputStream message =
                new java.io.ByteArrayOutputStream();
        private AuthPipeline pipeline;

        // The DMARC verdict arrives asynchronously, usually after the last
        // byte of the message; the final reply waits for both
        private MessageEndState pendingEnd;
        private AuthVerdict verdict;
        private boolean authenticated;

        SpoolHandler(Path spool) {
            this.spool = spool;
        }

        // ClientConnected

        @Override
        public void connected(ConnectedState state, Endpoint endpoint) {
            this.endpoint = endpoint;
            state.acceptConnection(LOCAL_DOMAIN + " ESMTP ready", this);
        }

        @Override
        public void disconnected() {
            clearTransaction();
        }

        // HelloHandler

        @Override
        public void hello(HelloState state, boolean extended, String hostname) {
            this.heloHost = hostname;
            state.acceptHello(this);
        }

        @Override
        public void tlsEstablished(SecurityInfo securityInfo) {
        }

        @Override
        public void authenticated(AuthenticateState state, Principal principal) {
            state.accept(this);
        }

        // MailFromHandler

        @Override
        public SmtpPipeline getPipeline() {
            SocketAddress remote = endpoint.getRemoteAddress();
            InetAddress clientIp = ((InetSocketAddress) remote).getAddress();
            DnsResolver resolver =
                    DnsResolver.forLoop(endpoint.getSelectorLoop());
            pipeline = new AuthPipeline.Builder(resolver, clientIp, heloHost)
                    .onSPF(new SpfCallback() {
                        @Override
                        public void spfResult(SpfResult result,
                                String explanation) {
                            System.out.println("SPF: " + result);
                        }
                    })
                    .onDKIM(new DkimCallback() {
                        @Override
                        public void dkimResult(DkimResult result,
                                String signingDomain, String selector) {
                            System.out.println("DKIM: " + result + " ("
                                    + signingDomain + ")");
                        }
                    })
                    .onDMARC(new DmarcCallback() {
                        @Override
                        public void dmarcResult(DmarcResult result,
                                DmarcPolicy policy, String fromDomain,
                                AuthVerdict verdict) {
                            System.out.println("DMARC: " + result
                                    + ", verdict " + verdict);
                            SpoolHandler.this.verdict = verdict;
                            authenticated = true;
                            finishMessage();
                        }
                    })
                    .build();
            return pipeline;
        }

        @Override
        public void mailFrom(MailFromState state, EmailAddress sender,
                boolean smtputf8, DeliveryRequirements requirements) {
            this.sender = sender;
            recipients.clear();
            message.reset();
            state.acceptSender(this);
        }

        @Override
        public void reset(ResetState state) {
            clearTransaction();
            state.acceptReset(this);
        }

        @Override
        public void quit() {
            clearTransaction();
        }

        // RecipientHandler

        @Override
        public void rcptTo(RecipientState state, EmailAddress recipient,
                MailboxFactory factory) {
            if (LOCAL_DOMAIN.equalsIgnoreCase(recipient.getDomain())) {
                recipients.add(recipient);
                state.acceptRecipient(this);
            } else {
                state.rejectRecipientRelayDenied(this);
            }
        }

        @Override
        public void startMessage(MessageStartState state) {
            state.acceptMessage(this);
        }

        // MessageDataHandler

        @Override
        public void messageContent(ByteBuffer content) {
            byte[] chunk = new byte[content.remaining()];
            content.get(chunk);
            message.write(chunk, 0, chunk.length);
        }

        @Override
        public void messageComplete(MessageEndState state) {
            pendingEnd = state;
            finishMessage();
        }

        /**
         * Replies to the end of the message once the content has all arrived
         * and the DMARC verdict is in.
         */
        private void finishMessage() {
            MessageEndState state = pendingEnd;
            if (state == null || !authenticated) {
                return;
            }
            pendingEnd = null;
            if (verdict == AuthVerdict.REJECT) {
                state.rejectMessagePermanent("Rejected by DMARC policy", this);
                clearTransaction();
                return;
            }
            String queueId = Long.toHexString(System.nanoTime());
            try {
                Files.write(spool.resolve(queueId + ".eml"),
                        message.toByteArray());
            } catch (IOException e) {
                state.rejectMessageTemporary("Unable to store message", this);
                return;
            }
            state.acceptMessageDelivery(queueId, this);
            clearTransaction();
        }

        @Override
        public void messageAborted() {
            clearTransaction();
        }

        @Override
        public boolean wantsPause() {
            return false;
        }

        @Override
        public void setResumeCallback(Runnable callback) {
        }

        private void clearTransaction() {
            sender = null;
            recipients.clear();
            message.reset();
            pipeline = null;
            pendingEnd = null;
            verdict = null;
            authenticated = false;
        }
    }
}
