/*
 * DMARCMessageHandlerTest.java
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

package org.bluezoo.gumdrop.smtp.auth;

import org.junit.Test;
import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.bluezoo.gumdrop.mime.rfc5322.EmailAddress;

/**
 * Unit tests for {@link DMARCMessageHandler} — RFC 7489 §7.6.
 *
 * <p>Covers GHSA-j73j-4776-j4j8: a message containing more than one
 * {@code From:} header (which RFC 5322 §3.6.2 forbids) must not have its
 * From domain silently resolved from whichever occurrence happens to be
 * last; that domain must instead be invalidated so DMARC cannot pass on it.
 */
public class DMARCMessageHandlerTest {

    private static class RecordingCallback implements DMARCMessageHandler.FromDomainCallback {
        final List<String> domains = new ArrayList<>();

        @Override
        public void onFromDomain(String domain) {
            domains.add(domain);
        }
    }

    private static List<EmailAddress> address(String localPart, String domain) {
        return Collections.singletonList(new EmailAddress(null, localPart, domain, true));
    }

    @Test
    public void testSingleFromHeaderReportsDomain() throws Exception {
        RecordingCallback callback = new RecordingCallback();
        DMARCMessageHandler handler = new DMARCMessageHandler(callback, null);

        handler.addressHeader("From", address("alice", "example.com"));

        assertEquals(Collections.singletonList("example.com"), callback.domains);
    }

    @Test
    public void testDuplicateFromHeaderInvalidatesDomain() throws Exception {
        RecordingCallback callback = new RecordingCallback();
        DMARCMessageHandler handler = new DMARCMessageHandler(callback, null);

        // First occurrence: spoofed/attacker-chosen address.
        handler.addressHeader("From", address("attacker", "evil.example"));
        // Second occurrence: legitimately signed address at the real domain.
        handler.addressHeader("From", address("real", "example.com"));

        // Whatever domain a normal downstream MUA might display, the
        // handler must not hand DMARCValidator a usable domain once a
        // duplicate From is seen — the last reported value must be null,
        // not either candidate domain.
        assertFalse("duplicate From must not report a usable domain",
                callback.domains.contains("example.com") && callback.domains.size() == 1);
        assertNull("the final reported domain must be invalidated",
                callback.domains.get(callback.domains.size() - 1));
    }

    @Test
    public void testThirdFromHeaderStaysInvalidated() throws Exception {
        RecordingCallback callback = new RecordingCallback();
        DMARCMessageHandler handler = new DMARCMessageHandler(callback, null);

        handler.addressHeader("From", address("a", "one.example"));
        handler.addressHeader("From", address("b", "two.example"));
        handler.addressHeader("From", address("c", "three.example"));

        assertNull(callback.domains.get(callback.domains.size() - 1));
    }
}
