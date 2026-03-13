package org.bluezoo.gumdrop.telemetry.metrics;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link DoubleHistogram}.
 */
public class DoubleHistogramTest {

    @Test
    public void testBuilder() {
        DoubleHistogram histogram = new DoubleHistogram.Builder("http.duration")
                .setDescription("Request duration")
                .setUnit("ms")
                .build();
        assertEquals("http.duration", histogram.getName());
        assertEquals("Request duration", histogram.getDescription());
        assertEquals("ms", histogram.getUnit());
    }

    @Test
    public void testRecordAndCollect() {
        DoubleHistogram histogram = new DoubleHistogram.Builder("test")
                .setExplicitBuckets(10, 50, 100)
                .build();

        histogram.record(5.0);
        histogram.record(25.0);
        histogram.record(75.0);
        histogram.record(150.0);

        MetricData data = histogram.collect(AggregationTemporality.CUMULATIVE);
        assertNotNull(data);
        assertEquals("test", data.getName());
        assertEquals(MetricData.Type.HISTOGRAM, data.getType());
        assertEquals(1, data.getHistogramDataPoints().size());

        HistogramDataPoint dp = data.getHistogramDataPoints().get(0);
        assertEquals(4, dp.getCount());
        assertEquals(255.0, dp.getSum(), 0.001);
        assertEquals(5.0, dp.getMin(), 0.001);
        assertEquals(150.0, dp.getMax(), 0.001);
    }

    @Test
    public void testBucketDistribution() {
        DoubleHistogram histogram = new DoubleHistogram.Builder("test")
                .setExplicitBuckets(10, 50, 100)
                .build();

        // 4 buckets: [<10], [10-50), [50-100), [>=100]
        histogram.record(5.0);   // bucket 0
        histogram.record(15.0);  // bucket 1
        histogram.record(30.0);  // bucket 1
        histogram.record(75.0);  // bucket 2
        histogram.record(200.0); // bucket 3

        MetricData data = histogram.collect(AggregationTemporality.CUMULATIVE);
        HistogramDataPoint dp = data.getHistogramDataPoints().get(0);
        long[] counts = dp.getBucketCounts();
        assertEquals(4, counts.length); // boundaries.length + 1
        assertEquals(1, counts[0]); // <10
        assertEquals(2, counts[1]); // 10-50
        assertEquals(1, counts[2]); // 50-100
        assertEquals(1, counts[3]); // >=100
    }

    @Test
    public void testRecordWithAttributes() {
        DoubleHistogram histogram = new DoubleHistogram.Builder("test")
                .setExplicitBuckets(10, 100)
                .build();

        Attributes get = Attributes.of("method", "GET");
        Attributes post = Attributes.of("method", "POST");

        histogram.record(5.0, get);
        histogram.record(50.0, get);
        histogram.record(25.0, post);

        MetricData data = histogram.collect(AggregationTemporality.CUMULATIVE);
        assertNotNull(data);
        assertEquals(2, data.getHistogramDataPoints().size());
    }

    @Test
    public void testCollectEmpty() {
        DoubleHistogram histogram = new DoubleHistogram.Builder("test").build();
        assertNull(histogram.collect(AggregationTemporality.CUMULATIVE));
    }

    @Test
    public void testCollectDelta() {
        DoubleHistogram histogram = new DoubleHistogram.Builder("test")
                .setExplicitBuckets(10, 100)
                .build();

        histogram.record(5.0);
        histogram.record(50.0);

        MetricData first = histogram.collect(AggregationTemporality.DELTA);
        assertNotNull(first);
        HistogramDataPoint dp1 = first.getHistogramDataPoints().get(0);
        assertEquals(2, dp1.getCount());

        histogram.record(75.0);
        MetricData second = histogram.collect(AggregationTemporality.DELTA);
        assertNotNull(second);
        HistogramDataPoint dp2 = second.getHistogramDataPoints().get(0);
        assertEquals(1, dp2.getCount());
    }

    @Test
    public void testCollectCumulative() {
        DoubleHistogram histogram = new DoubleHistogram.Builder("test")
                .setExplicitBuckets(10, 100)
                .build();

        histogram.record(5.0);
        histogram.collect(AggregationTemporality.CUMULATIVE);

        histogram.record(50.0);
        MetricData data = histogram.collect(AggregationTemporality.CUMULATIVE);
        assertNotNull(data);
        HistogramDataPoint dp = data.getHistogramDataPoints().get(0);
        assertEquals(2, dp.getCount());
        assertEquals(55.0, dp.getSum(), 0.001);
    }

    @Test
    public void testDefaultBoundaries() {
        DoubleHistogram histogram = new DoubleHistogram.Builder("test").build();
        histogram.record(1.0);
        histogram.record(100.0);
        histogram.record(10000.0);

        MetricData data = histogram.collect(AggregationTemporality.CUMULATIVE);
        assertNotNull(data);
        assertEquals(3, data.getHistogramDataPoints().get(0).getCount());
    }

    @Test
    public void testNullAttributesTreatedAsEmpty() {
        DoubleHistogram histogram = new DoubleHistogram.Builder("test")
                .setExplicitBuckets(10, 100)
                .build();
        histogram.record(5.0, null);
        histogram.record(15.0);

        MetricData data = histogram.collect(AggregationTemporality.CUMULATIVE);
        assertEquals(1, data.getHistogramDataPoints().size());
        assertEquals(2, data.getHistogramDataPoints().get(0).getCount());
    }

    @Test
    public void testTemporalityInMetricData() {
        DoubleHistogram histogram = new DoubleHistogram.Builder("test")
                .setExplicitBuckets(10)
                .build();
        histogram.record(5.0);

        MetricData cum = histogram.collect(AggregationTemporality.CUMULATIVE);
        assertEquals(AggregationTemporality.CUMULATIVE, cum.getTemporality());
    }
}
