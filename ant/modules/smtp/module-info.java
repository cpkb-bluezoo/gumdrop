module org.bluezoo.gumdrop.smtp {
    requires org.bluezoo.gumdrop.core;
    requires org.bluezoo.gumdrop.mime;
    requires org.bluezoo.gumdrop.http;
    requires org.bluezoo.gumdrop.mailbox;

    exports org.bluezoo.gumdrop.smtp;
    exports org.bluezoo.gumdrop.smtp.handler;
    exports org.bluezoo.gumdrop.smtp.client;
    exports org.bluezoo.gumdrop.smtp.client.handler;
    exports org.bluezoo.gumdrop.smtp.auth;
}
