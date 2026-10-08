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

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.bluezoo.gumdrop.crypto.NamedGroup;
import org.bluezoo.gumdrop.telemetry.LogLevel;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.testsupport.RecordingExporter;

/**
 * Mapping of the {@code named-groups} setting onto the TLS 1.2 / DTLS 1.2
 * engine's classical ECDHE groups. The warnings a factory emits about
 * entries it drops are read back from its telemetry configuration.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class Tls12NamedGroupConfigTest {

    private final RecordingExporter warnings = new RecordingExporter(LogLevel.WARN);
    private final TcpTransportFactory factory = new TcpTransportFactory();

    public Tls12NamedGroupConfigTest() {
        TelemetryConfig telemetry = new TelemetryConfig();
        telemetry.setExporter(warnings);
        factory.setTelemetryConfig(telemetry);
    }

    @Test
    public void unsetUsesEngineDefault() {
        assertNull(factory.resolveTls12NamedGroups(null, true));
    }

    @Test
    public void x25519AloneIsApplied() {
        List<NamedGroup> g = factory.resolveTls12NamedGroups("x25519", true);
        assertEquals(Arrays.asList(NamedGroup.X25519), g);
    }

    @Test
    public void classicalOrderIsKept() {
        List<NamedGroup> g = factory.resolveTls12NamedGroups("secp256r1:x25519", true);
        assertEquals(Arrays.asList(NamedGroup.SECP256R1, NamedGroup.X25519), g);
    }

    @Test
    public void hybridsOnlyWarnsAndFallsBackToDefault() {
        assertNull(factory.resolveTls12NamedGroups("X25519MLKEM768:SecP256r1MLKEM768", true));
        assertTrue("warned", warnings.records.size() >= 1);
        assertEquals(1, warnings.named("warn.tls12_named_groups_default").size());
        assertEquals("X25519MLKEM768:SecP256r1MLKEM768",
                warnings.named("warn.tls12_named_groups_default").get(0).getString("named_groups"));
    }

    @Test
    public void mixedListKeepsClassicalEntriesAndWarnsAboutTheRest() {
        List<NamedGroup> g = factory.resolveTls12NamedGroups("X25519MLKEM768:secp256r1:x25519", true);
        assertEquals(Arrays.asList(NamedGroup.SECP256R1, NamedGroup.X25519), g);
        assertEquals(1, warnings.records.size());
        assertEquals("warn.tls12_named_groups_ignored", warnings.records.get(0).getKey());
        assertEquals("X25519MLKEM768", warnings.records.get(0).getString("group"));
        assertEquals(TransportFactory.class.getName(), warnings.records.get(0).getScope());
    }

    @Test
    public void silentWhenSameValueAlsoDrivesTls13() {
        List<NamedGroup> g = factory.resolveTls12NamedGroups("X25519MLKEM768:x25519", false);
        assertEquals(Arrays.asList(NamedGroup.X25519), g);
        assertEquals(0, warnings.records.size());
    }

    @Test
    public void unknownGroupIsReported() {
        assertNull(factory.resolveTls12NamedGroups("nosuchgroup", true));
        assertEquals("warn.unrecognized_named_group", warnings.records.get(0).getKey());
        assertEquals("nosuchgroup", warnings.records.get(0).getString("group"));
    }
}
