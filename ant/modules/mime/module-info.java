@SuppressWarnings("module")
module org.bluezoo.gumdrop.mime {
    requires transitive org.bluezoo.gumdrop.core;

    exports org.bluezoo.gumdrop.mime;
    // L10N bundle looked up by gumdrop-smtp (DKIM parsing).
    opens org.bluezoo.gumdrop.mime to org.bluezoo.gumdrop.smtp;
    exports org.bluezoo.gumdrop.mime.rfc2047;
    exports org.bluezoo.gumdrop.mime.rfc2231;
    exports org.bluezoo.gumdrop.mime.rfc5322;
}
