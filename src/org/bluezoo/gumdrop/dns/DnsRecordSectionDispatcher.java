/*
 * DnsRecordSectionDispatcher.java
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

import java.util.List;

/**
 * Dispatches an already-parsed DNS message section to a {@link DnsResourceRecordSink}
 * one record at a time (event-driven), mirroring the push-parser contract used
 * by {@code MimeParser} and {@code H2Parser}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class DnsRecordSectionDispatcher {

    private DnsRecordSectionDispatcher() {
    }

    /**
     * Walks {@code section} in wire order, invoking the sink for each record.
     *
     * @param section the section list from a {@link DnsMessage}
     * @param sink receives each record, then {@link DnsResourceRecordSink#endSection()}
     */
    public static void dispatch(List<DnsResourceRecord> section,
                                DnsResourceRecordSink sink) {
        if (section != null) {
            for (int i = 0; i < section.size(); i++) {
                sink.resourceRecord(section.get(i));
            }
        }
        sink.endSection();
    }
}
