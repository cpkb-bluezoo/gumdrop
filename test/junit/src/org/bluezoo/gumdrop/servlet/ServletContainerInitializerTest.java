/*
 * ServletContainerInitializerTest.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.servlet;

import org.bluezoo.gumdrop.servlet.sci.SciMarker;
import org.bluezoo.gumdrop.servlet.sci.SciMarkedClass;
import org.bluezoo.gumdrop.servlet.sci.TestSciHandlesTypesInitializer;
import org.bluezoo.gumdrop.servlet.sci.TestSciInitializer;
import org.bluezoo.gumdrop.servlet.sci.TestSciOrderListener;
import org.bluezoo.gumdrop.servlet.sci.TestSciServlet;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import javax.servlet.Servlet;
import javax.servlet.http.MappingMatch;

import static org.junit.Assert.*;

/**
 * Regression tests for issue #443: {@link javax.servlet.ServletContainerInitializer}
 * discovery and {@code onStartup} invocation during web application startup.
 */
public class ServletContainerInitializerTest {

    private Container container;
    private Context context;
    private File webappRoot;

    @Before
    public void setUp() throws Exception {
        container = new Container();
        container.init();
        webappRoot = Files.createTempDirectory("gumdrop-sci-test").toFile();
        context = new Context(container, "/sci", webappRoot);
    }

    @After
    public void tearDown() {
        if (context != null) {
            context.destroy();
        }
        deleteRecursively(webappRoot);
    }

    @Test
    public void sciRegistersServletWithoutWebXmlMapping() throws Exception {
        deploySciWebapp(false, false);

        context.load();
        context.init();

        assertTrue(context.getServletRegistrations().containsKey("sci-test"));

        ServletMatch match = new ServletMatch();
        context.matchServletMapping("/sci-test", match);
        assertEquals(MappingMatch.EXACT, match.mappingMatch);
        assertEquals("sci-test", match.servletDef.name);

        Servlet servlet = context.loadServlet(match.servletDef);
        assertEquals(TestSciServlet.class.getName(), servlet.getClass().getName());
    }

    @Test
    public void sciRunsBeforeServletContextListener() throws Exception {
        deploySciWebapp(true, false);

        context.load();
        context.init();

        assertEquals("listener-ran",
                context.getAttribute(TestSciOrderListener.ORDER_ATTRIBUTE));
    }

    @Test
    public void sciReceivesHandlesTypesMatchesWhenMetadataCompleteIsFalse()
            throws Exception {
        deploySciWebapp(false, true);

        context.load();
        context.init();

        Integer count = (Integer) context.getAttribute(
                TestSciHandlesTypesInitializer.HANDLES_TYPES_COUNT);
        assertNotNull(count);
        assertTrue("expected SciMarkedClass in @HandlesTypes set, got " + count,
                count.intValue() >= 1);
    }

    @Test
    public void sciReceivesEmptyHandlesTypesSetWhenMetadataCompleteIsTrue()
            throws Exception {
        deploySciWebapp(false, true);
        writeWebXml("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<web-app xmlns=\"http://xmlns.jcp.org/xml/ns/javaee\"\n"
                + "         version=\"3.1\"\n"
                + "         metadata-complete=\"true\">\n"
                + "</web-app>\n");

        context.load();
        context.init();

        Integer count = (Integer) context.getAttribute(
                TestSciHandlesTypesInitializer.HANDLES_TYPES_COUNT);
        assertNotNull(count);
        assertEquals(0, count.intValue());
    }

    private void deploySciWebapp(boolean withOrderListener, boolean withHandlesTypesSci)
            throws Exception {
        copyClass(TestSciServlet.class);
        copyClass(TestSciInitializer.class);
        if (withOrderListener) {
            copyClass(TestSciOrderListener.class);
            writeWebXml("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                    + "<web-app xmlns=\"http://xmlns.jcp.org/xml/ns/javaee\"\n"
                    + "         version=\"3.1\">\n"
                    + "  <listener>\n"
                    + "    <listener-class>"
                    + TestSciOrderListener.class.getName()
                    + "</listener-class>\n"
                    + "  </listener>\n"
                    + "</web-app>\n");
        }
        if (withHandlesTypesSci) {
            copyClass(SciMarker.class);
            copyClass(SciMarkedClass.class);
            copyClass(TestSciHandlesTypesInitializer.class);
            appendServiceProvider(TestSciHandlesTypesInitializer.class.getName());
        }
        appendServiceProvider(TestSciInitializer.class.getName());
    }

    private void writeWebXml(String content) throws Exception {
        File webInf = new File(webappRoot, "WEB-INF");
        Files.createDirectories(webInf.toPath());
        Files.write(new File(webInf, "web.xml").toPath(),
                content.getBytes(StandardCharsets.UTF_8));
    }

    private void appendServiceProvider(String className) throws Exception {
        File servicesDir = new File(webappRoot,
                "WEB-INF/classes/META-INF/services");
        Files.createDirectories(servicesDir.toPath());
        File serviceFile = new File(servicesDir,
                "javax.servlet.ServletContainerInitializer");
        String existing = serviceFile.exists()
                ? new String(Files.readAllBytes(serviceFile.toPath()),
                        StandardCharsets.UTF_8)
                : "";
        Files.write(serviceFile.toPath(),
                (existing + className + "\n").getBytes(StandardCharsets.UTF_8));
    }

    private void copyClass(Class<?> type) throws Exception {
        String entryName = type.getSimpleName() + ".class";
        InputStream in = type.getResourceAsStream(entryName);
        assertNotNull("test class must be on the classpath: " + type.getName(), in);
        String destPath = type.getName().replace('.', '/') + ".class";
        File dest = new File(webappRoot, "WEB-INF/classes/" + destPath);
        Files.createDirectories(dest.getParentFile().toPath());
        Files.copy(in, dest.toPath());
    }

    private static void deleteRecursively(File file) {
        if (file == null) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        file.delete();
    }
}
