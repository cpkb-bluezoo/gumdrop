/*
 * DefaultTelemetryExporterFactory.java
 * Copyright (C) 2026 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 *
 * gumdrop is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * gumdrop is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with gumdrop.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.bluezoo.gumdrop.telemetry.export;

import org.bluezoo.gumdrop.telemetry.otlp.OTLPExporter;
import org.bluezoo.gumdrop.telemetry.otlp.OTLPGrpcExporter;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.TelemetryExporter;
import org.bluezoo.gumdrop.telemetry.TelemetryExporterFactory;
import org.bluezoo.gumdrop.telemetry.json.OTLPFileExporter;

/**
 * Default OTLP/HTTP, OTLP/gRPC, and JSONL file export for Gumdrop telemetry.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DefaultTelemetryExporterFactory implements TelemetryExporterFactory {

    @Override
    public TelemetryExporter createExporter(TelemetryConfig config) {
        if (!config.isExportConfigured()) {
            return null;
        }
        if ("file".equalsIgnoreCase(config.getExporterType())) {
            return new OTLPFileExporter(config,
                    config.getFileTracesPath(),
                    config.getFileLogsPath(),
                    config.getFileMetricsPath());
        }
        if ("grpc".equalsIgnoreCase(config.getProtocol())) {
            return new OTLPGrpcExporter(config);
        }
        return new OTLPExporter(config);
    }

}
