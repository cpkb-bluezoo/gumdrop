module org.bluezoo.gumdrop.telemetry.export {
    requires java.logging;
    requires org.bluezoo.gumdrop.core;
    requires org.bluezoo.gumdrop.http;
    requires org.bluezoo.gumdrop.mime;

    requires org.bluezoo.json;
    requires org.bluezoo.protobuf;

    exports org.bluezoo.gumdrop.telemetry.otlp;
    exports org.bluezoo.gumdrop.telemetry.json;
}
