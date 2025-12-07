/*
 * MailFromCallback.java
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
