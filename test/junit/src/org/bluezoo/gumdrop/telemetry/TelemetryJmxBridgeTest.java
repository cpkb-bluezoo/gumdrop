/*
 * TelemetryJmxBridgeTest.java
 * Copyright (C) 2025 Chris Burdess
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


package org.bluezoo.gumdrop.telemetry;

import org.bluezoo.gumdrop.telemetry.metrics.Attributes;
import org.bluezoo.gumdrop.telemetry.metrics.DoubleHistogram;
import org.bluezoo.gumdrop.telemetry.metrics.LongCounter;
import org.bluezoo.gumdrop.telemetry.metrics.Meter;
import org.bluezoo.gumdrop.telemetry.metrics.ObservableCallback;
import org.bluezoo.gumdrop.telemetry.metrics.ObservableMeasurement;
import org.junit.After;
import org.junit.Test;

import javax.management.Attribute;
import javax.management.AttributeList;
import javax.management.AttributeNotFoundException;
import javax.management.MBeanAttributeInfo;
import javax.management.MBeanInfo;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;

import static org.junit.Assert.*;

/**
 * Tests for {@link TelemetryJMXBridge} against the platform MBean server,
 * and the JMX/shutdown lifecycle paths of {@link TelemetryConfig}.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class TelemetryJmxBridgeTest {

    private static final String NAME = "org.bluezoo.gumdrop:type=Telemetry";

    private TelemetryJMXBridge bridge;
    private TelemetryConfig config;

    @After
    public void tearDown() throws Exception {
        if (bridge != null) {
            bridge.unregister();
        }
        if (config != null) {
            config.shutdown();
        }
        MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        ObjectName on = new ObjectName(NAME);
        if (server.isRegistered(on)) {
            server.unregisterMBean(on);
        }
    }

    private TelemetryConfig metricsConfig() {
        TelemetryConfig c = new TelemetryConfig();
        c.setMetricsEnabled(true);
        return c;
    }

    @Test
    public void registerRefusedWhenMetricsDisabled() {
        config = new TelemetryConfig();
        config.setMetricsEnabled(false);
        bridge = new TelemetryJMXBridge(config);
        assertFalse(bridge.register());
        assertFalse(bridge.isRegistered());
        bridge.unregister();
    }

    @Test
    public void registerIsIdempotentAndSecondBridgeIsRefused() throws Exception {
        config = metricsConfig();
        bridge = new TelemetryJMXBridge(config);
        assertTrue(bridge.register());
        assertTrue(bridge.register());
        assertTrue(bridge.isRegistered());
        TelemetryJMXBridge other = new TelemetryJMXBridge(config);
        assertFalse(other.register());
        assertFalse(other.isRegistered());
        MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        assertTrue(server.isRegistered(new ObjectName(NAME)));
        bridge.unregister();
        assertFalse(bridge.isRegistered());
        assertFalse(server.isRegistered(new ObjectName(NAME)));
    }

    @Test
    public void unregisterWhenMBeanAlreadyGoneStillClearsFlag() throws Exception {
        config = metricsConfig();
        bridge = new TelemetryJMXBridge(config);
        assertTrue(bridge.register());
        MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        server.unregisterMBean(new ObjectName(NAME));
        bridge.unregister();
        assertFalse(bridge.isRegistered());
    }

    @Test
    public void attributesReflectCountersGaugesAndHistograms() throws Exception {
        config = metricsConfig();
        Meter meter = config.getMeter("jmx.test");
        LongCounter counter = meter.counterBuilder("jmx.requests").setDescription("reqs").build();
        counter.add(3);
        counter.add(4);
        DoubleHistogram hist = meter.histogramBuilder("jmx.latency").build();
        hist.record(2.0);
        hist.record(6.0);
        meter.gaugeBuilder("jmx.depth").buildWithCallback(new ObservableCallback() {
            @Override
            public void observe(ObservableMeasurement m) {
                m.record(11L);
            }
        });
        meter.gaugeBuilder("jmx.ratio").setDescription("ratio").buildWithCallback(new ObservableCallback() {
            @Override
            public void observe(ObservableMeasurement m) {
                m.record(0.5);
            }
        });
        bridge = new TelemetryJMXBridge(config);
        assertTrue(bridge.register());
        MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        ObjectName on = new ObjectName(NAME);
        assertEquals(7L, server.getAttribute(on, "jmx_requests"));
        assertEquals(11L, server.getAttribute(on, "jmx_depth"));
        assertEquals(0.5, ((Number) server.getAttribute(on, "jmx_ratio")).doubleValue(), 0.0001);
        assertEquals(2L, server.getAttribute(on, "jmx_latency_count"));
        assertEquals(8.0, ((Number) server.getAttribute(on, "jmx_latency_sum")).doubleValue(), 0.0001);
        assertEquals(2.0, ((Number) server.getAttribute(on, "jmx_latency_min")).doubleValue(), 0.0001);
        assertEquals(6.0, ((Number) server.getAttribute(on, "jmx_latency_max")).doubleValue(), 0.0001);
        try {
            server.getAttribute(on, "no_such_attribute");
            fail("expected AttributeNotFoundException");
        } catch (AttributeNotFoundException expected) {
            assertTrue(expected.getMessage().contains("no_such_attribute"));
        }
        AttributeList list = server.getAttributes(on, new String[] {"jmx_requests", "bogus"});
        assertEquals(1, list.size());
        MBeanInfo info = server.getMBeanInfo(on);
        boolean sawDescribed = false;
        boolean sawCount = false;
        for (MBeanAttributeInfo ai : info.getAttributes()) {
            if (ai.getName().equals("jmx_requests") && ai.getDescription().equals("reqs")) {
                sawDescribed = true;
            }
            if (ai.getName().equals("jmx_latency_count") && ai.getDescription().endsWith("(count)")) {
                sawCount = true;
            }
        }
        assertTrue(sawDescribed);
        assertTrue(sawCount);
        AttributeList setResult = server.setAttributes(on, new AttributeList());
        assertEquals(0, setResult.size());
    }

    @Test
    public void mixedLongAndDoublePointsAreSummedTogether() throws Exception {
        config = metricsConfig();
        Meter meter = config.getMeter("jmx.mixed");
        meter.gaugeBuilder("jmx.mix").buildWithCallback(new ObservableCallback() {
            @Override
            public void observe(ObservableMeasurement m) {
                m.record(4L, Attributes.of("k", "a"));
                m.record(0.5, Attributes.of("k", "b"));
            }
        });
        bridge = new TelemetryJMXBridge(config);
        assertTrue(bridge.register());
        MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        Object value = server.getAttribute(new ObjectName(NAME), "jmx_mix");
        assertEquals(4.5, ((Number) value).doubleValue(), 0.0001);
    }

    @Test
    public void mutationAndInvocationAreRejected() throws Exception {
        config = metricsConfig();
        bridge = new TelemetryJMXBridge(config);
        assertTrue(bridge.register());
        MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        ObjectName on = new ObjectName(NAME);
        try {
            server.setAttribute(on, new Attribute("x", Integer.valueOf(1)));
            fail("expected failure");
        } catch (Exception expected) {
            assertNotNull(expected);
        }
        try {
            server.invoke(on, "anything", new Object[0], new String[0]);
            fail("expected failure");
        } catch (Exception expected) {
            assertNotNull(expected);
        }
    }

    @Test
    public void configInitRegistersBridgeAndShutdownUnregistersIt() throws Exception {
        config = metricsConfig();
        config.setJmxBridgeEnabled(true);
        config.init();
        TelemetryJMXBridge created = config.getJmxBridge();
        assertNotNull(created);
        assertTrue(created.isRegistered());
        assertFalse(config.isShuttingDown());
        config.shutdown();
        assertTrue(config.isShuttingDown());
        assertNull(config.getJmxBridge());
        MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        assertFalse(server.isRegistered(new ObjectName(NAME)));
        config.shutdown();
    }
}
