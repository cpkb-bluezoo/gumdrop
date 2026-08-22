module org.bluezoo.gumdrop.mailbox {
    requires org.bluezoo.gumdrop.core;
    requires org.bluezoo.gumdrop.mime;

    exports org.bluezoo.gumdrop.mailbox;
    exports org.bluezoo.gumdrop.mailbox.mbox;
    exports org.bluezoo.gumdrop.mailbox.maildir;
    exports org.bluezoo.gumdrop.mailbox.index;

    uses org.bluezoo.gumdrop.mailbox.spi.MailboxLifecycle;

    provides org.bluezoo.gumdrop.mailbox.spi.MailboxLifecycle
        with org.bluezoo.gumdrop.mailbox.DefaultMailboxLifecycle;
}
