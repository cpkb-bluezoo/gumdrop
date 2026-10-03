/**
 * JPMS descriptor for {@code gumdrop-core.jar} (Phase 3).
 */
// "module" lint: the qualified opens name modules that are not on this
// module path (they depend on core, not the other way round).
@SuppressWarnings("module")
module org.bluezoo.gumdrop.core {
    requires java.logging;
    requires java.naming;
    requires java.management;
    requires java.xml;
    requires java.security.jgss;

    requires org.bluezoo.gonzalez;
    requires org.bluezoo.micula;

    exports org.bluezoo.gumdrop;
    exports org.bluezoo.gumdrop.util;
    exports org.bluezoo.gumdrop.quota;
    exports org.bluezoo.gumdrop.ratelimit;
    exports org.bluezoo.gumdrop.auth;
    exports org.bluezoo.gumdrop.crypto;
    exports org.bluezoo.gumdrop.tls;
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
    exports org.bluezoo.gumdrop.mailbox.spi;
    exports org.bluezoo.gumdrop.client;
    exports org.bluezoo.gumdrop.dns.server;

    // Message bundles (L10N) that other modules look up by name.
    opens org.bluezoo.gumdrop.auth to org.bluezoo.gumdrop.http, org.bluezoo.gumdrop.ldap;
    opens org.bluezoo.gumdrop.telemetry to org.bluezoo.gumdrop.telemetry.export;

    uses org.bluezoo.gumdrop.mailbox.spi.MailboxLifecycle;
    uses org.bluezoo.gumdrop.telemetry.TelemetryExporterFactory;
}
