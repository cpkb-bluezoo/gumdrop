/*
 * ContainerConfigurationTest.java
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

package org.bluezoo.gumdrop.servlet;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.ServletException;

import org.bluezoo.gumdrop.servlet.jndi.Resource;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.xml.sax.Attributes;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Covers {@link Container} configuration, context lookup, the worker pool,
 * the access log and the start/destroy lifecycle without any network.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ContainerConfigurationTest {

    /** Resource whose lifecycle is recorded. */
    private static final class Probe extends Resource {
        final String name;
        final boolean failInit;
        boolean initialised;
        boolean closed;

        Probe(String name, boolean failInit) {
            this.name = name;
            this.failInit = failInit;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getClassName() {
            return "java.lang.StringBuilder";
        }

        @Override
        public String getInterfaceName() {
            return "java.lang.CharSequence";
        }

        @Override
        public void addProperty(String key, String value) {
        }

        @Override
        public void init(Attributes config) {
        }

        @Override
        public void init() throws ServletException {
            if (failInit) {
                throw new ServletException("init failed");
            }
            initialised = true;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    public MemoryFolder tmp = new MemoryFolder();

    private String savedFactory;
    private Container container;

    @Before
    public void setUp() {
        savedFactory = System.getProperty("java.naming.factory.initial");
        SharedContainer.get();
        container = new Container() {
            @Override
            HotDeploymentThread newHotDeploymentThread() {
                return new HotDeploymentThread(this, new MockWatchService()) {
                    @Override
                    public synchronized void start() {
                        // no live thread in a unit test
                    }
                };
            }
        };
    }

    @After
    public void tearDown() {
        container.destroy();
        if (savedFactory == null) {
            System.clearProperty("java.naming.factory.initial");
        } else {
            System.setProperty("java.naming.factory.initial", savedFactory);
        }
    }

    private Context context(String path, String name) throws Exception {
        Path root = tmp.newFolder(name);
        return new Context(container, path, root);
    }

    @Test
    public void testContextRegistryAndLongestPrefixLookup() throws Exception {
        Context root = context("", "root");
        Context app = context("/app", "app");
        Context deep = context("/app/deep", "deep");
        container.addContext(root);
        container.addContext(app);
        container.addContext(deep);
        assertEquals(3, container.getContexts().size());
        assertSame(app, container.getContext("/app"));
        assertNull(container.getContext("/nothing"));
        assertSame(deep, container.getContextByPath("/app/deep/x"));
        assertSame(app, container.getContextByPath("/app/other"));
        assertSame(root, container.getContextByPath("/zzz"));

        List<Context> replacement = new ArrayList<Context>();
        replacement.add(app);
        container.setContexts(replacement);
        assertEquals(1, container.getContexts().size());
        assertNull(container.getContextByPath("/zzz"));
        assertSame(app, container.getContextByPath("/app/x"));
    }

    @Test
    public void testRealmsAndResourcesCanBeReplaced() {
        container.addRealm("a", null);
        Map<String, org.bluezoo.gumdrop.auth.Realm> realms = new LinkedHashMap<String, org.bluezoo.gumdrop.auth.Realm>();
        realms.put("b", null);
        container.setRealms(realms);
        assertEquals(1, container.realms.size());
        assertTrue(container.realms.containsKey("b"));
        container.addResource(new Probe("r1", false));
        List<Resource> list = new ArrayList<Resource>();
        list.add(new Probe("r2", false));
        container.setResources(list);
        assertEquals(1, container.resources.size());
    }

    @Test
    public void testClusterConfigurationValidation() throws Exception {
        try {
            container.setClusterKey(new byte[5]);
            fail("short key");
        } catch (IllegalArgumentException e) {
            assertNotNull(e.getMessage());
        }
        try {
            container.setClusterKey(null);
            fail("null key");
        } catch (IllegalArgumentException e) {
            assertNotNull(e.getMessage());
        }
        byte[] key = new byte[32];
        key[0] = 7;
        container.setClusterKey(key);
        key[0] = 9;
        assertEquals(7, container.getClusterKey()[0]);
        container.setClusterPort(1234);
        assertEquals(1234, container.getClusterPort());
        try {
            container.setClusterGroupAddress(InetAddress.getByAddress(new byte[] { 10, 0, 0, 1 }));
            fail("unicast group");
        } catch (IllegalArgumentException e) {
            assertNotNull(e.getMessage());
        }
        InetAddress group = InetAddress.getByAddress(new byte[] { (byte) 224, 0, 5, 5 });
        container.setClusterGroupAddress(group);
        assertEquals(group, container.getClusterGroupAddress());
        container.setClusterGroupAddress(null);
        assertNull(container.getClusterGroupAddress());
        Set<String> allowed = new HashSet<String>();
        allowed.add("com.example.Safe");
        container.setReplicationAllowedClasses(allowed);
        container.setReplicationAllowedClasses(null);
        container.setHotDeploy(true);
        assertTrue(container.hotDeploy);
    }

    @Test
    public void testWorkerPoolSettings() {
        assertEquals(8192, container.getBufferSize());
        container.setBufferSize(10);
        assertEquals(1024, container.getBufferSize());
        container.setBufferSize(4096);
        assertEquals(4096, container.getBufferSize());
        container.setWorkerCorePoolSize(3);
        container.setWorkerMaximumPoolSize(9);
        container.setWorkerKeepAlive(Duration.ofSeconds(5));
        assertEquals(3, container.getWorkerThreadPool().getCorePoolSize());
        assertEquals(9, container.getWorkerThreadPool().getMaximumPoolSize());
        assertEquals(Duration.ofSeconds(5), container.getWorkerKeepAlive());
        assertNotNull(container.getAsyncTimeoutScheduler());
    }

    @Test
    public void testExecuteWorkerRunsTasksAndReportsRejection() throws Exception {
        final CountDownLatch ran = new CountDownLatch(1);
        container.executeWorker(new Runnable() {
            @Override
            public void run() {
                ran.countDown();
            }
        }, null);
        assertTrue(ran.await(30, TimeUnit.SECONDS));

        container.getWorkerThreadPool().shutdown();
        final boolean[] rejected = new boolean[1];
        container.executeWorker(new Runnable() {
            @Override
            public void run() {
                fail("pool is shut down");
            }
        }, new Runnable() {
            @Override
            public void run() {
                rejected[0] = true;
            }
        });
        assertTrue(rejected[0]);
        container.executeWorker(new Runnable() {
            @Override
            public void run() {
                fail("pool is shut down");
            }
        }, null);
    }

    @Test
    public void testAccessLogWritesLinesAndBadPathIsReported() throws Exception {
        container.log("ignored without a log");
        Path log = tmp.getRoot().resolve("access.log");
        container.setAccessLog(log);
        container.log("GET /one");
        container.log("GET /two");
        String text = new String(Files.readAllBytes(log), StandardCharsets.UTF_8);
        assertTrue(text.contains("GET /one"));
        assertTrue(text.contains("GET /two"));
        container.setAccessLog(tmp.getRoot());
    }

    @Test
    public void testDigestLookupAndDistributableContexts() throws Exception {
        Context plain = context("/plain", "plain");
        Context shared = context("/shared", "shared");
        plain.digest = new byte[] { 1, 2, 3 };
        shared.digest = new byte[] { 4, 5, 6 };
        shared.distributable = true;
        container.addContext(plain);
        container.addContext(shared);
        assertSame(shared, container.getContextByDigest(new byte[] { 4, 5, 6 }));
        assertNull(container.getContextByDigest(new byte[] { 4, 5, 7 }));
        assertNull(container.getContextByDigest(new byte[] { 4, 5 }));
        Collection<?> distributable = (Collection<?>) toCollection(container.getDistributableContexts());
        assertEquals(1, distributable.size());
        assertFalse(Container.match(null, new byte[1]));
        assertFalse(Container.match(new byte[1], null));
        assertTrue(Container.match(new byte[0], new byte[0]));
        container.registerContextWithCluster(shared);
        container.unregisterContextFromCluster(shared);
    }

    private static Collection<Object> toCollection(Iterable<?> iterable) {
        List<Object> list = new ArrayList<Object>();
        for (Object o : iterable) {
            list.add(o);
        }
        return list;
    }

    @Test
    public void testInitContextsBindsResourcesLoadsContextsAndDestroys() throws Exception {
        Probe good = new Probe("res/good", false);
        Probe bad = new Probe("res/bad", true);
        container.addResource(good);
        container.addResource(bad);

        Path broken = tmp.newFolder("broken");
        MemoryFolder.write(broken, "WEB-INF/web.xml", "<web-app><unclosed>");
        container.addContext(new Context(container, "/broken", broken));

        Path dist = tmp.newFolder("dist");
        MemoryFolder.write(dist, "WEB-INF/web.xml",
                "<web-app xmlns=\"https://jakarta.ee/xml/ns/jakartaee\" version=\"6.1\">"
                + "<distributable/></web-app>");
        Context distributable = new Context(container, "/dist", dist);
        container.addContext(distributable);
        container.setHotDeploy(true);

        container.init();
        container.initContexts(null);
        assertTrue(container.started);
        assertTrue(good.initialised);
        assertNotNull(container.hotDeploymentThread);
        assertTrue(distributable.distributable);
        container.initContexts(null);

        container.destroy();
        assertFalse(container.started);
        assertTrue(good.closed);
        assertNull(container.hotDeploymentThread);
        container.destroy();
    }

    @Test
    public void testDestroyBeforeStartOnlyStopsTheRuntime() {
        container.destroy();
        assertFalse(container.started);
    }

    @Test
    public void testDefaultHotDeployFollowsEnvironment() {
        String env = System.getenv("GUMDROP_HOT_DEPLOY");
        boolean expected = env != null && Boolean.parseBoolean(env.trim());
        assertEquals(expected, new Container().hotDeploy);
    }
}
