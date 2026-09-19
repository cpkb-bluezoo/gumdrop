/*
 * DnsResourceRecordSink.java
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

package org.bluezoo.gumdrop.dns;

/**
 * Event sink for DNS resource records emitted from a parsed message section.
 * Used with {@link DnsRecordSectionDispatcher} so consumers (for example RFC
 * 8198 proof caching) can process records incrementally without the
 * dispatcher materializing a new collection.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface DnsResourceRecordSink {

    /**
     * One resource record from the section being walked.
     *
     * @param record the record (caller must copy if it outlives the walk)
     */
    void resourceRecord(DnsResourceRecord record);

    /**
     * End of the section; no more {@link #resourceRecord} calls until the
     * next dispatch.
     */
    void endSection();
}
