/*
 * GumdropFipsModeTest.java
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

package org.bluezoo.gumdrop;

import java.net.InetAddress;
import java.nio.ByteBuffer;

import org.bluezoo.gumdrop.crypto.CryptoServices;
import org.bluezoo.gumdrop.telemetry.Attribute;
import org.bluezoo.gumdrop.telemetry.LogLevel;
import org.bluezoo.gumdrop.telemetry.LogRecord;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.testsupport.CompositeTestProvider;
import org.bluezoo.gumdrop.testsupport.InlineSelectorLoop;
import org.bluezoo.gumdrop.testsupport.RecordingExporter;
import org.bluezoo.gumdrop.testsupport.TestGumdrop;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The runtime FIPS flag (issue #570): off by default, set through
 * {@link GumdropConfig} for both {@link Gumdrop#boot(GumdropConfig)} and
 * {@link Gumdrop#embedded(SelectorLoop, java.util.concurrent.Executor, GumdropConfig)},
 * resolving the named provider once and refusing to come up when it is
 * absent or cannot supply a required service.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class GumdropFipsModeTest {

    private static Gumdrop embedded(GumdropConfig config) {
        return Gumdrop.embedded(new InlineSelectorLoop(), new TestGumdrop.DirectExecutor(), config);
    }

    @Test
    public void fipsModeIsOffByDefault() {
        Gumdrop gumdrop = TestGumdrop.create();
        assertFalse(gumdrop.isFipsMode());
        assertNull(gumdrop.getCryptoServices().getProvider());
        assertFalse(GumdropConfig.create().isFipsMode());
    }

    @Test
    public void bootedRuntimeExposesTheFlagThatWasSet() {
        CompositeTestProvider.install();
        GumdropConfig config = GumdropConfig.create().workerThreads(1)
                .fipsProvider(CompositeTestProvider.NAME);
        assertTrue(config.isFipsMode());
        Gumdrop gumdrop = Gumdrop.boot(config);
        try {
            assertTrue(gumdrop.isFipsMode());
            assertSame(CompositeTestProvider.install(), gumdrop.getCryptoServices().getProvider());
        } finally {
            gumdrop.shutdown();
        }
    }

    @Test
    public void embeddedRuntimeExposesTheFlagThatWasSet() {
        CompositeTestProvider.install();
        Gumdrop gumdrop = embedded(GumdropConfig.create().fipsProvider(CompositeTestProvider.NAME));
        assertTrue(gumdrop.isFipsMode());
        assertEquals(CompositeTestProvider.NAME, gumdrop.getCryptoServices().getProvider().getName());
    }

    @Test
    public void enteringFipsModeIsLoggedWithProviderAndRandomSource() {
        CompositeTestProvider provider = CompositeTestProvider.install();
        Gumdrop gumdrop = embedded(GumdropConfig.create().fipsProvider(CompositeTestProvider.NAME)
                .fipsSecureRandomAlgorithm("DRBG"));
        TelemetryConfig telemetry = new TelemetryConfig();
        RecordingExporter exporter = new RecordingExporter();
        telemetry.exporter(exporter);
        gumdrop.telemetryConfig(telemetry);
        gumdrop.start();
        LogRecord fips = null;
        for (int i = 0; i < exporter.records.size(); i++) {
            if ("info.fips_mode".equals(exporter.records.get(i).getKey())) {
                fips = exporter.records.get(i);
            }
        }
        assertNotNull("entering FIPS mode is logged", fips);
        assertEquals(LogLevel.INFO, fips.getLevel());
        assertEquals(CompositeTestProvider.NAME, attribute(fips, "provider"));
        assertEquals(provider.getVersionStr(), attribute(fips, "provider_version"));
        assertEquals("DRBG", attribute(fips, "secure_random"));
    }

    @Test
    public void nothingIsLoggedAboutFipsModeWhenItIsOff() {
        Gumdrop gumdrop = TestGumdrop.create();
        TelemetryConfig telemetry = new TelemetryConfig();
        RecordingExporter exporter = new RecordingExporter();
        telemetry.exporter(exporter);
        gumdrop.telemetryConfig(telemetry);
        gumdrop.start();
        for (int i = 0; i < exporter.records.size(); i++) {
            assertFalse("info.fips_mode".equals(exporter.records.get(i).getKey()));
        }
    }

    private static String attribute(LogRecord record, String name) {
        for (Attribute attribute : record.getAttributes()) {
            if (name.equals(attribute.getKey())) {
                return attribute.getStringValue();
            }
        }
        fail("no attribute " + name + " on " + record.getKey());
        return null;
    }

    @Test
    public void bootFailsWhenTheNamedProviderIsAbsent() {
        try {
            embedded(GumdropConfig.create().fipsProvider("NoSuchProvider"));
            fail("a missing provider must refuse to boot");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("NoSuchProvider"));
        }
    }

    @Test
    public void bootFailsWhenTheProviderLacksARequiredService() {
        // SunJCE has the ciphers, MACs and HKDF but no digests, no
        // certificate factory and no random source
        try {
            embedded(GumdropConfig.create().fipsProvider("SunJCE"));
            fail("a provider missing a required service must refuse to boot");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("SunJCE"));
            assertTrue(expected.getMessage(), expected.getMessage().contains("MessageDigest"));
        }
    }

    @Test
    public void randomNumbersComeFromTheNamedProvider() {
        CompositeTestProvider provider = CompositeTestProvider.install();
        Gumdrop gumdrop = embedded(GumdropConfig.create().fipsProvider(CompositeTestProvider.NAME));
        int before = provider.handedOut("SecureRandom");
        CryptoServices crypto = gumdrop.getCryptoServices();
        assertSame(provider, crypto.secureRandom().getProvider());
        crypto.nextBytes(new byte[16]);
        assertTrue(provider.handedOut("SecureRandom") > before);
    }

    @Test
    public void randomAlgorithmCanBeNamed() {
        CompositeTestProvider.install();
        Gumdrop gumdrop = embedded(GumdropConfig.create().fipsProvider(CompositeTestProvider.NAME)
                .fipsSecureRandomAlgorithm("DRBG"));
        assertEquals("DRBG", gumdrop.getCryptoServices().secureRandom().getAlgorithm());
    }

    @Test
    public void bootFailsWhenTheNamedRandomAlgorithmIsAbsent() {
        CompositeTestProvider.install();
        try {
            embedded(GumdropConfig.create().fipsProvider(CompositeTestProvider.NAME)
                    .fipsSecureRandomAlgorithm("NoSuchDrbg"));
            fail("a missing random algorithm must refuse to boot");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("NoSuchDrbg"));
        }
    }

    @Test
    public void servicesComeFromTheNamedProvider() throws Exception {
        CompositeTestProvider provider = CompositeTestProvider.install();
        Gumdrop gumdrop = embedded(GumdropConfig.create().fipsProvider(CompositeTestProvider.NAME));
        CryptoServices crypto = gumdrop.getCryptoServices();
        assertSame(provider, crypto.cipher("AES/GCM/NoPadding").getProvider());
        assertSame(provider, crypto.mac("HmacSHA256").getProvider());
        assertSame(provider, crypto.messageDigest("SHA-256").getProvider());
        assertSame(provider, crypto.signature("SHA256withECDSA").getProvider());
        assertEquals(CompositeTestProvider.NAME, crypto.kdf("HKDF-SHA256").getProviderName());
        assertSame(provider, crypto.keyPairGenerator("EC").getProvider());
        assertSame(provider, crypto.keyAgreement("ECDH").getProvider());
        assertSame(provider, crypto.certificateFactory("X.509").getProvider());
        int before = provider.handedOut("KDF");
        crypto.hkdfExtract("SHA-256", new byte[0], new byte[32]);
        assertTrue(provider.handedOut("KDF") > before);
    }

    @Test
    public void aFactoryStartedOutsideTheRuntimeIsRefusedInFipsMode() throws Exception {
        CompositeTestProvider.install();
        Gumdrop gumdrop = embedded(GumdropConfig.create().fipsProvider(CompositeTestProvider.NAME));
        TcpTransportFactory factory = new TcpTransportFactory();
        factory.start();
        assertNotSame(gumdrop.getCryptoServices(), factory.getCryptoServices());
        try {
            factory.connect(gumdrop, InetAddress.getLoopbackAddress(), 1, new NoOpHandler(),
                    gumdrop.nextWorkerLoop());
            fail("a factory not started under a FIPS runtime must be refused");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("FIPS"));
        }
    }

    @Test
    public void aFactoryStartedUnderTheRuntimeSharesItsServices() {
        CompositeTestProvider.install();
        Gumdrop gumdrop = embedded(GumdropConfig.create().fipsProvider(CompositeTestProvider.NAME));
        TcpTransportFactory factory = new TcpTransportFactory();
        factory.start(gumdrop);
        assertSame(gumdrop.getCryptoServices(), factory.getCryptoServices());
    }

    @Test
    public void aFactoryStartedOutsideTheRuntimeIsAcceptedWhenFipsModeIsOff() throws Exception {
        Gumdrop gumdrop = TestGumdrop.create();
        TcpTransportFactory factory = new TcpTransportFactory();
        factory.start();
        // Not a FIPS runtime: the standalone services are as good as its own
        factory.checkRuntime(gumdrop);
    }

    private static final class NoOpHandler implements ProtocolHandler {
        @Override
        public void connected(Endpoint endpoint) {
        }

        @Override
        public void receive(ByteBuffer data) {
        }

        @Override
        public void disconnected() {
        }

        @Override
        public void securityEstablished(SecurityInfo info) {
        }

        @Override
        public void error(Exception e) {
        }
    }

}
