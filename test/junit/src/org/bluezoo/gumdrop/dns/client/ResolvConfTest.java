/*
 * ResolvConfTest.java
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

package org.bluezoo.gumdrop.dns.client;

import org.bluezoo.gumdrop.testsupport.memfs.MemoryFileSystem;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link ResolvConf}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ResolvConfTest {

    private MemoryFileSystem mem;

    @Before
    public void setUp() {
        mem = MemoryFileSystem.create();
    }

    @Test
    public void testParseSingleNameserver() throws IOException {
        Path path = writeResolvConf("nameserver 8.8.8.8\n");
        assertEquals(List.of("8.8.8.8"), ResolvConf.parse(path));
    }

    @Test
    public void testParseMultipleNameserversPreservesOrder() throws IOException {
        Path path = writeResolvConf(
                "nameserver 8.8.8.8\n"
                + "nameserver 1.1.1.1\n"
                + "nameserver 9.9.9.9\n");
        assertEquals(List.of("8.8.8.8", "1.1.1.1", "9.9.9.9"),
                ResolvConf.parse(path));
    }

    @Test
    public void testIgnoresSearchDomainOptionsDirectives() throws IOException {
        Path path = writeResolvConf(
                "search example.com\n"
                + "domain example.com\n"
                + "options ndots:1\n"
                + "sortlist 10.0.0.0/8\n"
                + "nameserver 8.8.8.8\n");
        assertEquals(List.of("8.8.8.8"), ResolvConf.parse(path));
    }

    @Test
    public void testIgnoresHashComments() throws IOException {
        Path path = writeResolvConf(
                "# a comment\n"
                + "nameserver 8.8.8.8 # trailing comment\n");
        assertEquals(List.of("8.8.8.8"), ResolvConf.parse(path));
    }

    @Test
    public void testIgnoresSemicolonComments() throws IOException {
        Path path = writeResolvConf(
                "; a comment\n"
                + "nameserver 8.8.8.8 ; trailing comment\n");
        assertEquals(List.of("8.8.8.8"), ResolvConf.parse(path));
    }

    @Test
    public void testBlankAndWhitespaceLinesIgnored() throws IOException {
        Path path = writeResolvConf(
                "\n   \nnameserver 8.8.8.8\n\n");
        assertEquals(List.of("8.8.8.8"), ResolvConf.parse(path));
    }

    @Test
    public void testIPv6NameserverWithZonePreserved() throws IOException {
        Path path = writeResolvConf("nameserver fe80::1%eth0\n");
        assertEquals(List.of("fe80::1%eth0"), ResolvConf.parse(path));
    }

    @Test
    public void testMalformedNameserverLineIgnored() throws IOException {
        Path path = writeResolvConf("nameserver\nnameserver 8.8.8.8\n");
        assertEquals(List.of("8.8.8.8"), ResolvConf.parse(path));
    }

    @Test
    public void testNonexistentFileReturnsEmptyList() {
        List<String> result = ResolvConf.parse(
                Paths.get("/does/not/exist/resolv.conf"));
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    public void testNullPathReturnsEmptyList() {
        List<String> result = ResolvConf.parse(null);
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    public void testGetNameserversDoesNotThrow() {
        List<String> result = ResolvConf.getNameservers();
        assertNotNull(result);
    }

    private Path writeResolvConf(String content) throws IOException {
        Path path = mem.getPath("/etc/resolv.conf");
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
        return path;
    }

}
