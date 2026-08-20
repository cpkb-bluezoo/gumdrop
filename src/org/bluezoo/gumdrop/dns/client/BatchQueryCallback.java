/*
 * BatchQueryCallback.java
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

import java.util.List;

import org.bluezoo.gumdrop.dns.DNSResourceRecord;
import org.bluezoo.gumdrop.dns.DNSType;

/**
 * Callback interface for {@link DNSResolver#queryBatch(String, List,
 * BatchQueryCallback)}, which resolves several RRTYPEs for one name.
 *
 * <p>How many wire exchanges that takes is deliberately not visible
 * here: a supporting server may merge everything into a single
 * response (RFC 10029), or the resolver may fall back to a standalone
 * query per type. Either way, every requested type is reported exactly
 * once via {@link #onResult} or {@link #onTypeError}, in whatever order
 * results become available, followed by exactly one {@link
 * #onComplete()} once nothing is left outstanding.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see DNSResolver#queryBatch(String, List, BatchQueryCallback)
 */
public interface BatchQueryCallback {

    /**
     * Called when one of the requested types has resolved successfully.
     *
     * @param type the resolved type
     * @param records the answer records for this type (never empty)
     */
    void onResult(DNSType type, List<DNSResourceRecord> records);

    /**
     * Called when one of the requested types failed to resolve.
     * Other types in the same batch are unaffected and are still
     * reported individually.
     *
     * @param type the type that failed
     * @param error a description of the error
     */
    void onTypeError(DNSType type, String error);

    /**
     * Called once every requested type has reported a result or an
     * error, whether all succeeded, all failed, or a mix.
     */
    void onComplete();

}
