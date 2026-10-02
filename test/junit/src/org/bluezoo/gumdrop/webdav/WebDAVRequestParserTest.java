/*
 * WebDAVRequestParserTest.java
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

package org.bluezoo.gumdrop.webdav;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Parsing of PROPFIND, PROPPATCH and LOCK request bodies by
 * {@link WebDAVRequestParser} (RFC 4918 section 14).
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class WebDAVRequestParserTest {

    private static WebDAVRequestParser parse(String xml) throws IOException {
        WebDAVRequestParser p = new WebDAVRequestParser();
        p.receive(ByteBuffer.wrap(xml.getBytes(StandardCharsets.UTF_8)));
        p.close();
        return p;
    }

    private static final String HEAD = "<?xml version=\"1.0\" encoding=\"utf-8\"?>";

    @Test
    public void propfindAllpropIsDefault() throws IOException {
        WebDAVRequestParser p = parse(HEAD + "<D:propfind xmlns:D=\"DAV:\"><D:allprop/></D:propfind>");
        assertEquals(WebDAVRequestParser.PropfindType.ALLPROP, p.getPropfindRequest().type);
        assertNull(p.getProppatchRequest());
        assertNull(p.getLockRequest());
    }

    @Test
    public void propfindPropname() throws IOException {
        WebDAVRequestParser p = parse(HEAD + "<D:propfind xmlns:D=\"DAV:\"><D:propname/></D:propfind>");
        assertEquals(WebDAVRequestParser.PropfindType.PROPNAME, p.getPropfindRequest().type);
    }

    @Test
    public void propfindNamedPropertiesAndInclude() throws IOException {
        WebDAVRequestParser p = parse(HEAD
                + "<D:propfind xmlns:D=\"DAV:\" xmlns:X=\"urn:x\">"
                + "<D:prop><D:getetag/><X:custom/></D:prop>"
                + "<D:include><D:getcontenttype/><X:more/></D:include>"
                + "</D:propfind>");
        WebDAVRequestParser.PropfindRequest r = p.getPropfindRequest();
        assertEquals(WebDAVRequestParser.PropfindType.PROP, r.type);
        assertEquals(2, r.properties.size());
        assertEquals("DAV:", r.properties.get(0).namespaceURI);
        assertEquals("getetag", r.properties.get(0).localName);
        assertEquals("urn:x", r.properties.get(1).namespaceURI);
        assertEquals(2, r.include.size());
        assertEquals("more", r.include.get(1).localName);
    }

    @Test
    public void propfindOwnerPropertyIsARequestedPropertyNotLockOwner() throws IOException {
        WebDAVRequestParser p = parse(HEAD
                + "<D:propfind xmlns:D=\"DAV:\"><D:prop><D:owner/></D:prop></D:propfind>");
        assertEquals(1, p.getPropfindRequest().properties.size());
        assertEquals("owner", p.getPropfindRequest().properties.get(0).localName);
    }

    @Test
    public void proppatchSetAndRemove() throws IOException {
        WebDAVRequestParser p = parse(HEAD
                + "<D:propertyupdate xmlns:D=\"DAV:\" xmlns:X=\"urn:x\">"
                + "<D:set><D:prop><X:title>Hello</X:title></D:prop></D:set>"
                + "<D:set><D:prop><X:rich><X:b>bold</X:b> text</X:rich></D:prop></D:set>"
                + "<D:remove><D:prop><X:gone/></D:prop></D:remove>"
                + "</D:propertyupdate>");
        WebDAVRequestParser.ProppatchRequest r = p.getProppatchRequest();
        assertEquals(3, r.updates.size());
        WebDAVRequestParser.PropertyUpdate set = r.updates.get(0);
        assertEquals(WebDAVRequestParser.PropPatchOp.SET, set.operation);
        assertEquals("urn:x", set.namespaceURI);
        assertEquals("title", set.localName);
        assertEquals("Hello", set.value);
        assertFalse(set.isXML);
        WebDAVRequestParser.PropertyUpdate rich = r.updates.get(1);
        assertTrue(rich.isXML);
        assertTrue(rich.value, rich.value.contains("<X:b>bold</X:b>"));
        WebDAVRequestParser.PropertyUpdate remove = r.updates.get(2);
        assertEquals(WebDAVRequestParser.PropPatchOp.REMOVE, remove.operation);
        assertEquals("", remove.value);
    }

    @Test
    public void proppatchDavNamespacedProperty() throws IOException {
        WebDAVRequestParser p = parse(HEAD
                + "<D:propertyupdate xmlns:D=\"DAV:\">"
                + "<D:set><D:prop><D:displayname>n</D:displayname></D:prop></D:set>"
                + "</D:propertyupdate>");
        assertEquals(1, p.getProppatchRequest().updates.size());
        assertEquals("DAV:", p.getProppatchRequest().updates.get(0).namespaceURI);
    }

    @Test
    public void lockinfoExclusiveWithOwner() throws IOException {
        WebDAVRequestParser p = parse(HEAD
                + "<D:lockinfo xmlns:D=\"DAV:\"><D:lockscope><D:exclusive/></D:lockscope>"
                + "<D:locktype><D:write/></D:locktype>"
                + "<D:owner>  mailto:me@example.com </D:owner></D:lockinfo>");
        WebDAVRequestParser.LockRequest r = p.getLockRequest();
        assertEquals(WebDAVLock.Scope.EXCLUSIVE, r.scope);
        assertEquals(WebDAVLock.Type.WRITE, r.type);
        assertEquals("mailto:me@example.com", r.owner);
    }

    @Test
    public void lockinfoSharedWithoutOwner() throws IOException {
        WebDAVRequestParser p = parse(HEAD
                + "<D:lockinfo xmlns:D=\"DAV:\"><D:lockscope><D:shared/></D:lockscope>"
                + "<D:locktype><D:write/></D:locktype><D:owner></D:owner></D:lockinfo>");
        assertEquals(WebDAVLock.Scope.SHARED, p.getLockRequest().scope);
        assertNull(p.getLockRequest().owner);
    }

    @Test
    public void scopeAndTypeOutsideLockinfoAreIgnored() throws IOException {
        WebDAVRequestParser p = parse(HEAD
                + "<D:propfind xmlns:D=\"DAV:\"><D:exclusive/><D:shared/><D:write/></D:propfind>");
        assertNull(p.getLockRequest());
        assertNotNull(p.getPropfindRequest());
    }

    @Test
    public void allpropOutsidePropfindIsIgnored() throws IOException {
        WebDAVRequestParser p = parse(HEAD
                + "<D:other xmlns:D=\"DAV:\"><D:allprop/><D:propname/><D:prop/></D:other>");
        assertNull(p.getPropfindRequest());
    }

    @Test
    public void malformedXmlRaisesIOException() {
        try {
            parse(HEAD + "<D:propfind xmlns:D=\"DAV:\"><D:allprop></D:propfind>");
            fail("expected IOException");
        } catch (IOException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void externalEntitiesAreDenied() {
        try {
            parse(HEAD + "<!DOCTYPE d [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>"
                    + "<D:propertyupdate xmlns:D=\"DAV:\" xmlns:X=\"urn:x\"><D:set><D:prop>"
                    + "<X:t>&x;</X:t></D:prop></D:set></D:propertyupdate>");
        } catch (IOException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void resetAllowsReuse() throws IOException {
        WebDAVRequestParser p = parse(HEAD + "<D:propfind xmlns:D=\"DAV:\"><D:propname/></D:propfind>");
        p.reset();
        assertNull(p.getPropfindRequest());
        p.receive(ByteBuffer.wrap((HEAD
                + "<D:lockinfo xmlns:D=\"DAV:\"><D:owner>x</D:owner></D:lockinfo>")
                .getBytes(StandardCharsets.UTF_8)));
        p.close();
        assertEquals("x", p.getLockRequest().owner);
        assertNull(p.getPropfindRequest());
    }

    @Test
    public void bodyFedInSeveralChunks() throws IOException {
        byte[] body = ("<D:propfind xmlns:D=\"DAV:\"><D:prop><D:getetag/></D:prop></D:propfind>")
                .getBytes(StandardCharsets.UTF_8);
        WebDAVRequestParser p = new WebDAVRequestParser();
        for (int i = 0; i < body.length; i += 3) {
            int end = Math.min(body.length, i + 3);
            byte[] chunk = java.util.Arrays.copyOfRange(body, i, end);
            p.receive(ByteBuffer.wrap(chunk));
        }
        p.close();
        assertEquals(1, p.getPropfindRequest().properties.size());
    }
}
