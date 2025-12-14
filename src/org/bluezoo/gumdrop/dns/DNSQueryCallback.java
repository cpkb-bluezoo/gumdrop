/*
 * DNSQueryCallback.java
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

package org.bluezoo.gumdrop.dns;

/**
 * Callback interface for asynchronous DNS query results.
 *
 * <p>This interface enables non-blocking DNS queries. The appropriate method
 * is called when the query completes, times out, or encounters an error.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see DNSResolver
 */
public interface DNSQueryCallback {

    /**
     * Called when a DNS query completes successfully.
     *
     * @param response the DNS response message
     */
    void onResponse(DNSMessage response);

    /**
     * Called when a DNS query fails due to timeout or network error.
     *
     * @param error a description of the error
     */
    void onError(String error);

}


