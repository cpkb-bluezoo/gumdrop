/*
 * ServerXmlLoaderIntegrationTest.java
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

package org.bluezoo.gumdrop.servlet;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.bluezoo.gumdrop.http.HttpServer;
import org.bluezoo.gumdrop.servlet.container.ServerXmlLoader;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Tests {@link ServerXmlLoader#load} reading a real server.xml from disk
 * through its asynchronous file channel, including relative path
 * resolution against the file's directory.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ServerXmlLoaderIntegrationTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final CountDownLatch done = new CountDownLatch(1);
    private final AtomicReference<HttpServer> server =
            new AtomicReference<HttpServer>();
    private final AtomicReference<String> error =
            new AtomicReference<String>();

    private void load(File file) throws Exception {
        ServerXmlLoader.load(file, new ServerXmlLoader.Callback() {
            @Override
            public void onServer(HttpServer s) {
                server.set(s);
                done.countDown();
            }

            @Override
            public void onError(String e) {
                error.set(e);
                done.countDown();
            }
        });
        assertTrue("loader callback", done.await(10, TimeUnit.SECONDS));
    }

    private File write(String xml) throws Exception {
        File f = tmp.newFile("server.xml");
        Files.write(f.toPath(), xml.getBytes(StandardCharsets.UTF_8));
        return f;
    }

    @Test
    public void loadsServerFromFile() throws Exception {
        File webapp = tmp.newFolder("webapp");
        load(write("<server><context path='/app' root='webapp'/>"
                + "<listener port='18999'/></server>"));
        assertNull(error.get(), error.get());
        assertNotNull(server.get());
        assertTrue(webapp.isDirectory());
    }

    @Test
    public void largeFileSpanningSeveralReadsParses() throws Exception {
        StringBuilder xml = new StringBuilder("<server>");
        for (int i = 0; i < 400; i++) {
            xml.append("<realm name='r").append(i)
                    .append("' class='org.bluezoo.gumdrop.auth.BasicRealm'/>");
        }
        xml.append("<listener port='18998'/></server>");
        load(write(xml.toString()));
        assertNull(error.get(), error.get());
        assertNotNull(server.get());
    }

    @Test
    public void missingFileReportsError() throws Exception {
        load(new File(tmp.getRoot(), "absent.xml"));
        assertNotNull(error.get());
        assertTrue(error.get(), error.get().contains("Cannot open"));
    }

    @Test
    public void malformedFileReportsError() throws Exception {
        load(write("<server><listener port='1'>"));
        assertNotNull(error.get());
        assertTrue(error.get(), error.get().contains("parse error"));
    }
}
