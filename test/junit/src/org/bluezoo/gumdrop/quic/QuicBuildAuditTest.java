/*
 * QuicBuildAuditTest.java
 * Copyright (C) 2026 Chris Burdess
 *
 * Records the quiche/BoringSSL versions pinned by the native build so
 * security advisories can be cross-checked when upgrading QUICHE_DIR.
 */

package org.bluezoo.gumdrop.quic;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertTrue;

public class QuicBuildAuditTest {

    /** Matches install_name_tool pin in build.xml fix-mac-install-names. */
    private static final String EXPECTED_QUICHE_DYLIB = "libquiche.0.25.0";

    @Test
    public void testBuildXmlPinsQuicheVersion() throws Exception {
        Path buildXml = Paths.get("build.xml");
        String content = Files.readString(buildXml);
        assertTrue("build.xml should pin " + EXPECTED_QUICHE_DYLIB,
                content.contains(EXPECTED_QUICHE_DYLIB));
        assertTrue("native build should link quiche from QUICHE_DIR",
                content.contains("QUICHE_DIR"));
        assertTrue("BoringSSL headers come from quiche deps",
                content.contains("quiche/deps/boringssl"));
    }
}
