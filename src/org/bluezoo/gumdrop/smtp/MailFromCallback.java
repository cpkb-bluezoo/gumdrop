/*
 * MailFromCallback.java
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

package org.bluezoo.gumdrop.smtp;

/**
 * Callback interface for delivering asynchronous MAIL FROM policy results.
 * <p>
 * This interface enables non-blocking SMTP connection handling by allowing
 * policy evaluations to be performed asynchronously. The handler can perform
 * database lookups, reputation checks, or other time-consuming operations
 * without blocking the connection thread.
 * 
 * <p>The callback method will be invoked with the policy result, and the
 * SMTP connection will automatically send the appropriate response code
 * to the client based on the result.
 * 
 * <p><strong>Thread Safety:</strong> Implementations must be thread-safe as
 * the callback may be invoked from different threads depending on how the
 * policy evaluation is implemented (thread pools, async I/O, etc.).
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see SenderPolicyResult
 * @see SMTPConnectionHandler#mailFrom(String, MailFromCallback)
 */
@FunctionalInterface
public interface MailFromCallback {

    /**
     * Receives the result of sender policy evaluation.
     * <p>
     * This method is called asynchronously once the policy evaluation for
     * a MAIL FROM command has completed. The SMTP connection will automatically
     * generate the appropriate SMTP response based on the result.
     * 
     * <p>The implementation should not assume which thread this method will
     * be called from, and should not perform any blocking operations.
     * 
     * @param result the policy decision for the sender address
     */
    void mailFromReply(SenderPolicyResult result);

}
