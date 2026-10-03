// The digit in the module name is the protocol (POP3, AMQP 1.0), not a version.
@SuppressWarnings("module")
module org.bluezoo.gumdrop.pop3 {
    requires java.logging;
    requires transitive org.bluezoo.gumdrop.core;
    requires org.bluezoo.gumdrop.mime;
    requires org.bluezoo.gumdrop.mailbox;

    exports org.bluezoo.gumdrop.pop3;
    exports org.bluezoo.gumdrop.pop3.server;
    exports org.bluezoo.gumdrop.pop3.client;
}
