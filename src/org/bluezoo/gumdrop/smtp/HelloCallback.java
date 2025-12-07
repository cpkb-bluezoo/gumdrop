/*
 * HelloCallback.java
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
 * Callback interface for delivering asynchronous HELO/EHLO command results.
 * <p>
 * This interface enables non-blocking SMTP connection handling by allowing
 * greeting command evaluation to be performed asynchronously. The handler can perform
 * hostname validation, policy checks, or other time-consuming operations
 * without blocking the connection thread.
 * 
 * <p>The callback method will be invoked with the policy result, and the
 * SMTP connection will automatically send the appropriate response code
 * to the client based on the result.
 * 
 * <p>HELO/EHLO commands establish the client's identity and initiate the SMTP session:
 * <ul>
 * <li><strong>HELO</strong> - Basic SMTP greeting, expects simple 250 response</li>
 * <li><strong>EHLO</strong> - Extended SMTP greeting, expects 250 response with extension list</li>
 * </ul>
 * 
 * <p><strong>Thread Safety:</strong> Implementations must be thread-safe as
 * the callback may be invoked from different threads depending on how the
 * greeting evaluation is implemented (thread pools, async I/O, etc.).
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see HelloReply
 * @see SMTPConnectionHandler#hello(boolean, String, HelloCallback)
 */

public interface HelloCallback {

    /**
     * Receives the result of HELO/EHLO command evaluation.
     * <p>
     * This method is called asynchronously once the evaluation for
     * a HELO or EHLO command has completed. The SMTP connection will automatically
     * generate the appropriate SMTP response based on the result.
     * 
     * <p>For ACCEPT_HELO results, the connection will send a simple 250 greeting.
     * For ACCEPT_EHLO results, the connection will send a multi-line 250 response
     * with available extensions. For any rejection result, the connection will
     * send the appropriate error response.
     * 
     * <p>The implementation should not assume which thread this method will
     * be called from, and should not perform any blocking operations.
     * 
     * @param result the policy decision for the HELO/EHLO command
     */
    void helloReply(HelloReply result);

}
