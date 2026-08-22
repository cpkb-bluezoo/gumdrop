/**
 * All-in-one {@code gumdrop.jar} module (Phase 3 aggregator).
 *
 * <p>Individual protocol and optional jars publish their own module descriptors;
 * this module covers the merged library artifact used by classpath and JPMS
 * consumers who depend on a single coordinate.
 */
module org.bluezoo.gumdrop {
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
    exports org.bluezoo.gumdrop.auth.oauth;
    exports org.bluezoo.gumdrop.auth.ldap;
    exports org.bluezoo.gumdrop.http;
    exports org.bluezoo.gumdrop.http.client;
    exports org.bluezoo.gumdrop.http.h2;
    exports org.bluezoo.gumdrop.http.h3;
    exports org.bluezoo.gumdrop.http.hpack;
    exports org.bluezoo.gumdrop.http.qpack;
    exports org.bluezoo.gumdrop.http.doh;
    exports org.bluezoo.gumdrop.websocket;
    exports org.bluezoo.gumdrop.websocket.client;
    exports org.bluezoo.gumdrop.webdav;
    exports org.bluezoo.gumdrop.smtp;
    exports org.bluezoo.gumdrop.smtp.client;
    exports org.bluezoo.gumdrop.smtp.handler;
    exports org.bluezoo.gumdrop.smtp.auth;
    exports org.bluezoo.gumdrop.pop3;
    exports org.bluezoo.gumdrop.pop3.handler;
    exports org.bluezoo.gumdrop.pop3.client;
    exports org.bluezoo.gumdrop.pop3.client.handler;
    exports org.bluezoo.gumdrop.imap;
    exports org.bluezoo.gumdrop.imap.handler;
    exports org.bluezoo.gumdrop.imap.client;
    exports org.bluezoo.gumdrop.imap.client.handler;
    exports org.bluezoo.gumdrop.ftp;
    exports org.bluezoo.gumdrop.ftp.file;
    exports org.bluezoo.gumdrop.ftp.client;
    exports org.bluezoo.gumdrop.ftp.client.handler;
    exports org.bluezoo.gumdrop.dns;
    exports org.bluezoo.gumdrop.dns.client;
    exports org.bluezoo.gumdrop.mqtt;
    exports org.bluezoo.gumdrop.mqtt.broker;
    exports org.bluezoo.gumdrop.mqtt.client;
    exports org.bluezoo.gumdrop.mqtt.codec;
    exports org.bluezoo.gumdrop.mqtt.handler;
    exports org.bluezoo.gumdrop.mqtt.store;
    exports org.bluezoo.gumdrop.socks;
    exports org.bluezoo.gumdrop.socks.client;
    exports org.bluezoo.gumdrop.socks.handler;
    exports org.bluezoo.gumdrop.quic;
    exports org.bluezoo.gumdrop.quic.tls;
    exports org.bluezoo.gumdrop.quic.packet;
    exports org.bluezoo.gumdrop.quic.frame;
    exports org.bluezoo.gumdrop.quic.cid;
    exports org.bluezoo.gumdrop.quic.recovery;
    exports org.bluezoo.gumdrop.servlet;
    exports org.bluezoo.gumdrop.servlet.jsp;
    exports org.bluezoo.gumdrop.servlet.session;
    exports org.bluezoo.gumdrop.servlet.jndi;
    exports org.bluezoo.gumdrop.servlet.manager;
    exports org.bluezoo.gumdrop.mailbox;
    exports org.bluezoo.gumdrop.mailbox.spi;
    exports org.bluezoo.gumdrop.mailbox.mbox;
    exports org.bluezoo.gumdrop.mailbox.maildir;
    exports org.bluezoo.gumdrop.mailbox.index;
    exports org.bluezoo.gumdrop.health;
    exports org.bluezoo.gumdrop.mime;
    exports org.bluezoo.gumdrop.mime.rfc2047;
    exports org.bluezoo.gumdrop.mime.rfc2231;
    exports org.bluezoo.gumdrop.mime.rfc5322;
    exports org.bluezoo.gumdrop.ldap.client;
    exports org.bluezoo.gumdrop.ldap.asn1;
    exports org.bluezoo.gumdrop.redis.client;
    exports org.bluezoo.gumdrop.redis.codec;
    exports org.bluezoo.gumdrop.telemetry;
    exports org.bluezoo.gumdrop.telemetry.metrics;
    exports org.bluezoo.gumdrop.telemetry.protobuf;
    exports org.bluezoo.gumdrop.telemetry.otlp;
    exports org.bluezoo.gumdrop.telemetry.json;
    exports org.bluezoo.gumdrop.telemetry.export;
    exports org.bluezoo.gumdrop.grpc;
    exports org.bluezoo.gumdrop.grpc.client;
    exports org.bluezoo.gumdrop.grpc.server;
    exports org.bluezoo.gumdrop.grpc.proto;
    exports org.bluezoo.gumdrop.amqp.client;
    exports org.bluezoo.gumdrop.amqp.client.handler;
    exports org.bluezoo.gumdrop.mdns;
    exports javax.servlet.jsp;

    uses org.bluezoo.gumdrop.GumdropConfigurator;
    uses org.bluezoo.gumdrop.mailbox.spi.MailboxLifecycle;
    uses org.bluezoo.gumdrop.telemetry.TelemetryExporterFactory;

    provides org.bluezoo.gumdrop.GumdropConfigurator
        with org.bluezoo.gumdrop.config.DefaultConfigurator;
    provides org.bluezoo.gumdrop.mailbox.spi.MailboxLifecycle
        with org.bluezoo.gumdrop.mailbox.DefaultMailboxLifecycle;
    provides org.bluezoo.gumdrop.telemetry.TelemetryExporterFactory
        with org.bluezoo.gumdrop.telemetry.export.DefaultTelemetryExporterFactory;
}
