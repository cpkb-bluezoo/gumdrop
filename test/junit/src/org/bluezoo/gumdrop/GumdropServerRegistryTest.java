/*
 * GumdropServerRegistryTest.java
 * Copyright (C) 2026 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 */

package org.bluezoo.gumdrop;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Workstream C.1.1 — {@link Gumdrop} protocol server registry API.
 */
public class GumdropServerRegistryTest {

    private Gumdrop gumdrop;

    @Before
    public void setUp() {
        gumdrop = Gumdrop.getInstance();
    }

    @After
    public void tearDown() {
        if (gumdrop == null) {
            return;
        }
        for (Server server : new ArrayList<Server>(gumdrop.getServers())) {
            gumdrop.removeServer(server);
        }
        if (gumdrop.isStarted()) {
            gumdrop.shutdown();
        }
    }

    @Test
    public void testAddServerBeforeStart() {
        RecordingServer server = new RecordingServer();
        gumdrop.addServer(server);
        assertEquals(1, gumdrop.getServers().size());
        assertSame(server, gumdrop.getServers().get(0));
        assertEquals(0, server.startCount);
    }

    @Test
    public void testAddServerAfterStartStartsImmediately() {
        gumdrop.start();
        RecordingServer server = new RecordingServer();
        gumdrop.addServer(server);
        assertEquals(1, server.startCount);
        assertEquals(1, gumdrop.getServers().size());
    }

    @Test
    public void testRemoveServerStopsAndUnregisters() {
        RecordingServer server = new RecordingServer();
        gumdrop.addServer(server);
        gumdrop.start();
        gumdrop.removeServer(server);
        assertEquals(1, server.stopCount);
        assertTrue(gumdrop.getServers().isEmpty());
    }

    @Test
    @SuppressWarnings("deprecation")
    public void testDeprecatedServiceApiDelegatesToServerRegistry() {
        RecordingServer server = new RecordingServer();
        gumdrop.addService(server);
        assertSame(server, gumdrop.getServers().get(0));
        assertEquals(1, gumdrop.getServices().size());
        gumdrop.removeService(server);
        assertTrue(gumdrop.getServers().isEmpty());
    }

    private static final class RecordingServer implements Service {
        int startCount;
        int stopCount;

        @Override
        @SuppressWarnings("rawtypes")
        public List getListeners() {
            return new ArrayList();
        }

        @Override
        public void start() {
            startCount++;
        }

        @Override
        public void stop() {
            stopCount++;
        }
    }
}
