/*
 * SMTPPipeline.java
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

import java.nio.channels.WritableByteChannel;

import org.bluezoo.gumdrop.mime.rfc5322.EmailAddress;

/**
 * Generic pipeline interface for SMTP message processing.
 *
 * <p>An SMTPPipeline receives notifications at key stages of an SMTP
 * transaction and can optionally receive the raw message bytes. This
 * allows pipelines to perform various processing such as:
 *
 * <ul>
 *   <li>Email authentication (SPF, DKIM, DMARC)</li>
 *   <li>Content filtering and virus scanning</li>
 *   <li>Message archiving and journaling</li>
 *   <li>Rate limiting and quota enforcement</li>
 * </ul>
 *
 * <p>SMTPConnection calls the pipeline methods automatically at the
 * appropriate stages. The pipeline can perform checks and invoke
 * callbacks registered during its construction.
 *
 * <h2>Lifecycle</h2>
 *
 * <ol>
 *   <li>{@link #mailFrom(EmailAddress)} - Called when MAIL FROM is received</li>
 *   <li>{@link #rcptTo(EmailAddress)} - Called for each RCPT TO received</li>
 *   <li>{@link #getMessageChannel()} - Called at DATA start to get byte channel</li>
 *   <li>Channel.write() - Called as message bytes stream in</li>
 *   <li>Channel.close() - Called at end of DATA transfer</li>
 *   <li>{@link #endData()} - Called after channel close for final processing</li>
 *   <li>{@link #reset()} - Called on RSET or after message delivery</li>
 * </ol>
 *
 * <h2>Usage</h2>
 *
 * <p>Associate a pipeline with an SMTP connection:
 *
 * <pre><code>
 * public void connected(SMTPConnectionMetadata metadata) {
 *     SMTPPipeline pipeline = createMyPipeline();
 *     metadata.setPipeline(pipeline);
 * }
 * </code></pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see org.bluezoo.gumdrop.smtp.auth.AuthPipeline
 */
public interface SMTPPipeline {

    /**
     * Called when MAIL FROM is received.
     *
     * <p>This is typically the first notification for a new message.
     * Pipelines can use this to perform sender-based checks (e.g., SPF).
     *
     * @param sender the envelope sender address, or null for bounce messages
     */
    void mailFrom(EmailAddress sender);

    /**
     * Called when RCPT TO is received.
     *
     * <p>This may be called multiple times for messages with multiple
     * recipients. Pipelines can use this for recipient-based checks.
     *
     * @param recipient the envelope recipient address
     */
    void rcptTo(EmailAddress recipient);

    /**
     * Returns a channel to receive raw message bytes.
     *
     * <p>Called when DATA transfer begins. If this returns non-null,
     * SMTPConnection will write raw message bytes to the channel as
     * they arrive, and close the channel at end of transfer.
     *
     * <p>Returning null indicates the pipeline doesn't need message bytes.
     *
     * @return a writable byte channel, or null
     */
    WritableByteChannel getMessageChannel();

    /**
     * Called when DATA transfer is complete.
     *
     * <p>This is called after the message channel is closed. Pipelines
     * should perform any final processing here (e.g., DKIM verification,
     * DMARC evaluation) and invoke completion callbacks.
     */
    void endData();

    /**
     * Called when the transaction is reset.
     *
     * <p>This occurs on RSET command, after successful message delivery,
     * or when the connection is closed. Pipelines should clean up any
     * per-message state.
     */
    void reset();

}

