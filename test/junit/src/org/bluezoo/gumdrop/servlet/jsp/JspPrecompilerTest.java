/*
 * JspPrecompilerTest.java
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

package org.bluezoo.gumdrop.servlet.jsp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.bluezoo.gumdrop.servlet.MemoryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Exercises {@link JspPrecompiler}: configuration validation, JSP discovery,
 * serial compilation, error accounting and the command line
 * help.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class JspPrecompilerTest {

    public MemoryFolder tmp = new MemoryFolder();

    private PrintStream savedOut;
    private PrintStream savedErr;
    private ByteArrayOutputStream out;
    private ByteArrayOutputStream err;
    private Path webapp;

    @Before
    public void setUp() throws Exception {
        savedOut = System.out;
        savedErr = System.err;
        out = new ByteArrayOutputStream();
        err = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out, true, "UTF-8"));
        System.setErr(new PrintStream(err, true, "UTF-8"));
        webapp = tmp.newFolder("webapp");
    }

    @After
    public void tearDown() {
        System.setOut(savedOut);
        System.setErr(savedErr);
    }

    private void write(String path, String content) throws IOException {
        MemoryFolder.write(webapp, path, content);
    }

    private JspPrecompiler precompiler() throws Exception {
        JspPrecompiler p = new JspPrecompiler();
        p.setWebappRoot(webapp);
        p.setOutputDir(tmp.getRoot().resolve("out"));
        return p;
    }

    private String stdout() throws Exception {
        return out.toString("UTF-8");
    }

    private String stderr() throws Exception {
        return err.toString("UTF-8");
    }

    @Test
    public void missingWebappIsRejected() throws Exception {
        JspPrecompiler p = new JspPrecompiler();
        try {
            p.precompile();
            fail("expected IllegalStateException");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage() != null);
        }
        p.setWebappRoot(webapp.resolve("nonexistent"));
        try {
            p.precompile();
            fail("expected IllegalStateException");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage() != null);
        }
    }

    @Test
    public void missingOutputIsRejected() throws Exception {
        JspPrecompiler p = new JspPrecompiler();
        p.setWebappRoot(webapp);
        try {
            p.precompile();
            fail("expected IllegalStateException");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage() != null);
        }
    }

    @Test
    public void emptyWebappSucceedsQuietlyOrVerbosely() throws Exception {
        JspPrecompiler p = precompiler();
        assertTrue(p.precompile());
        assertEquals("", stdout());
        p.setVerbose(true);
        assertTrue(p.precompile());
        assertFalse(stdout().isEmpty());
        assertTrue(Files.isDirectory(tmp.getRoot().resolve("out")));
    }

    @Test
    public void compilesDiscoveredJspFilesSkippingMetaDirs() throws Exception {
        write("a.jsp", "Hello <%= 1 + 1 %>");
        write("sub/dir/b-page.JSP", "<%@ page contentType=\"text/plain\" %>b");
        write("WEB-INF/hidden.jsp", "<% broken java %>");
        write("META-INF/hidden2.jsp", "<% broken java %>");
        write("readme.txt", "not a jsp");
        JspPrecompiler p = precompiler();
        p.setVerbose(true);
        assertTrue(stderr(), p.precompile());
        assertTrue(p.getErrors().isEmpty());
        String o = stdout();
        assertTrue(o, o.contains("/a.jsp"));
        assertTrue(o, o.contains("/sub/dir/b-page.JSP"));
        assertFalse(o, o.contains("hidden"));
    }

    @Test
    public void compileErrorsAreCollectedAndStopWhenFailOnError() throws Exception {
        write("a_bad.jsp", "<% this is not java %>");
        write("b_bad.jsp", "<% neither is this %>");
        JspPrecompiler p = precompiler();
        assertFalse(p.precompile());
        List<String> errors = p.getErrors();
        assertFalse(errors.isEmpty());
        assertFalse(stderr().isEmpty());
        // returned list is a copy
        errors.clear();
        assertFalse(p.getErrors().isEmpty());
    }

    @Test
    public void continuesPastErrorsWhenFailOnErrorDisabled() throws Exception {
        write("a_bad.jsp", "<% this is not java %>");
        write("b_bad.jsp", "<% neither is this %>");
        write("good.jsp", "fine");
        JspPrecompiler p = precompiler();
        p.setFailOnError(false);
        p.setVerbose(true);
        assertFalse(p.precompile());
        String o = stdout();
        assertTrue(o, o.contains("/good.jsp"));
        assertTrue(o, o.contains("/a_bad.jsp"));
        assertTrue(o, o.contains("/b_bad.jsp"));
    }

    @Test
    public void unparseableJspIsAnError() throws Exception {
        write("x.jsp", "<%-- never closed");
        JspPrecompiler p = precompiler();
        p.setVerbose(true);
        boolean ok = p.precompile();
        assertFalse(stdout() + stderr() + p.getErrors(), ok);
        assertEquals(1, p.getErrors().size());
        assertTrue(p.getErrors().get(0).startsWith("/x.jsp"));
    }

    @Test
    public void threadCountIsClampedToOne() throws Exception {
        write("one.jsp", "1");
        JspPrecompiler p = precompiler();
        p.setThreadCount(-5);
        p.setPackageName("org.example.gen");
        assertTrue(stderr(), p.precompile());
    }

    @Test
    public void mainHelpPrintsUsage() throws Exception {
        JspPrecompiler.main(new String[] { "-help" });
        assertFalse(stdout().isEmpty());
    }
}
