/**
 * JPMS descriptor for {@code gumdrop-core.jar} (Phase 3).
 */
module org.bluezoo.gumdrop.core {
    requires java.logging;
    requires java.naming;
    requires java.management;
    requires java.xml;
    requires java.security.jgss;

    requires org.bluezoo.gonzalez;
    requires org.bluezoo.json;
    requires tech.kwik.agent15;

    exports org.bluezoo.gumdrop;
    exports org.bluezoo.gumdrop.config;
    exports org.bluezoo.gumdrop.util;
    exports org.bluezoo.gumdrop.quota;
    exports org.bluezoo.gumdrop.ratelimit;
    exports org.bluezoo.gumdrop.auth;
    exports org.bluezoo.gumdrop.quic;
    exports org.bluezoo.gumdrop.quic.tls;
    exports org.bluezoo.gumdrop.quic.packet;
    exports org.bluezoo.gumdrop.quic.frame;
    exports org.bluezoo.gumdrop.quic.cid;
    exports org.bluezoo.gumdrop.quic.recovery;
    exports org.bluezoo.gumdrop.dns;
    exports org.bluezoo.gumdrop.dns.client;
    exports org.bluezoo.gumdrop.ldap.client;
    exports org.bluezoo.gumdrop.ldap.asn1;
    exports org.bluezoo.gumdrop.telemetry;
    exports org.bluezoo.gumdrop.telemetry.metrics;
    exports org.bluezoo.gumdrop.telemetry.protobuf;
    exports org.bluezoo.gumdrop.mailbox.spi;
    exports javax.servlet.jsp;

    uses org.bluezoo.gumdrop.GumdropConfigurator;
    uses org.bluezoo.gumdrop.mailbox.spi.MailboxLifecycle;
    uses org.bluezoo.gumdrop.telemetry.TelemetryExporterFactory;

    provides org.bluezoo.gumdrop.GumdropConfigurator
        with org.bluezoo.gumdrop.config.DefaultConfigurator;
}
