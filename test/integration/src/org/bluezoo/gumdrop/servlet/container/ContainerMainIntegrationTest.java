/*
 * ContainerMainIntegrationTest.java
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

package org.bluezoo.gumdrop.servlet.container;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import org.bluezoo.gumdrop.http.HttpServer;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Integration test of the {@link ContainerMain} launcher against real
 * configuration files in a temporary directory: configuration discovery
 * through {@code GUMDROP_HOME}, the missing and invalid configuration
 * failure paths, and hand-off of a composed server to the launcher. The
 * launcher is a recording mock, so no listener is opened and nothing blocks.
 * Loading completes through a latch inside {@code run}, so the tests do not
 * depend on timing.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ContainerMainIntegrationTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private HttpServer launched;
    private int launches;

    private final ContainerMain.Launcher recorder = new ContainerMain.Launcher() {
        @Override
        public void launch(HttpServer server) {
            launched = server;
            launches++;
        }
    };

    private File write(File dir, String name, String content) throws IOException {
        File f = new File(dir, name);
        FileOutputStream out = new FileOutputStream(f);
        try {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        } finally {
            out.close();
        }
        return f;
    }

    private int run(String[] args, Map<String, String> env, ByteArrayOutputStream errBytes)
            throws Exception {
        PrintStream err = new PrintStream(errBytes, true, "UTF-8");
        return ContainerMain.run(args, env, err, recorder);
    }

    @Test
    public void homeConfigurationIsDiscovered() throws Exception {
        File conf = tmp.newFolder("home", "conf");
        File server = write(conf, "server.xml", "<server><listener port='8080'/></server>");
        Map<String, String> env = new HashMap<String, String>();
        env.put("GUMDROP_HOME", new File(tmp.getRoot(), "home").getPath());
        File resolved = ContainerMain.resolveConfigFile(new String[0], env);
        assertEquals(server, resolved);
    }

    @Test
    public void homeWithoutConfigurationFallsBackToWorkingDirectoryDefault() throws Exception {
        Map<String, String> env = new HashMap<String, String>();
        env.put("GUMDROP_HOME", tmp.getRoot().getPath());
        File resolved = ContainerMain.resolveConfigFile(new String[0], env);
        assertEquals(new File("conf/server.xml"), resolved);
    }

    @Test
    public void validConfigurationIsHandedToLauncher() throws Exception {
        File f = write(tmp.getRoot(), "server.xml", "<server><listener port='8080'/></server>");
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int status = run(new String[] {f.getPath()}, new HashMap<String, String>(), err);
        assertEquals(0, status);
        assertEquals(1, launches);
        assertNotNull(launched);
        assertEquals("", err.toString("UTF-8"));
    }

    @Test
    public void missingConfigurationReportsAndDoesNotLaunch() throws Exception {
        File absent = new File(tmp.getRoot(), "absent.xml");
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int status = run(new String[] {absent.getPath()}, new HashMap<String, String>(), err);
        assertEquals(1, status);
        assertEquals(0, launches);
        String text = err.toString("UTF-8");
        assertTrue(text, text.startsWith("gumdrop: no server.xml found"));
    }

    @Test
    public void directoryAsConfigurationIsTreatedAsMissing() throws Exception {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int status = run(new String[] {tmp.getRoot().getPath()}, new HashMap<String, String>(), err);
        assertEquals(1, status);
        assertEquals(0, launches);
    }

    @Test
    public void invalidConfigurationReportsParseErrorAndDoesNotLaunch() throws Exception {
        File f = write(tmp.getRoot(), "bad.xml", "<server><listener port='8080'></server>");
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int status = run(new String[] {f.getPath()}, new HashMap<String, String>(), err);
        assertEquals(1, status);
        assertEquals(0, launches);
        assertNull(launched);
        String text = err.toString("UTF-8");
        assertTrue(text, text.startsWith("gumdrop: "));
        assertTrue(text, text.indexOf("parse error") >= 0);
    }
}
