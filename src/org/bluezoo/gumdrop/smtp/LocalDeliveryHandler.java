/*
 * LocalDeliveryHandler.java
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

package org.bluezoo.gumdrop.smtp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.CompletionHandler;
import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.StorageExecutor;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.smtp.handler.*;
import org.bluezoo.gumdrop.mailbox.AsyncMessageWriter;
import org.bluezoo.gumdrop.mailbox.Mailbox;
import org.bluezoo.gumdrop.mailbox.MailboxFactory;
import org.bluezoo.gumdrop.mailbox.MailboxStore;
import org.bluezoo.gumdrop.mime.rfc5322.EmailAddress;

/**
 * SMTP handler that delivers incoming messages to local mailboxes.
 *
 * <p>This handler accepts mail only for recipients in the configured local
 * domain. Messages are delivered to the INBOX of each recipient, using the
 * local-part of the email address as the username.
 *
 * <p>If any recipient is not in the local domain, the message is rejected
 * at RCPT TO time with a "relay denied" error.
 *
 * <h4>Configuration</h4>
 *
 * <p>This handler requires:
 * <ul>
 *   <li>A {@link MailboxFactory} - set via constructor or service</li>
 *   <li>A local domain - the domain this server accepts mail for</li>
 * </ul>
 *
 * <h4>Example</h4>
 *
 * <pre><code>
 * MailboxFactory factory = new MaildirMailboxFactory("/var/mail");
 * LocalDeliveryHandler handler = new LocalDeliveryHandler(factory, "example.com");
 * </code></pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc5321#section-2.3.10">RFC 5321 §2.3.10</a> (final delivery)
 * @see MailboxFactory
 */
