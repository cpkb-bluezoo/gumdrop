module org.bluezoo.gumdrop.telemetry.export {
    requires java.logging;
    requires org.bluezoo.gumdrop.core;
    requires org.bluezoo.gumdrop.http;
    requires org.bluezoo.gumdrop.mime;

    requires org.bluezoo.json;
    requires org.bluezoo.protobuf;

    exports org.bluezoo.gumdrop.telemetry.otlp;
    exports org.bluezoo.gumdrop.telemetry.json;
    exports org.bluezoo.gumdrop.telemetry.export;

    uses org.bluezoo.gumdrop.telemetry.TelemetryExporterFactory;

    provides org.bluezoo.gumdrop.telemetry.TelemetryExporterFactory
        with org.bluezoo.gumdrop.telemetry.export.DefaultTelemetryExporterFactory;
}
