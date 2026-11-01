/*
 * ImapServerMetricsTest.java
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

import org.junit.Test;

import org.bluezoo.gumdrop.telemetry.TelemetryConfig;

/**
 * Exercises every recording method of {@link ImapServerMetrics} against a
 * default {@link TelemetryConfig}; the instruments must accept updates
 * without throwing.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ImapServerMetricsTest {

    @Test
    public void allRecordingMethodsAcceptUpdates() {
        ImapServerMetrics m = new ImapServerMetrics(new TelemetryConfig());
        m.connectionOpened();
        m.starttlsUpgraded();
        m.authAttempt("PLAIN");
        m.authSuccess("PLAIN");
        m.authAttempt("SCRAM-SHA-256");
        m.authFailure("SCRAM-SHA-256");
        m.commandExecuted("SELECT", 12.5, true);
        m.commandExecuted("FETCH", 3.0, false);
        m.idleStarted();
        m.idleEnded();
        m.messagesFetched(10);
        m.messagesAppended(1);
        m.messagesDeleted(2);
        m.messagesCopied(3);
        m.connectionClosed(1500.0);
    }
}
