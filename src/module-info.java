/**
 * Gumdrop multipurpose Java server module.
 *
 * <p>Gumdrop is an event-driven, non-blocking server framework that provides
 * implementations for multiple protocols including HTTP/1.1, HTTP/2, WebSocket,
 * SMTP, POP3, IMAP, FTP, and DNS. It also includes a Servlet 4.0 container
 * for hosting Java web applications.
 *
 * <p>The core architecture is based on Java NIO with a single-threaded
 * event loop that handles all I/O operations, enabling high concurrency
 * with minimal thread overhead.
 *
 * <h2>Note on Dependencies</h2>
 *
 * <p>Gumdrop bundles its dependencies (servlet-api, javamail, jsonparser,
 * gonzalez, etc.) inside the gumdrop.jar fat jar. These are loaded at runtime
 * via a custom ContainerClassLoader and are not declared as JPMS dependencies.
 * This allows gumdrop.jar to be used as a standalone executable without
 * external module dependencies.
 *
 * @see org.bluezoo.gumdrop.Bootstrap
 * @see org.bluezoo.gumdrop.Gumdrop
 */
module org.bluezoo.gumdrop {
    // JDK modules used by gumdrop
    requires java.logging;
    requires java.naming;          // JNDI for servlet container
    requires java.management;      // JMX for monitoring
    
    // Core server framework
    exports org.bluezoo.gumdrop;
    exports org.bluezoo.gumdrop.util;
    
    // Protocol implementations
    exports org.bluezoo.gumdrop.http;
    exports org.bluezoo.gumdrop.http.client;
    exports org.bluezoo.gumdrop.http.file;
    exports org.bluezoo.gumdrop.http.websocket;
    exports org.bluezoo.gumdrop.http.h2;
    exports org.bluezoo.gumdrop.http.hpack;
    exports org.bluezoo.gumdrop.smtp;
    exports org.bluezoo.gumdrop.smtp.client;
    exports org.bluezoo.gumdrop.smtp.handler;
    exports org.bluezoo.gumdrop.smtp.auth;
    exports org.bluezoo.gumdrop.pop3;
    exports org.bluezoo.gumdrop.pop3.handler;
    exports org.bluezoo.gumdrop.imap;
    exports org.bluezoo.gumdrop.imap.handler;
    exports org.bluezoo.gumdrop.ftp;
    exports org.bluezoo.gumdrop.ftp.file;
    exports org.bluezoo.gumdrop.dns;
    
    // Servlet container
    exports org.bluezoo.gumdrop.servlet;
    exports org.bluezoo.gumdrop.servlet.jsp;
    exports org.bluezoo.gumdrop.servlet.session;
    exports org.bluezoo.gumdrop.servlet.jndi;
    
    // Mail storage
    exports org.bluezoo.gumdrop.mailbox;
    exports org.bluezoo.gumdrop.mailbox.mbox;
    exports org.bluezoo.gumdrop.mailbox.maildir;
    exports org.bluezoo.gumdrop.mailbox.index;
    
    // Supporting services
    exports org.bluezoo.gumdrop.auth;
    exports org.bluezoo.gumdrop.mime;
    exports org.bluezoo.gumdrop.mime.rfc2047;
    exports org.bluezoo.gumdrop.mime.rfc5322;
    exports org.bluezoo.gumdrop.quota;
    exports org.bluezoo.gumdrop.ratelimit;
    // Note: org.bluezoo.gumdrop.ldap only has package-info.java, no types to export
    exports org.bluezoo.gumdrop.ldap.client;
    exports org.bluezoo.gumdrop.ldap.asn1;
    // Note: org.bluezoo.gumdrop.redis only has sub-packages, no types to export
    exports org.bluezoo.gumdrop.redis.client;
    exports org.bluezoo.gumdrop.redis.codec;
    
    // Telemetry / OpenTelemetry
    exports org.bluezoo.gumdrop.telemetry;
    exports org.bluezoo.gumdrop.telemetry.metrics;
    exports org.bluezoo.gumdrop.telemetry.protobuf;
}

