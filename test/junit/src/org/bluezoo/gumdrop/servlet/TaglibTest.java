/*
 * TaglibTest.java
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

import org.junit.Test;
import static org.junit.Assert.*;

import javax.servlet.descriptor.TaglibDescriptor;

/**
 * Unit tests for Taglib class.
 */
public class TaglibTest {

    @Test
    public void testTaglibUri() {
        Taglib taglib = new Taglib();
        taglib.taglibUri = "http://example.com/tags";

        assertEquals("http://example.com/tags", taglib.getTaglibURI());
    }

    @Test
    public void testTaglibLocation() {
        Taglib taglib = new Taglib();
        taglib.taglibLocation = "/WEB-INF/tlds/custom.tld";

        assertEquals("/WEB-INF/tlds/custom.tld", taglib.getTaglibLocation());
    }

    @Test
    public void testFullTaglib() {
        Taglib taglib = new Taglib();
        taglib.taglibUri = "http://java.sun.com/jsp/jstl/core";
        taglib.taglibLocation = "/WEB-INF/tlds/c.tld";

        assertEquals("http://java.sun.com/jsp/jstl/core", taglib.getTaglibURI());
        assertEquals("/WEB-INF/tlds/c.tld", taglib.getTaglibLocation());
    }

    @Test
    public void testNullValues() {
        Taglib taglib = new Taglib();

        assertNull(taglib.getTaglibURI());
        assertNull(taglib.getTaglibLocation());
    }

    @Test
    public void testImplementsTaglibDescriptor() {
        Taglib taglib = new Taglib();

        assertTrue(taglib instanceof TaglibDescriptor);
    }

    @Test
    public void testJstlCoreTaglib() {
        Taglib taglib = new Taglib();
        taglib.taglibUri = "http://java.sun.com/jsp/jstl/core";
        taglib.taglibLocation = "/WEB-INF/lib/jstl.jar";

        assertEquals("http://java.sun.com/jsp/jstl/core", taglib.getTaglibURI());
    }

    @Test
    public void testJstlFmtTaglib() {
        Taglib taglib = new Taglib();
        taglib.taglibUri = "http://java.sun.com/jsp/jstl/fmt";
        taglib.taglibLocation = "/WEB-INF/tlds/fmt.tld";

        assertEquals("http://java.sun.com/jsp/jstl/fmt", taglib.getTaglibURI());
    }

    @Test
    public void testCustomTaglib() {
        Taglib taglib = new Taglib();
        taglib.taglibUri = "/custom-tags";
        taglib.taglibLocation = "/WEB-INF/tags/custom.tld";

        assertEquals("/custom-tags", taglib.getTaglibURI());
        assertEquals("/WEB-INF/tags/custom.tld", taglib.getTaglibLocation());
    }

    @Test
    public void testSpringFormTaglib() {
        Taglib taglib = new Taglib();
        taglib.taglibUri = "http://www.springframework.org/tags/form";
        taglib.taglibLocation = "/WEB-INF/lib/spring-webmvc.jar";

        assertEquals("http://www.springframework.org/tags/form", taglib.getTaglibURI());
        assertEquals("/WEB-INF/lib/spring-webmvc.jar", taglib.getTaglibLocation());
    }
}

