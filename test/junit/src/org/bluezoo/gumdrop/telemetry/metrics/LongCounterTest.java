package org.bluezoo.gumdrop.telemetry.metrics;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link LongCounter}.
 */
public class LongCounterTest {

    @Test
    public void testBuilder() {
        LongCounter counter = new LongCounter.Builder("http.requests")
                .setDescription("Total HTTP requests")
                .setUnit("1")
                .build();
        assertEquals("http.requests", counter.getName());
        assertEquals("Total HTTP requests", counter.getDescription());
        assertEquals("1", counter.getUnit());
    }

    @Test
    public void testAddWithoutAttributes() {
        LongCounter counter = new LongCounter.Builder("test.counter").build();
        counter.add(5);
        counter.add(3);

        MetricData data = counter.collect(AggregationTemporality.CUMULATIVE);
        assertNotNull(data);
        assertEquals("test.counter", data.getName());
        assertEquals(MetricData.Type.SUM, data.getType());
        assertTrue(data.isMonotonic());
        assertEquals(1, data.getNumberDataPoints().size());
        assertEquals(8, data.getNumberDataPoints().get(0).getLongValue());
    }

    @Test
    public void testAddWithAttributes() {
        LongCounter counter = new LongCounter.Builder("http.requests").build();
        Attributes get = Attributes.of("method", "GET");
        Attributes post = Attributes.of("method", "POST");

        counter.add(10, get);
        counter.add(3, post);
        counter.add(5, get);

        MetricData data = counter.collect(AggregationTemporality.CUMULATIVE);
        assertNotNull(data);
        assertEquals(2, data.getNumberDataPoints().size());

        long getTotal = 0;
        long postTotal = 0;
        for (NumberDataPoint dp : data.getNumberDataPoints()) {
            if (dp.getAttributes().equals(get)) {
                getTotal = dp.getLongValue();
            } else if (dp.getAttributes().equals(post)) {
                postTotal = dp.getLongValue();
            }
        }
        assertEquals(15, getTotal);
        assertEquals(3, postTotal);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testAddNegativeValue() {
        LongCounter counter = new LongCounter.Builder("test").build();
        counter.add(-1);
    }

    @Test
    public void testCollectEmpty() {
        LongCounter counter = new LongCounter.Builder("test").build();
        assertNull(counter.collect(AggregationTemporality.CUMULATIVE));
    }

    @Test
    public void testCollectDelta() {
        LongCounter counter = new LongCounter.Builder("test").build();
        counter.add(10);

        MetricData first = counter.collect(AggregationTemporality.DELTA);
        assertNotNull(first);
        assertEquals(10, first.getNumberDataPoints().get(0).getLongValue());

        counter.add(5);
        MetricData second = counter.collect(AggregationTemporality.DELTA);
        assertNotNull(second);
        assertEquals(5, second.getNumberDataPoints().get(0).getLongValue());
    }

    @Test
    public void testCollectCumulative() {
        LongCounter counter = new LongCounter.Builder("test").build();
        counter.add(10);

        MetricData first = counter.collect(AggregationTemporality.CUMULATIVE);
        assertNotNull(first);
        assertEquals(10, first.getNumberDataPoints().get(0).getLongValue());

        counter.add(5);
        MetricData second = counter.collect(AggregationTemporality.CUMULATIVE);
        assertNotNull(second);
        assertEquals(15, second.getNumberDataPoints().get(0).getLongValue());
    }

    @Test
    public void testAddZero() {
        LongCounter counter = new LongCounter.Builder("test").build();
        counter.add(0);

        MetricData data = counter.collect(AggregationTemporality.CUMULATIVE);
        assertNotNull(data);
        assertEquals(0, data.getNumberDataPoints().get(0).getLongValue());
    }

    @Test
    public void testNullAttributesTreatedAsEmpty() {
        LongCounter counter = new LongCounter.Builder("test").build();
        counter.add(5, null);
        counter.add(3);

        MetricData data = counter.collect(AggregationTemporality.CUMULATIVE);
        assertNotNull(data);
        assertEquals(1, data.getNumberDataPoints().size());
        assertEquals(8, data.getNumberDataPoints().get(0).getLongValue());
    }

    @Test
    public void testTemporalityInMetricData() {
        LongCounter counter = new LongCounter.Builder("test").build();
        counter.add(1);

        MetricData cum = counter.collect(AggregationTemporality.CUMULATIVE);
        assertEquals(AggregationTemporality.CUMULATIVE, cum.getTemporality());

        counter.add(1);
        MetricData delta = counter.collect(AggregationTemporality.DELTA);
        assertEquals(AggregationTemporality.DELTA, delta.getTemporality());
    }
}
