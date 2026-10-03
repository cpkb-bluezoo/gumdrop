module org.bluezoo.gumdrop.smtp {
    requires java.logging;
    requires java.security.sasl;
    requires transitive org.bluezoo.gumdrop.core;
    requires org.bluezoo.gumdrop.mime;
    requires org.bluezoo.gumdrop.mailbox;

    requires org.bluezoo.gonzalez;

    exports org.bluezoo.gumdrop.smtp;
    exports org.bluezoo.gumdrop.smtp.server;
    exports org.bluezoo.gumdrop.smtp.client;
    exports org.bluezoo.gumdrop.smtp.auth;
}
