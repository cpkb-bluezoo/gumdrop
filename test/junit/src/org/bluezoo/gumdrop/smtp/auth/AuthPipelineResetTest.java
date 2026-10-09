/*
 * AuthPipelineResetTest.java
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

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.bluezoo.gumdrop.dns.DnsMessage;
import org.bluezoo.gumdrop.dns.DnsQueryCallback;
import org.bluezoo.gumdrop.dns.DnsQuestion;
import org.bluezoo.gumdrop.dns.DnsResourceRecord;
import org.bluezoo.gumdrop.dns.client.DnsResolver;
import org.bluezoo.gumdrop.mime.rfc5322.EmailAddress;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * An SPF answer that arrives after the pipeline was reset (the transaction
 * ended or the connection closed while DNS was in flight) must be dropped
 * rather than dereferencing the cleared per-message state.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class AuthPipelineResetTest {

    private static final class HoldingResolver extends DnsResolver {
        final List<DnsQueryCallback> held = new ArrayList<DnsQueryCallback>();

        HoldingResolver() {
            super();
        }

        @Override
        public void queryTXT(String name, DnsQueryCallback callback) {
            held.add(callback);
        }

        void answerAll() {
            int flags = DnsMessage.FLAG_QR | DnsMessage.FLAG_RA
                    | DnsMessage.RCODE_NXDOMAIN;
            for (int i = 0; i < held.size(); i++) {
                held.get(i).onResponse(new DnsMessage(1, flags,
                        Collections.<DnsQuestion>emptyList(),
                        Collections.<DnsResourceRecord>emptyList(),
                        Collections.<DnsResourceRecord>emptyList(),
                        Collections.<DnsResourceRecord>emptyList()));
            }
        }
    }

    @Test
    public void spfAnswerAfterResetIsDropped() throws Exception {
        HoldingResolver resolver = new HoldingResolver();
        final List<SpfResult> seen = new ArrayList<SpfResult>();
        AuthPipeline pipeline = new AuthPipeline.Builder(resolver,
                InetAddress.getByName("192.0.2.1"), "mail.example.com")
                .onSPF(new SpfCallback() {
                    @Override
                    public void spfResult(SpfResult result, String explanation) {
                        seen.add(result);
                    }
                })
                .build();

        pipeline.mailFrom(new EmailAddress(null, "a", "example.com", true));
        assertTrue("the SPF lookup is in flight", resolver.held.size() > 0);
        pipeline.reset();

        resolver.answerAll();

        assertEquals("a result for an ended transaction is not delivered",
                0, seen.size());
    }
}
