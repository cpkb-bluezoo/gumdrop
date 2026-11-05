// The digit in the module name is the protocol (NNTP, AMQP 1.0), not a version.
@SuppressWarnings("module")
module org.bluezoo.gumdrop.nntp {
    requires java.logging;
    requires transitive org.bluezoo.gumdrop.core;
    requires org.bluezoo.gumdrop.mime;
    requires org.bluezoo.gumdrop.mailbox;

    exports org.bluezoo.gumdrop.nntp;
    exports org.bluezoo.gumdrop.nntp.server;
}
