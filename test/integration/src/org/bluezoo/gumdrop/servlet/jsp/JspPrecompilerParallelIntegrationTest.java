/*
 * JspPrecompilerParallelIntegrationTest.java
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

package org.bluezoo.gumdrop.servlet.jsp;

import java.nio.file.Path;

import org.junit.Test;

import org.bluezoo.gumdrop.servlet.MemoryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/**
 * Integration test: the parallel compilation path of {@link JspPrecompiler},
 * which runs a pool of compiler threads. The unit test covers the serial path.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class JspPrecompilerParallelIntegrationTest {

    @Test
    public void parallelCompilationHandlesAllFiles() throws Exception {
        MemoryFolder tmp = new MemoryFolder();
        Path webapp = tmp.newFolder("webapp");
        MemoryFolder.write(webapp, "one.jsp", "1");
        MemoryFolder.write(webapp, "two.jsp", "2");
        MemoryFolder.write(webapp, "three.jspf", "3");
        MemoryFolder.write(webapp, "four.jsp", "<% bad bad %>");
        JspPrecompiler p = new JspPrecompiler();
        p.setWebappRoot(webapp);
        p.setOutputDir(tmp.getRoot().resolve("out"));
        p.setThreadCount(3);
        p.setFailOnError(false);
        assertFalse(p.precompile());
        assertEquals(1, p.getErrors().size());
    }
}
