/*
 * TaglibRegistryTest.java
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

import jakarta.servlet.ServletContext;
import jakarta.servlet.descriptor.JspConfigDescriptor;
import jakarta.servlet.descriptor.TaglibDescriptor;

import org.junit.Test;
import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/**
 * Unit tests for TaglibRegistry.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class TaglibRegistryTest {

    static final String TLD = "<?xml version=\"1.0\"?>\n<taglib>\n"
            + "<short-name>t</short-name><uri>http://example.com/t</uri>\n"
            + "<tag><name>hi</name><tag-class>com.example.Hi</tag-class>"
            + "<body-content>empty</body-content></tag>\n</taglib>";

    @Test
    public void testResolveNullAndEmpty() throws IOException {
        TaglibRegistry r = new TaglibRegistry(JspStubSupport.context(new HashMap<String, String>(), null));
        assertNull(r.resolveTaglib(null));
        assertNull(r.resolveTaglib(""));
        assertNull(r.resolveTaglib("http://unknown"));
        assertTrue(r.getCachedTaglibUris().isEmpty());
    }

    @Test
    public void testScannedTldFile() throws IOException {
        Map<String, String> res = new HashMap<String, String>();
        res.put("/WEB-INF/t.tld", TLD);
        TaglibRegistry r = new TaglibRegistry(JspStubSupport.context(res, null));
        TagLibraryDescriptor tld = r.resolveTaglib("http://example.com/t");
        assertNotNull(tld);
        assertEquals("t", tld.getShortName());
        assertTrue(r.getCachedTaglibUris().contains("http://example.com/t"));
        TagLibraryDescriptor again = r.resolveTaglib("http://example.com/t");
        assertSame(tld, again);
        r.clearCache();
        assertTrue(r.getCachedTaglibUris().isEmpty());
    }

    @Test
    public void testSubdirectoryScan() throws IOException {
        Map<String, String> res = new HashMap<String, String>();
        res.put("/WEB-INF/tlds/sub.tld", TLD);
        res.put("/WEB-INF/tlds/readme.txt", "x");
        res.put("/WEB-INF/tags/", "");
        TaglibRegistry r = new TaglibRegistry(JspStubSupport.context(res, null));
        assertNotNull(r.resolveTaglib("http://example.com/t"));
    }

    @Test
    public void testJspConfigMapping() throws IOException {
        Map<String, String> res = new HashMap<String, String>();
        res.put("/custom/location.tld", TLD);
        List<TaglibDescriptor> tl = new ArrayList<TaglibDescriptor>();
        tl.add(JspStubSupport.taglib("urn:mapped", "/custom/location.tld"));
        tl.add(JspStubSupport.taglib(null, "/ignored"));
        JspConfigDescriptor cfg = JspStubSupport.config(tl, null);
        TaglibRegistry r = new TaglibRegistry(JspStubSupport.context(res, cfg));
        assertNotNull(r.resolveTaglib("urn:mapped"));
    }

    @Test
    public void testDirectPathResolution() throws IOException {
        Map<String, String> res = new HashMap<String, String>();
        res.put("/direct/x.tld", TLD);
        res.put("/WEB-INF/rel.tld", TLD);
        res.put("/WEB-INF/noext.tld", TLD);
        TaglibRegistry r = new TaglibRegistry(JspStubSupport.context(res, null));
        assertNotNull(r.resolveTaglib("/direct/x.tld"));
        assertNotNull(r.resolveTaglib("/WEB-INF/rel.tld"));
        assertNotNull(r.resolveTaglib("rel.tld"));
        assertNotNull(r.resolveTaglib("noext"));
        assertNull(r.resolveTaglib("/nothing"));
    }

    @Test
    public void testJarLocationNotResolvable() throws IOException {
        List<TaglibDescriptor> tl = new ArrayList<TaglibDescriptor>();
        tl.add(JspStubSupport.taglib("urn:jar", "jar:file:/x.jar!/a.tld"));
        TaglibRegistry r = new TaglibRegistry(JspStubSupport.context(
                new HashMap<String, String>(), JspStubSupport.config(tl, null)));
        assertNull(r.resolveTaglib("urn:jar"));
    }

    @Test
    public void testJarScan() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        JarOutputStream jos = new JarOutputStream(bos);
        jos.putNextEntry(new JarEntry("META-INF/lib.tld"));
        jos.write(TLD.getBytes(StandardCharsets.UTF_8));
        jos.closeEntry();
        jos.putNextEntry(new JarEntry("META-INF/other.txt"));
        jos.write(new byte[] { 1 });
        jos.closeEntry();
        jos.close();
        String jarContent = new String(bos.toByteArray(), StandardCharsets.ISO_8859_1);
        JspStubSupport.MapHandler h = new JspStubSupport.MapHandler() {
            @Override
            public Object invoke(Object proxy, java.lang.reflect.Method m, Object[] args) {
                if (m.getName().equals("getResourceAsStream")
                        && "/WEB-INF/lib/a.jar".equals(args[0])) {
                    return new java.io.ByteArrayInputStream(jarBytes);
                }
                return super.invoke(proxy, m, args);
            }

            byte[] jarBytes = bos.toByteArray();
        };
        h.resources.put("/WEB-INF/lib/a.jar", jarContent);
        h.resources.put("/WEB-INF/lib/b.txt", "x");
        ServletContext ctx = JspStubSupport.stub(ServletContext.class, h);
        TaglibRegistry r = new TaglibRegistry(ctx);
        assertNotNull(r);
        assertNull(r.resolveTaglib("http://example.com/t"));
    }
}
