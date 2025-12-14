/*
 * DKIMCallback.java
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

package org.bluezoo.gumdrop.smtp.auth;

/**
 * Callback interface for asynchronous DKIM verification results.
 *
 * <p>This interface enables non-blocking DKIM validation. The callback is
 * invoked when signature verification completes, which requires fetching
 * the public key from DNS.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see DKIMValidator
 * @see DKIMResult
 */
public interface DKIMCallback {

    /**
     * Called when a DKIM verification completes.
     *
     * @param result the DKIM verification result
     * @param signingDomain the domain that signed the message (d= tag), or null
     * @param selector the selector used (s= tag), or null
     */
    void dkimResult(DKIMResult result, String signingDomain, String selector);

}


