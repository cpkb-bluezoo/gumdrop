/*
 * ServerXmlLoaderFileIntegrationTest.java
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

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.bluezoo.gumdrop.http.HttpServer;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Integration test of the asynchronous file-reading entry point of
 * {@link ServerXmlLoader} against real files in a temporary directory (a
 * real {@code AsynchronousFileChannel}). The chunking logic is unit tested
 * by {@code ServerXmlLoaderChunksTest}. Completion is signalled by a
 * latch, so the tests do not depend on timing.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ServerXmlLoaderFileIntegrationTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private HttpServer server;
    private String error;

    private File write(String name, String content) throws IOException {
        File f = new File(tmp.getRoot(), name);
        FileOutputStream out = new FileOutputStream(f);
        try {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        } finally {
            out.close();
        }
        return f;
    }

    private void load(File file) throws InterruptedException {
        final CountDownLatch done = new CountDownLatch(1);
        ServerXmlLoader.load(file, new ServerXmlLoader.Callback() {
            @Override
            public void onServer(HttpServer s) {
                server = s;
                done.countDown();
            }

            @Override
            public void onError(String e) {
                error = e;
                done.countDown();
            }
        });
        assertTrue("loader did not complete", done.await(30, TimeUnit.SECONDS));
    }

    @Test
    public void loadsServerFromFileWithContext() throws Exception {
        File f = write("server.xml", "<server><context path='/app' root='webapp' distributable='true'/>"
                + "<listener port='8080'/></server>");
        load(f);
        assertNull(error, error);
        assertNotNull(server);
    }

    @Test
    public void loadsFileLargerThanOneReadBuffer() throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("<server><!--");
        for (int i = 0; i < 3000; i++) {
            sb.append("padding padding ");
        }
        sb.append("--><listener port='8080'/></server>");
        File f = write("big.xml", sb.toString());
        load(f);
        assertNull(error, error);
        assertNotNull(server);
    }

    @Test
    public void missingFileReportsError() throws Exception {
        load(new File(tmp.getRoot(), "absent.xml"));
        assertNull(server);
        assertNotNull(error);
        assertTrue(error, error.startsWith("Cannot open"));
    }

    @Test
    public void malformedFileReportsParseError() throws Exception {
        File f = write("bad.xml", "<server><listener port='8080'></server>");
        load(f);
        assertNull(server);
        assertNotNull(error);
        assertTrue(error, error.indexOf("parse error") >= 0);
    }

    @Test
    public void emptyFileReportsError() throws Exception {
        File f = write("empty.xml", "");
        load(f);
        assertNull(server);
        assertNotNull(error);
    }

    private static final String REALM = "<realm><user name='bob' password='pw'/></realm>";

    @Test
    public void realmHrefResolvesAgainstConfigurationDirectory() throws Exception {
        write("realm.xml", REALM);
        File f = write("server.xml", "<server><realm name='r' "
                + "class='org.bluezoo.gumdrop.auth.BasicRealm' href='realm.xml'/>"
                + "<listener port='8080'/></server>");
        load(f);
        assertNull(error, error);
        assertNotNull(server);
    }

    @Test
    public void absoluteRealmHrefIsUsedAsGiven() throws Exception {
        File realm = write("abs-realm.xml", REALM);
        File f = write("server.xml", "<server><realm name='r' "
                + "class='org.bluezoo.gumdrop.auth.BasicRealm' href='" + realm.getAbsolutePath()
                + "'/><listener port='8080'/></server>");
        load(f);
        assertNull(error, error);
        assertNotNull(server);
    }
}
