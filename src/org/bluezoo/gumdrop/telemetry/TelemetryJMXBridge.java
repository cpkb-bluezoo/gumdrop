/*
 * TelemetryJMXBridge.java
 * Copyright (C) 2025 Chris Burdess
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

package org.bluezoo.gumdrop.telemetry;

import org.bluezoo.gumdrop.telemetry.metrics.AggregationTemporality;
import org.bluezoo.gumdrop.telemetry.metrics.HistogramDataPoint;
import org.bluezoo.gumdrop.telemetry.metrics.Meter;
import org.bluezoo.gumdrop.telemetry.metrics.MetricData;
import org.bluezoo.gumdrop.telemetry.metrics.NumberDataPoint;

import javax.management.Attribute;
import javax.management.AttributeList;
import javax.management.AttributeNotFoundException;
import javax.management.DynamicMBean;
import javax.management.InvalidAttributeValueException;
import javax.management.MBeanAttributeInfo;
import javax.management.MBeanException;
import javax.management.MBeanInfo;
import javax.management.InstanceAlreadyExistsException;
import javax.management.MBeanRegistrationException;
import javax.management.MalformedObjectNameException;
import javax.management.NotCompliantMBeanException;
import javax.management.ObjectName;
import javax.management.ReflectionException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * JMX bridge that exposes OpenTelemetry metrics via MBeans.
 *
 * <p>This bridge keeps OpenTelemetry as the single source of truth. It reads
 * metrics from {@link TelemetryConfig} and exposes them as JMX attributes.
 * Values are collected on each JMX access using cumulative temporality.
 *
 * <p>Metrics are exposed under the domain {@code org.bluezoo.gumdrop} with
 * type {@code Telemetry} and a name that includes the meter scope.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class TelemetryJMXBridge {

    private static final String DOMAIN = "org.bluezoo.gumdrop";
    private static final String TYPE = "Telemetry";
    private static final Logger logger = Logger.getLogger(TelemetryJMXBridge.class.getName());

    private final TelemetryConfig config;
    private ObjectName objectName;
    private boolean registered;

    /**
     * Creates a JMX bridge for the given telemetry configuration.
     *
     * @param config the telemetry configuration
     */
    public TelemetryJMXBridge(TelemetryConfig config) {
        this.config = config;
    }

    /**
     * Registers the metrics MBean with the platform MBean server.
     * Call this when telemetry is initialized and metrics are enabled.
     *
     * @return true if registration succeeded
     */
    public boolean register() {
        if (registered) {
            return true;
        }
        if (!config.isMetricsEnabled()) {
            return false;
        }
        try {
            objectName = new ObjectName(DOMAIN + ":type=" + TYPE);
            javax.management.MBeanServer server = java.lang.management.ManagementFactory.getPlatformMBeanServer();
            server.registerMBean(new TelemetryMetricsMBean(), objectName);
            registered = true;
            logger.info("Telemetry JMX bridge registered: " + objectName);
            return true;
        } catch (MalformedObjectNameException | MBeanRegistrationException | InstanceAlreadyExistsException | NotCompliantMBeanException e) {
            logger.warning("Failed to register Telemetry JMX bridge: " + e.getMessage());
            return false;
        }
    }

    /**
     * Unregisters the metrics MBean.
     */
    public void unregister() {
        if (!registered || objectName == null) {
            return;
        }
        try {
            javax.management.MBeanServer server = java.lang.management.ManagementFactory.getPlatformMBeanServer();
            if (server.isRegistered(objectName)) {
                server.unregisterMBean(objectName);
            }
            registered = false;
            logger.info("Telemetry JMX bridge unregistered: " + objectName);
        } catch (Exception e) {
            logger.warning("Failed to unregister Telemetry JMX bridge: " + e.getMessage());
        }
    }

    /**
     * Returns true if the bridge is registered.
     */
    public boolean isRegistered() {
        return registered;
    }

    /**
     * DynamicMBean that exposes OpenTelemetry metrics as JMX attributes.
     */
    private class TelemetryMetricsMBean implements DynamicMBean {

        @Override
        public Object getAttribute(String name) throws AttributeNotFoundException, MBeanException, ReflectionException {
            Map<String, Object> values = collectMetrics();
            Object value = values.get(name);
            if (value == null && !values.containsKey(name)) {
                throw new AttributeNotFoundException("Unknown attribute: " + name);
            }
            return value;
        }

        @Override
        public void setAttribute(Attribute attribute) throws AttributeNotFoundException, InvalidAttributeValueException, MBeanException, ReflectionException {
            throw new UnsupportedOperationException("Telemetry metrics are read-only");
        }

        @Override
        public AttributeList getAttributes(String[] names) {
            Map<String, Object> values = collectMetrics();
            AttributeList list = new AttributeList();
            for (String name : names) {
                if (values.containsKey(name)) {
                    list.add(new Attribute(name, values.get(name)));
                }
            }
            return list;
        }

        @Override
        public AttributeList setAttributes(AttributeList attributes) {
            return new AttributeList();
        }

        @Override
        public Object invoke(String actionName, Object[] params, String[] signature) throws MBeanException, ReflectionException {
            throw new UnsupportedOperationException("No operations supported");
        }

        @Override
        public MBeanInfo getMBeanInfo() {
            Map<String, MBeanAttributeInfo> attrMap = new LinkedHashMap<>();
            for (MetricData metric : collectAllMetricData()) {
                String baseName = toJmxName(metric.getName());
                switch (metric.getType()) {
                    case GAUGE:
                    case SUM:
                        attrMap.putIfAbsent(baseName, new MBeanAttributeInfo(
                                baseName,
                                "number",
                                metric.getDescription() != null ? metric.getDescription() : metric.getName(),
                                true, false, false));
                        break;
                    case HISTOGRAM:
                        attrMap.putIfAbsent(baseName + "_count", new MBeanAttributeInfo(
                                baseName + "_count",
                                "long",
                                (metric.getDescription() != null ? metric.getDescription() : metric.getName()) + " (count)",
                                true, false, false));
                        attrMap.putIfAbsent(baseName + "_sum", new MBeanAttributeInfo(
                                baseName + "_sum",
                                "double",
                                (metric.getDescription() != null ? metric.getDescription() : metric.getName()) + " (sum)",
                                true, false, false));
                        attrMap.putIfAbsent(baseName + "_min", new MBeanAttributeInfo(
                                baseName + "_min",
                                "double",
                                (metric.getDescription() != null ? metric.getDescription() : metric.getName()) + " (min)",
                                true, false, false));
                        attrMap.putIfAbsent(baseName + "_max", new MBeanAttributeInfo(
                                baseName + "_max",
                                "double",
                                (metric.getDescription() != null ? metric.getDescription() : metric.getName()) + " (max)",
                                true, false, false));
                        break;
                }
            }
            MBeanAttributeInfo[] attrs = attrMap.values().toArray(new MBeanAttributeInfo[0]);
            return new MBeanInfo(TelemetryMetricsMBean.class.getName(),
                    "Gumdrop OpenTelemetry metrics exposed via JMX",
                    attrs, null, null, null);
        }

        private Map<String, Object> collectMetrics() {
            Map<String, Object> result = new LinkedHashMap<>();
            for (MetricData metric : collectAllMetricData()) {
                String baseName = toJmxName(metric.getName());
                switch (metric.getType()) {
                    case GAUGE:
                    case SUM:
                        aggregateNumberDataPoints(metric, baseName, result);
                        break;
                    case HISTOGRAM:
                        aggregateHistogramDataPoints(metric, baseName, result);
                        break;
                }
            }
            return result;
        }

        private void aggregateNumberDataPoints(MetricData metric, String baseName, Map<String, Object> result) {
            List<NumberDataPoint> points = metric.getNumberDataPoints();
            if (points.isEmpty()) {
                result.put(baseName, 0L);
                return;
            }
            long longSum = 0;
            double doubleSum = 0;
            boolean hasDouble = false;
            for (NumberDataPoint p : points) {
                if (p.isDouble()) {
                    doubleSum += p.getDoubleValue();
                    hasDouble = true;
                } else {
                    longSum += p.getLongValue();
                }
            }
            result.put(baseName, hasDouble ? doubleSum : longSum);
        }

        private void aggregateHistogramDataPoints(MetricData metric, String baseName, Map<String, Object> result) {
            List<HistogramDataPoint> points = metric.getHistogramDataPoints();
            if (points.isEmpty()) {
                result.put(baseName + "_count", 0L);
                result.put(baseName + "_sum", 0.0);
                result.put(baseName + "_min", Double.NaN);
                result.put(baseName + "_max", Double.NaN);
                return;
            }
            long totalCount = 0;
            double totalSum = 0;
            double min = Double.POSITIVE_INFINITY;
            double max = Double.NEGATIVE_INFINITY;
            for (HistogramDataPoint p : points) {
                totalCount += p.getCount();
                totalSum += p.getSum();
                if (p.getCount() > 0) {
                    min = Math.min(min, p.getMin());
                    max = Math.max(max, p.getMax());
                }
            }
            result.put(baseName + "_count", totalCount);
            result.put(baseName + "_sum", totalSum);
            result.put(baseName + "_min", min == Double.POSITIVE_INFINITY ? Double.NaN : min);
            result.put(baseName + "_max", max == Double.NEGATIVE_INFINITY ? Double.NaN : max);
        }

        private List<MetricData> collectAllMetricData() {
            List<MetricData> all = new ArrayList<>();
            for (Meter meter : config.getMeters().values()) {
                all.addAll(meter.collect(AggregationTemporality.CUMULATIVE));
            }
            return all;
        }

        private String toJmxName(String metricName) {
            return metricName.replace('.', '_');
        }
    }
}
