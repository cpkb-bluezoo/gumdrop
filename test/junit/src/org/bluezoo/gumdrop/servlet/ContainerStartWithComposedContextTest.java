/*
 * ContainerStartWithComposedContextTest.java
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

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;

import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.GumdropConfig;

/**
 * Regression test: {@link Container#start()} (via {@code initContexts()})
 * unconditionally re-called {@link Context#setContainer(Container)} on
 * every deployed context, which threw {@code IllegalStateException
 * ("Container already set")} for any context built with the {@link
 * Context#Context(Container, String, File)} constructor -- the
 * documented, composition-first way to add a context (see
 * docs/COMPOSITION.md) -- since that constructor already assigns the
 * container directly. Only the older no-arg-constructor-plus-external-setter
 * wiring path (XML DI) was actually exercised by {@code start()} before
 * this fix.
 */
public class ContainerStartWithComposedContextTest {

    private Container container;
    private File webappRoot;
    private Gumdrop gumdrop;

    @Before
    public void setUp() throws Exception {
        container = new Container();
        webappRoot = Files.createTempDirectory("gumdrop-container-start-test").toFile();
        gumdrop = Gumdrop.boot(GumdropConfig.create().workerThreads(1));
    }

    @After
    public void tearDown() throws InterruptedException {
        deleteRecursively(webappRoot);
        gumdrop.shutdown();
        gumdrop.join();
    }

    @Test
    public void startDoesNotThrowForContextBuiltWithContainerConstructor() {
        Context context = new Context(container, "", webappRoot);
        container.addContext(context);
        container.start(gumdrop);
    }

    private static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        file.delete();
    }

}
