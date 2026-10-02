/*
 * IMAPSessionTelemetryTest.java
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

package org.bluezoo.gumdrop.imap;

import java.util.ArrayList;
import java.util.List;

import org.junit.After;

import org.bluezoo.gumdrop.telemetry.Trace;
import org.bluezoo.gumdrop.testsupport.RecordingStubEndpoint;

import static org.junit.Assert.*;

/**
 * Re-runs the full authenticated-session scenarios of
 * {@link IMAPSessionCoverageTest} with telemetry enabled on the endpoint,
 * so every span, attribute and event recording branch of the handler is
 * taken, and checks that each connection produced a populated trace.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class IMAPSessionTelemetryTest extends IMAPSessionCoverageTest {

    private final List<RecordingStubEndpoint> endpoints =
            new ArrayList<RecordingStubEndpoint>();
    private final List<ImapTelemetryEndpoint.CapturingConfig> configs =
            new ArrayList<ImapTelemetryEndpoint.CapturingConfig>();

    @Override
    protected RecordingStubEndpoint newEndpoint() {
        RecordingStubEndpoint e = new RecordingStubEndpoint(143);
        ImapTelemetryEndpoint.CapturingConfig config =
                new ImapTelemetryEndpoint.CapturingConfig();
        e.setTelemetryConfig(config);
        endpoints.add(e);
        configs.add(config);
        return e;
    }

    @After
    public void checkTraces() {
        assertFalse(endpoints.isEmpty());
        for (int i = 0; i < endpoints.size(); i++) {
            List<Trace> traces = configs.get(i).traces;
            assertEquals(1, traces.size());
            List<String> events = new ArrayList<String>();
            List<String> attrs = new ArrayList<String>();
            ImapTelemetryEndpoint.collect(traces.get(0).getRootSpan(),
                    events, attrs);
            assertTrue(attrs.toString(), attrs.contains("rpc.system"));
            assertTrue(attrs.toString(), attrs.contains("net.peer.ip"));
        }
    }
}
