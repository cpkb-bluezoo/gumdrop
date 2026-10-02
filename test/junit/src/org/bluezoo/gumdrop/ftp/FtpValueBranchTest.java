/*
 * FtpValueBranchTest.java
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

package org.bluezoo.gumdrop.ftp;

import java.net.InetSocketAddress;
import java.security.cert.Certificate;
import java.time.Instant;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Branch coverage for the FTP value classes: listing and MLST formatting
 * with absent attributes, and connection metadata null handling.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class FtpValueBranchTest {

    private static final Instant WHEN = Instant.parse("2025-01-15T10:30:00Z");

    @Test
    public void testListingLineWithAllAttributesAbsent() {
        FtpFileInfo f = new FtpFileInfo("a.txt", 12, null, null, null, null);
        String line = f.formatAsListingLine();
        assertTrue(line, line.startsWith("-rw-r--r-- 1 "));
        assertTrue(line, line.endsWith(" a.txt"));
        assertTrue(line, line.contains("      12 "));
    }

    @Test
    public void testListingLineForDirectoryWithAttributes() {
        FtpFileInfo d = new FtpFileInfo("dir", WHEN, "me", "grp", "rwxr-xr-x");
        String line = d.formatAsListingLine();
        assertTrue(line, line.startsWith("drwxr-xr-x 1 me grp "));
        assertTrue(line, line.endsWith(" dir"));
        assertTrue(d.isDirectory());
        assertFalse(d.isFile());
        assertEquals("grp", d.getGroup());
        assertEquals("me", d.getOwner());
        assertEquals(WHEN, d.getLastModified());
    }

    @Test
    public void testMlsEntryDirectoryPermissions() {
        FtpFileInfo writable = new FtpFileInfo("d", WHEN, "o", "g", "rwxr-xr-x");
        assertEquals("type=dir;size=0;modify=20250115103000;perm=elcmp; d",
                writable.formatAsMLSEntry());
        FtpFileInfo readOnly = new FtpFileInfo("d", WHEN, "o", "g", "r-xr-xr-x");
        assertEquals("type=dir;size=0;modify=20250115103000;perm=el; d",
                readOnly.formatAsMLSEntry());
        FtpFileInfo noPerms = new FtpFileInfo("d", null, "o", "g", null);
        assertEquals("type=dir;size=0;perm=el; d", noPerms.formatAsMLSEntry());
        FtpFileInfo shortPerms = new FtpFileInfo("d", WHEN, "o", "g", "r");
        assertEquals("type=dir;size=0;modify=20250115103000;perm=el; d",
                shortPerms.formatAsMLSEntry());
    }

    @Test
    public void testMlsEntryFilePermissions() {
        FtpFileInfo writable = new FtpFileInfo("f", 5, WHEN, "o", "g", "rw-r--r--");
        assertEquals("type=file;size=5;modify=20250115103000;perm=rwadf; f",
                writable.formatAsMLSEntry());
        FtpFileInfo readOnly = new FtpFileInfo("f", 5, WHEN, "o", "g", "r--r--r--");
        assertEquals("type=file;size=5;modify=20250115103000;perm=r; f",
                readOnly.formatAsMLSEntry());
        FtpFileInfo noPerms = new FtpFileInfo("f", 5, null, "o", "g", null);
        assertEquals("type=file;size=5;perm=r; f", noPerms.formatAsMLSEntry());
        FtpFileInfo shortPerms = new FtpFileInfo("f", 5, WHEN, "o", "g", "");
        assertEquals("type=file;size=5;modify=20250115103000;perm=r; f",
                shortPerms.formatAsMLSEntry());
        assertTrue(writable.toString().contains("name='f'"));
    }

    @Test
    public void testMetadataPortChecksAndCertificateCopies() {
        FtpConnectionMetadata onFtp = new FtpConnectionMetadata(
                new InetSocketAddress("127.0.0.1", 5000),
                new InetSocketAddress("127.0.0.1", 21),
                false, null, null, null, 0L, "ftp");
        assertTrue(onFtp.isStandardFTPPort());
        assertFalse(onFtp.isSecureFTPPort());
        assertNull(onFtp.getClientCertificates());
        FtpConnectionMetadata onFtps = new FtpConnectionMetadata(
                new InetSocketAddress("127.0.0.1", 5000),
                new InetSocketAddress("127.0.0.1", 990),
                true, new Certificate[0], "suite", "TLSv1.3", 0L, "ftps");
        assertFalse(onFtps.isStandardFTPPort());
        assertTrue(onFtps.isSecureFTPPort());
        Certificate[] first = onFtps.getClientCertificates();
        Certificate[] second = onFtps.getClientCertificates();
        assertNotNull(first);
        assertNotSame(first, second);
        onFtps.setClientCertificates(null);
        assertNull(onFtps.getClientCertificates());
        onFtps.setClientCertificates(new Certificate[0]);
        assertNotNull(onFtps.getClientCertificates());
        FtpConnectionMetadata nowhere = new FtpConnectionMetadata(null, null,
                false, null, null, null, 0L, "ftp");
        assertFalse(nowhere.isStandardFTPPort());
        assertFalse(nowhere.isSecureFTPPort());
    }

}
