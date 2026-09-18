/*
 * DnssecValidationCallback.java
 * Copyright (C) 2026 Chris Burdess
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

package org.bluezoo.gumdrop.dns.client;

import org.bluezoo.gumdrop.dns.DnsMessage;
import org.bluezoo.gumdrop.dns.DnssecStatus;

/**
 * Callback interface for asynchronous DNSSEC validation results.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see DnssecChainValidator
 */
public interface DnssecValidationCallback {

    /**
     * Called when DNSSEC validation completes.
     *
     * @param status the validation result
     * @param response the original DNS response
     */
    void onValidated(DnssecStatus status, DnsMessage response);

}
