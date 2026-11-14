/*
 * QlogEventsErrorNamesTest.java
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

package org.bluezoo.gumdrop.quic;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * The names qlog gives transport error codes in {@code connection_closed}
 * (draft-ietf-quic-qlog-quic-events-14).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class QlogEventsErrorNamesTest {

    @Test
    public void transportErrorsHaveTheirRfc9000Names() {
        assertEquals("no_error", QlogEvents.transportErrorName(0x00));
        assertEquals("flow_control_error", QlogEvents.transportErrorName(0x03));
        assertEquals("protocol_violation", QlogEvents.transportErrorName(0x0a));
        assertEquals("no_viable_path", QlogEvents.transportErrorName(0x10));
    }

    @Test
    public void cryptoErrorsCarryTheTlsAlert() {
        assertEquals("crypto_error_0x128", QlogEvents.transportErrorName(0x128));
        assertEquals("crypto_error_0x100", QlogEvents.transportErrorName(0x100));
        assertEquals("crypto_error_0x1ff", QlogEvents.transportErrorName(0x1ff));
    }

    @Test
    public void otherCodesAreUnknown() {
        assertEquals("unknown", QlogEvents.transportErrorName(0x11));
        assertEquals("unknown", QlogEvents.transportErrorName(0x200));
        assertEquals("unknown", QlogEvents.transportErrorName(-1));
    }
}
