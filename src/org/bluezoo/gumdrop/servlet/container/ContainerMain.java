/*
 * ContainerMain.java
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

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.http.HttpServer;

/**
 * Entry point for the stock servlet container distribution, invoked
 * reflectively by {@link org.bluezoo.gumdrop.Bootstrap} once it has set up
 * the container classloader.
 *
 * <p>Resolves {@code server.xml} in this order:
 * <ol>
 *   <li>{@code args[0]}, if given;</li>
 *   <li>the {@code GUMDROP_CONFIG} environment variable;</li>
 *   <li>{@code $GUMDROP_HOME/conf/server.xml};</li>
 *   <li>{@code conf/server.xml} under the current working directory.</li>
 * </ol>
 *
 * <p>{@link ServerXmlLoader} parses {@code server.xml} asynchronously (no
 * blocking file I/O or blocking parse calls); since there is nothing else
 * for the launching thread to do until the container is composed, {@code
 * main} waits on a latch for that one-time result before starting the
 * server, rather than threading a callback through the process entry point.
 *
 * <p>Anything more elaborate than "one server.xml, one JVM" — multiple
 * independently-configured containers, non-servlet protocols alongside it,
 * programmatic webapp discovery — is exactly what {@code web/configuration.html}
 * covers: write your own {@code main()} using {@link ServerXmlLoader} (or
 * skip it and compose {@link org.bluezoo.gumdrop.servlet.Container} /
 * {@link org.bluezoo.gumdrop.servlet.server.ServletRequestHandler} directly)
 * instead of this class.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ServerXmlLoader
 */
public final class ContainerMain {

    private ContainerMain() {
    }

    public static void main(String[] args) throws Exception {
        File configFile = resolveConfigFile(args);
        if (configFile == null || !configFile.isFile()) {
            System.err.println(
                    "gumdrop: no server.xml found (looked for: an explicit argument, "
                    + "$GUMDROP_CONFIG, $GUMDROP_HOME/conf/server.xml, ./conf/server.xml). "
                    + "See docs/CONTAINER-DEPLOYMENT.md.");
            System.exit(1);
            return;
        }

        final CountDownLatch loaded = new CountDownLatch(1);
        final AtomicReference<HttpServer> serverRef = new AtomicReference<HttpServer>();
        final AtomicReference<String> errorRef = new AtomicReference<String>();
        ServerXmlLoader.load(configFile, new ServerXmlLoader.Callback() {
            @Override
            public void onServer(HttpServer server) {
                serverRef.set(server);
                loaded.countDown();
            }

            @Override
            public void onError(String error) {
                errorRef.set(error);
                loaded.countDown();
            }
        });
        loaded.await();

        String error = errorRef.get();
        if (error != null) {
            System.err.println("gumdrop: " + error);
            System.exit(1);
            return;
        }

        Gumdrop gumdrop = Gumdrop.boot();
        gumdrop.addServer(serverRef.get());
    }

    private static File resolveConfigFile(String[] args) {
        if (args.length > 0 && !args[0].isEmpty()) {
            return new File(args[0]);
        }
        String fromEnv = System.getenv("GUMDROP_CONFIG");
        if (fromEnv != null && !fromEnv.isEmpty()) {
            return new File(fromEnv);
        }
        String gumdropHome = System.getenv("GUMDROP_HOME");
        if (gumdropHome != null && !gumdropHome.isEmpty()) {
            File fromHome = new File(new File(gumdropHome, "conf"), "server.xml");
            if (fromHome.isFile()) {
                return fromHome;
            }
        }
        return new File("conf/server.xml");
    }
}
