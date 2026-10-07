/*
 * Tls12NamedGroupConfigTest.java
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

package org.bluezoo.gumdrop;

import java.util.Arrays;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.bluezoo.gumdrop.crypto.NamedGroup;

/**
 * Mapping of the {@code named-groups} setting onto the TLS 1.2 / DTLS 1.2
 * engine's classical ECDHE groups.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class Tls12NamedGroupConfigTest {

    private static final class Capture extends Handler {
        int warnings;
        java.util.logging.Level previousLevel;

        @Override
        public void publish(LogRecord record) {
            if (record.getLevel().intValue() >= java.util.logging.Level.WARNING.intValue()) {
                warnings++;
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }

    private static Capture capture() {
        Capture c = new Capture();
        Logger log = Logger.getLogger(TransportFactory.class.getName());
        c.previousLevel = log.getLevel();
        log.setLevel(java.util.logging.Level.WARNING);
        log.addHandler(c);
        return c;
    }

    private static void release(Capture c) {
        Logger log = Logger.getLogger(TransportFactory.class.getName());
        log.removeHandler(c);
        log.setLevel(c.previousLevel);
    }

    @Test
    public void unsetUsesEngineDefault() {
        assertNull(TransportFactory.resolveTls12NamedGroups(null, true));
    }

    @Test
    public void x25519AloneIsApplied() {
        List<NamedGroup> g = TransportFactory.resolveTls12NamedGroups("x25519", true);
        assertEquals(Arrays.asList(NamedGroup.X25519), g);
    }

    @Test
    public void classicalOrderIsKept() {
        List<NamedGroup> g = TransportFactory.resolveTls12NamedGroups("secp256r1:x25519", true);
        assertEquals(Arrays.asList(NamedGroup.SECP256R1, NamedGroup.X25519), g);
    }

    @Test
    public void hybridsOnlyWarnsAndFallsBackToDefault() {
        Capture c = capture();
        try {
            assertNull(TransportFactory.resolveTls12NamedGroups("X25519MLKEM768:SecP256r1MLKEM768", true));
            assertTrue("warned", c.warnings >= 1);
        } finally {
            release(c);
        }
    }

    @Test
    public void mixedListKeepsClassicalEntriesAndWarnsAboutTheRest() {
        Capture c = capture();
        try {
            List<NamedGroup> g = TransportFactory.resolveTls12NamedGroups("X25519MLKEM768:secp256r1:x25519", true);
            assertEquals(Arrays.asList(NamedGroup.SECP256R1, NamedGroup.X25519), g);
            assertEquals(1, c.warnings);
        } finally {
            release(c);
        }
    }

    @Test
    public void silentWhenSameValueAlsoDrivesTls13() {
        Capture c = capture();
        try {
            List<NamedGroup> g = TransportFactory.resolveTls12NamedGroups("X25519MLKEM768:x25519", false);
            assertEquals(Arrays.asList(NamedGroup.X25519), g);
            assertEquals(0, c.warnings);
        } finally {
            release(c);
        }
    }
}