public class LocalDeliveryHandler
        implements ClientConnected, HelloHandler, MailFromHandler, 
                   RecipientHandler, MessageDataHandler {

    private static final Logger LOGGER = Logger.getLogger(LocalDeliveryHandler.class.getName());
    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.smtp.L10N");

    private final MailboxFactory mailboxFactory;
    private final String localDomain;
    private final String hostname;

    // Per-message state
    private EmailAddress sender;
    private List<String> recipients;  // Local-parts of recipients
    private ByteArrayOutputStream messageBuffer;

    // Async append state. Opening the per-recipient async writers is blocking
    // disk I/O (store scan + file lock / temp-file create), so it is offloaded
    // to the StorageExecutor while the DATA state transition (354) is sent
    // synchronously. Content that arrives before the writers are ready is
    // accumulated in messageBuffer and flushed once they open; if async append
    // is unavailable (e.g. mbox) the whole message is buffered and delivered
    // at messageComplete.
    private List<AsyncAppendTarget> asyncTargets;
    // True while the async writers are being opened off-loop.
    private boolean targetsOpening;
    // Non-null if end-of-message arrived before the writers finished opening;
    // the completion is run once onTargetsOpened() fires.
    private MessageEndState deferredEndState;
    private Runnable resumeCallback;

    // Endpoint for this connection, captured in connected(). Used to offload
    // blocking mailbox opens/deliveries to the shared StorageExecutor and
    // marshal the result back to this connection's loop thread.
    private Endpoint endpoint;

    /**
     * Creates a new local delivery handler.
     *
     * @param mailboxFactory the factory for creating mailbox stores
     * @param localDomain the domain this server accepts mail for
     */
    public LocalDeliveryHandler(MailboxFactory mailboxFactory, String localDomain) {
        this(mailboxFactory, localDomain, "localhost");
    }

    /**
     * Creates a new local delivery handler.
     *
     * @param mailboxFactory the factory for creating mailbox stores
     * @param localDomain the domain this server accepts mail for
     * @param hostname the server hostname for the greeting
     */
    public LocalDeliveryHandler(MailboxFactory mailboxFactory, String localDomain,
                                String hostname) {
        if (mailboxFactory == null) {
            throw new NullPointerException(L10N.getString("err.null_mailbox_factory"));
        }
        if (localDomain == null || localDomain.isEmpty()) {
            throw new IllegalArgumentException(L10N.getString("err.empty_local_domain"));
        }
        this.mailboxFactory = mailboxFactory;
        this.localDomain = localDomain.toLowerCase();
        this.hostname = hostname != null ? hostname : "localhost";
        this.recipients = new ArrayList<String>();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ClientConnected
    // ═══════════════════════════════════════════════════════════════════════

    @Override
    public void connected(ConnectedState state, Endpoint endpoint) {
        this.endpoint = endpoint;
        state.acceptConnection(hostname + " ESMTP Service ready", this);
    }

    @Override
    public void disconnected() {
        // Clean up any resources
        resetMessageState();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // HelloHandler
    // ═══════════════════════════════════════════════════════════════════════

    @Override
    public void hello(HelloState state, boolean extended, String hostname) {
        state.acceptHello(this);
    }

    @Override
    public void tlsEstablished(SecurityInfo securityInfo) {
        // TLS established - nothing special to do
    }

    @Override
    public void authenticated(AuthenticateState state, Principal principal) {
        // Accept any authenticated principal for local delivery
        state.accept(this);
    }

    @Override
    public void quit() {
        // Connection will close
    }

    // ═══════════════════════════════════════════════════════════════════════
    // MailFromHandler
    // ═══════════════════════════════════════════════════════════════════════

    @Override
    public SMTPPipeline getPipeline() {
        return null;  // No pipeline needed
    }

    @Override
    public void mailFrom(MailFromState state, EmailAddress sender, boolean smtputf8,
                         DeliveryRequirements deliveryRequirements) {
        this.sender = sender;
        this.recipients.clear();
        this.messageBuffer = new ByteArrayOutputStream();
        state.acceptSender(this);
    }

    @Override
    public void reset(ResetState state) {
        resetMessageState();
        state.acceptReset(this);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // RecipientHandler
    // ═══════════════════════════════════════════════════════════════════════

    @Override
    public void rcptTo(RecipientState state, EmailAddress recipient, MailboxFactory factory) {
        // Check if recipient is in local domain
        String domain = recipient.getDomain();
        if (domain == null || !domain.toLowerCase().equals(localDomain)) {
            // Not local - reject as relay denied
            state.rejectRecipientRelayDenied(this);
            return;
        }

        // Accept and record the local-part
        String localPart = recipient.getLocalPart();
        recipients.add(localPart);
        state.acceptRecipient(this);
    }

    @Override
    public void startMessage(MessageStartState state) {
        if (recipients.isEmpty()) {
            state.rejectMessage("No recipients", this);
            return;
        }

        // The DATA state transition (354) MUST be sent synchronously: the
        // protocol handler only switches to data-collection mode once
        // acceptMessage() returns, and a pipelining client may already have
        // the message body buffered behind the DATA command. Deferring the
        // 354 would let that body be mis-parsed as SMTP commands and lost.
        //
        // Opening each recipient's store/mailbox and async writer is blocking
        // disk I/O, so it is offloaded WITHOUT pausing reads. Content that
        // arrives before the writers are ready is accumulated in messageBuffer
        // and flushed to the writers once they open (streaming thereafter); if
        // async append is unavailable, the whole message stays buffered and is
        // delivered at messageComplete.
        asyncTargets = null;
        targetsOpening = true;
        deferredEndState = null;
        if (messageBuffer == null) {
            messageBuffer = new ByteArrayOutputStream();
        }
        state.acceptMessage(this);

        final List<String> recips = new ArrayList<String>(recipients);
        offload(new Callable<List<AsyncAppendTarget>>() {
            @Override
            public List<AsyncAppendTarget> call() {
                return openAsyncTargets(recips);
            }
        }, new Consumer<List<AsyncAppendTarget>>() {
            @Override
            public void accept(List<AsyncAppendTarget> targets) {
                onTargetsOpened(targets);
            }
        }, new Consumer<Throwable>() {
            @Override
            public void accept(Throwable error) {
                LOGGER.log(Level.FINE,
                        "Async append unavailable; buffering", error);
                onTargetsOpened(null);
            }
        });
    }

    /**
     * Invoked on the loop thread when the async writers have finished opening
     * (or failed). Switches to streaming mode if writers are available,
     * flushing any content buffered while they were opening, and runs a
     * deferred end-of-message if one arrived in the meantime.
     */
    private void onTargetsOpened(List<AsyncAppendTarget> targets) {
        targetsOpening = false;
        if (targets != null && !targets.isEmpty()) {
            asyncTargets = targets;
            if (messageBuffer != null && messageBuffer.size() > 0) {
                writeToTargets(messageBuffer.toByteArray());
            }
            messageBuffer = null;
        }
        // else: buffered fallback — messageBuffer keeps accumulating content.

        if (deferredEndState != null) {
            MessageEndState s = deferredEndState;
            deferredEndState = null;
            finishMessage(s);
        }
    }

    /**
     * Opens async-append writers for every recipient. Returns the list of
     * targets, or {@code null} if async append is unavailable for any
     * recipient (in which case all partially-opened targets are aborted and
     * the caller falls back to buffered delivery). Runs off the loop thread.
     */
    private List<AsyncAppendTarget> openAsyncTargets(List<String> recips) {
        List<AsyncAppendTarget> targets = new ArrayList<AsyncAppendTarget>();
        for (String username : recips) {
            MailboxStore store = null;
            try {
                store = mailboxFactory.createStore();
                store.open(username);
                Mailbox mailbox = store.openMailbox("INBOX", false);
                AsyncMessageWriter writer =
                        mailbox.openAsyncAppend(null, null);
                if (writer != null) {
                    targets.add(new AsyncAppendTarget(
                            username, store, mailbox, writer));
                } else {
                    mailbox.close(false);
                    store.close();
                    abortAll(targets);
                    return null;
                }
            } catch (IOException e) {
                LOGGER.log(Level.FINE,
                        "Async append unavailable for " + username, e);
                if (store != null) {
                    try {
                        store.close();
                    } catch (IOException ce) {
                        LOGGER.log(Level.FINE,
                                "Error closing store after failure", ce);
                    }
                }
                abortAll(targets);
                return null;
            }
        }
        return targets;
    }

    private static void abortAll(List<AsyncAppendTarget> targets) {
        for (AsyncAppendTarget target : targets) {
            target.abort();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // MessageDataHandler
    // ═══════════════════════════════════════════════════════════════════════

    @Override
    public void messageContent(ByteBuffer content) {
        byte[] bytes = new byte[content.remaining()];
        content.get(bytes);
        if (asyncTargets != null && !asyncTargets.isEmpty()) {
            // Streaming mode: writers are open.
            writeToTargets(bytes);
        } else {
            // Buffering: writers are still opening, or the buffered fallback
            // is in effect (async append unavailable).
            if (messageBuffer == null) {
                messageBuffer = new ByteArrayOutputStream();
            }
            try {
                messageBuffer.write(bytes);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING,
                        "Error buffering message content", e);
            }
        }
    }

    /**
     * Streams a chunk of content to every open async writer. Runs on the loop
     * thread; the writers persist the data asynchronously.
     */
    private void writeToTargets(byte[] data) {
        for (final AsyncAppendTarget target : asyncTargets) {
            ByteBuffer copy = ByteBuffer.wrap(data.clone());
            target.writer.write(copy,
                    new CompletionHandler<Integer, ByteBuffer>() {
                @Override
                public void completed(Integer result, ByteBuffer attachment) {
                    // Chunk written successfully
                }

                @Override
                public void failed(Throwable exc, ByteBuffer attachment) {
                    LOGGER.log(Level.WARNING,
                            "Async write failed for " + target.username, exc);
                }
            });
        }
    }

    @Override
    public void messageComplete(MessageEndState state) {
        if (targetsOpening) {
            // Writers are still being opened off-loop; run the completion
            // once they are ready (or the buffered fallback is chosen). No
            // reply is sent until then — the client is waiting for the 250.
            deferredEndState = state;
            return;
        }
        finishMessage(state);
    }

    /**
     * Finalizes the message once the writers' open state is known: either
     * finishing the open async writers or delivering the buffered fallback.
     * All blocking work is offloaded; the 250/4xx reply is sent on the loop.
     */
    private void finishMessage(final MessageEndState state) {
        if (asyncTargets != null && !asyncTargets.isEmpty()) {
            final List<AsyncAppendTarget> targets = asyncTargets;
            asyncTargets = null;
            offload(new Callable<String>() {
                @Override
                public String call() {
                    return finalizeTargets(targets);
                }
            }, new Consumer<String>() {
                @Override
                public void accept(String errorMessage) {
                    resetMessageState();
                    if (errorMessage == null) {
                        state.acceptMessageDelivery(null,
                                LocalDeliveryHandler.this);
                    } else {
                        state.rejectMessageTemporary(errorMessage,
                                LocalDeliveryHandler.this);
                    }
                }
            }, new Consumer<Throwable>() {
                @Override
                public void accept(Throwable error) {
                    resetMessageState();
                    state.rejectMessageTemporary(
                            error.getMessage(), LocalDeliveryHandler.this);
                }
            });
            return;
        }

        // Buffered path (fallback): delivering to each mailbox is blocking
        // disk I/O (store scan + file lock + append), so offload it.
        final byte[] messageData = messageBuffer != null
                ? messageBuffer.toByteArray() : new byte[0];
        final List<String> recips = new ArrayList<String>(recipients);
        offload(new Callable<String>() {
            @Override
            public String call() {
                return deliverBuffered(recips, messageData);
            }
        }, new Consumer<String>() {
            @Override
            public void accept(String errorMessage) {
                resetMessageState();
                if (errorMessage == null) {
                    state.acceptMessageDelivery(null, LocalDeliveryHandler.this);
                } else {
                    state.rejectMessageTemporary(errorMessage,
                            LocalDeliveryHandler.this);
                }
            }
        }, new Consumer<Throwable>() {
            @Override
            public void accept(Throwable error) {
                resetMessageState();
                state.rejectMessageTemporary(
                        error.getMessage(), LocalDeliveryHandler.this);
            }
        });
    }

    /**
     * Delivers buffered message data to every recipient's mailbox. Returns
     * {@code null} if all deliveries succeeded, or the first error message if
     * any failed (other recipients are still attempted). Runs off the loop.
     */
    private String deliverBuffered(List<String> recips, byte[] messageData) {
        String errorMessage = null;
        for (String username : recips) {
            try {
                deliverToMailbox(username, messageData);
                LOGGER.log(Level.FINE, "Delivered message to {0}@{1}",
                        new Object[] { username, localDomain });
            } catch (IOException e) {
                LOGGER.log(Level.WARNING,
                        "Failed to deliver to " + username, e);
                if (errorMessage == null) {
                    errorMessage = e.getMessage();
                }
            }
        }
        return errorMessage;
    }

    /**
     * Runs a blocking storage operation on the shared {@link StorageExecutor}
     * and delivers the outcome on this connection's loop thread. Reads are NOT
     * paused: the SMTP DATA flow relies on synchronous state transitions, and
     * pausing would neither stop the already-buffered input nor be needed
     * (clients wait for the 354/250 replies these callbacks send). If no
     * executor/endpoint is available (e.g. a unit-test harness), the operation
     * runs inline.
     */
    private <T> void offload(Callable<T> op, final Consumer<T> onDone,
            final Consumer<Throwable> onError) {
        Gumdrop gumdrop = Gumdrop.getInstance();
        StorageExecutor exec =
                (gumdrop != null) ? gumdrop.getStorageExecutor() : null;
        if (exec == null || endpoint == null) {
            T result;
            try {
                result = op.call();
            } catch (Throwable t) {
                onError.accept(t);
                return;
            }
            onDone.accept(result);
            return;
        }

        exec.submit(endpoint, op, new StorageExecutor.Callback<T>() {
            @Override
            public void completed(T result) {
                onDone.accept(result);
            }

            @Override
            public void failed(Throwable error) {
                onError.accept(error);
            }
        });
    }

    /**
     * Finalizes the async writers (off the loop). For each target, calls
     * {@link AsyncMessageWriter#finish} and waits for completion, then closes
     * the store/mailbox. Returns {@code null} if all succeeded, or the first
     * error message otherwise.
     */
    private String finalizeTargets(List<AsyncAppendTarget> targets) {
        String errorMessage = null;
        for (AsyncAppendTarget target : targets) {
            try {
                final Throwable[] error = { null };
                final CountDownLatch latch = new CountDownLatch(1);
                target.writer.finish(new CompletionHandler<Long, Void>() {
                    @Override
                    public void completed(Long uid, Void att) {
                        LOGGER.log(Level.FINE,
                                "Async delivered to {0}@{1} uid={2}",
                                new Object[] { target.username,
                                        localDomain, uid });
                        latch.countDown();
                    }

                    @Override
                    public void failed(Throwable exc, Void att) {
                        error[0] = exc;
                        latch.countDown();
                    }
                });
                if (!latch.await(30, TimeUnit.SECONDS)) {
                    throw new IOException(
                            "Timed out finalizing delivery for "
                                    + target.username);
                }
                if (error[0] != null) {
                    throw new IOException(
                            "Delivery failed: " + error[0].getMessage(),
                            error[0]);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (errorMessage == null) {
                    errorMessage = "Interrupted during delivery";
                }
            } catch (IOException e) {
                LOGGER.log(Level.WARNING,
                        "Failed async delivery to " + target.username,
                        e);
                if (errorMessage == null) {
                    errorMessage = e.getMessage();
                }
            } finally {
                target.closeStoreAndMailbox();
            }
        }
        return errorMessage;
    }

    @Override
    public void messageAborted() {
        deferredEndState = null;
        targetsOpening = false;
        if (asyncTargets != null) {
            for (AsyncAppendTarget target : asyncTargets) {
                target.abort();
            }
            asyncTargets = null;
        }
        resetMessageState();
    }

    @Override
    public boolean wantsPause() {
        if (asyncTargets != null) {
            for (AsyncAppendTarget target : asyncTargets) {
                if (target.writer.wantsPause()) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public void setResumeCallback(Runnable callback) {
        this.resumeCallback = callback;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Delivery
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Delivers message data to a user's mailbox.
     */
    private void deliverToMailbox(String username, byte[] messageData) throws IOException {
        MailboxStore store = null;
        Mailbox mailbox = null;

        try {
            store = mailboxFactory.createStore();
            store.open(username);
            mailbox = store.openMailbox("INBOX", false);

            // Append message
            mailbox.startAppendMessage(null, null);
            mailbox.appendMessageContent(ByteBuffer.wrap(messageData));
            mailbox.endAppendMessage();

        } finally {
            if (mailbox != null) {
                try {
                    mailbox.close(true);
                } catch (IOException e) {
                    LOGGER.log(Level.FINE, "Error closing mailbox", e);
                }
            }
            if (store != null) {
                try {
                    store.close();
                } catch (IOException e) {
                    LOGGER.log(Level.FINE, "Error closing store", e);
                }
            }
        }
    }

    /**
     * Resets per-message state.
     */
    private void resetMessageState() {
        sender = null;
        recipients.clear();
        messageBuffer = null;
        resumeCallback = null;
        asyncTargets = null;
        targetsOpening = false;
        deferredEndState = null;
    }

    /**
     * Tracks a single async append target (one recipient).
     */
    private static final class AsyncAppendTarget {

        final String username;
        private final MailboxStore store;
        private final Mailbox mailbox;
        final AsyncMessageWriter writer;

        AsyncAppendTarget(String username, MailboxStore store,
                Mailbox mailbox, AsyncMessageWriter writer) {
            this.username = username;
            this.store = store;
            this.mailbox = mailbox;
            this.writer = writer;
        }

        void abort() {
            writer.abort();
            closeStoreAndMailbox();
        }

        void closeStoreAndMailbox() {
            try {
                mailbox.close(true);
            } catch (IOException e) {
                Logger.getLogger(LocalDeliveryHandler.class.getName())
                        .log(Level.FINE, "Error closing mailbox", e);
            }
            try {
                store.close();
            } catch (IOException e) {
                Logger.getLogger(LocalDeliveryHandler.class.getName())
                        .log(Level.FINE, "Error closing store", e);
            }
        }
    }

}
