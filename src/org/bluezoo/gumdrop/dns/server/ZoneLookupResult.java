/*
 * ZoneLookupResult.java
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

package org.bluezoo.gumdrop.dns.server;

import org.bluezoo.gumdrop.dns.DnsMessage;
import org.bluezoo.gumdrop.dns.DnsResourceRecord;

import java.util.Collections;
import java.util.List;

/**
 * Outcome of an authoritative lookup in a {@link ZoneFile}.
  * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class ZoneLookupResult {

    static final int STATUS_ANSWER = 0;
    static final int STATUS_NODATA = 1;
    static final int STATUS_NXDOMAIN = 2;

    private final int status;
    private final List<DnsResourceRecord> answers;
    private final boolean fromWildcard;

    private ZoneLookupResult(int status, List<DnsResourceRecord> answers,
                             boolean fromWildcard) {
        this.status = status;
        this.answers = answers;
        this.fromWildcard = fromWildcard;
    }

    static ZoneLookupResult answer(List<DnsResourceRecord> answers) {
        return new ZoneLookupResult(STATUS_ANSWER,
                copy(answers), false);
    }

    static ZoneLookupResult answerWildcard(List<DnsResourceRecord> answers) {
        return new ZoneLookupResult(STATUS_ANSWER,
                copy(answers), true);
    }

    static ZoneLookupResult nodata() {
        return new ZoneLookupResult(STATUS_NODATA,
                Collections.<DnsResourceRecord>emptyList(), false);
    }

    static ZoneLookupResult nxdomain() {
        return new ZoneLookupResult(STATUS_NXDOMAIN,
                Collections.<DnsResourceRecord>emptyList(), false);
    }

    int getStatus() {
        return status;
    }

    List<DnsResourceRecord> getAnswers() {
        return answers;
    }

    boolean isFromWildcard() {
        return fromWildcard;
    }

    int rcode() {
        if (status == STATUS_NXDOMAIN) {
            return DnsMessage.RCODE_NXDOMAIN;
        }
        return DnsMessage.RCODE_NOERROR;
    }

    private static List<DnsResourceRecord> copy(List<DnsResourceRecord> answers) {
        if (answers == null || answers.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(answers);
    }
}
