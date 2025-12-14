package org.bluezoo.gumdrop.telemetry;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;

import org.bluezoo.gumdrop.telemetry.protobuf.TraceSerializer;

/**
 * JUnit 4 test class for TraceSerializer.
 * Tests OTLP protobuf trace serialization.
 */
public class TraceSerializerTest {

    @Test
    public void testSerializeSimpleTrace() throws IOException {
        // Create a simple trace
        Trace trace = new Trace("test-operation", SpanKind.SERVER);
        trace.getRootSpan().addAttribute("test.key", "test-value");
        trace.getRootSpan().end();

        // Serialize it
        TraceSerializer serializer = new TraceSerializer("test-service");
        ByteBuffer buffer = serializer.serialize(trace);

        assertTrue("Expected serialized data", buffer.remaining() > 0);

        // Verify basic structure by checking for known bytes
        // The first bytes should be the TracesData message
        byte firstByte = buffer.get(0);
        // Field 1 (ResourceSpans), wire type LEN = (1 << 3) | 2 = 0x0A
        assertEquals("First byte should be TracesData.resource_spans tag", (byte) 0x0A, firstByte);
    }

    @Test
    public void testSerializeTraceWithEvents() throws IOException {
        Trace trace = new Trace("operation-with-events", SpanKind.SERVER);
        Span root = trace.getRootSpan();

        // Add an event
        root.addEvent("event1");
        SpanEvent event2 = new SpanEvent("event2");
        event2.addAttribute("event.key", "event-value");
        root.addEvent(event2);

        root.end();

        TraceSerializer serializer = new TraceSerializer("test-service", "1.0.0", null, null);
        ByteBuffer buffer = serializer.serialize(trace);

        assertTrue("Expected serialized data with events", buffer.remaining() > 50);
    }

    @Test
    public void testSerializeTraceWithChildSpans() throws IOException {
        Trace trace = new Trace("parent-operation", SpanKind.SERVER);

        // Create child spans
        Span child1 = trace.startSpan("child-1", SpanKind.INTERNAL);
        child1.addAttribute("child", "1");
        child1.end();

        Span child2 = trace.startSpan("child-2", SpanKind.CLIENT);
        child2.addAttribute("child", "2");
        child2.setStatusOk();
        child2.end();

        trace.getRootSpan().end();

        TraceSerializer serializer = new TraceSerializer("test-service");
        ByteBuffer buffer = serializer.serialize(trace);

        // Should have data for 3 spans
        assertTrue("Expected larger output for multiple spans", buffer.remaining() > 100);
    }

    @Test
    public void testSerializeTraceWithLinks() throws IOException {
        // Create a remote span context to link to
        byte[] remoteTraceId = new byte[16];
        byte[] remoteSpanId = new byte[8];
        for (int i = 0; i < 16; i++) {
            remoteTraceId[i] = (byte) (i + 1);
        }
        for (int i = 0; i < 8; i++) {
            remoteSpanId[i] = (byte) (i + 10);
        }
        SpanContext remoteContext = new SpanContext(remoteTraceId, remoteSpanId, true);

        Trace trace = new Trace("linked-operation", SpanKind.SERVER);
        trace.getRootSpan().addLink(remoteContext);
        trace.getRootSpan().end();

        TraceSerializer serializer = new TraceSerializer("test-service");
        ByteBuffer buffer = serializer.serialize(trace);

        assertTrue("Expected serialized data with links", buffer.remaining() > 50);
    }

    @Test
    public void testSerializeTraceWithStatus() throws IOException {
        Trace trace = new Trace("error-operation", SpanKind.SERVER);
        trace.getRootSpan().setStatusError("Something went wrong");
        trace.getRootSpan().end();

        TraceSerializer serializer = new TraceSerializer("test-service");
        ByteBuffer buffer = serializer.serialize(trace);

        assertTrue("Expected serialized data", buffer.remaining() > 0);

        // "Something went wrong" should appear in the output
        assertTrue("Should contain error message", containsString(buffer, "Something went wrong"));
    }

    @Test
    public void testSerializeTraceWithException() throws IOException {
        Trace trace = new Trace("exception-operation", SpanKind.SERVER);
        try {
            throw new RuntimeException("Test exception message");
        } catch (RuntimeException e) {
            trace.getRootSpan().recordException(e);
        }
        trace.getRootSpan().end();

        TraceSerializer serializer = new TraceSerializer("test-service");
        ByteBuffer buffer = serializer.serialize(trace);

        // Should contain exception type and message
        assertTrue("Should contain exception type",
                containsString(buffer, "java.lang.RuntimeException"));
    }

    @Test
    public void testSerializeWithResourceAttributes() throws IOException {
        java.util.Map<String, String> resourceAttrs = new java.util.HashMap<String, String>();
        resourceAttrs.put("deployment.environment", "test");
        resourceAttrs.put("host.name", "test-host");

        Trace trace = new Trace("attributed-operation", SpanKind.SERVER);
        trace.getRootSpan().end();

        TraceSerializer serializer = new TraceSerializer(
                "test-service", "2.0.0", "test-namespace", resourceAttrs);
        ByteBuffer buffer = serializer.serialize(trace);

        // Should contain service name and namespace
        assertTrue("Should contain service name", containsString(buffer, "test-service"));
        assertTrue("Should contain service version", containsString(buffer, "2.0.0"));
        assertTrue("Should contain namespace", containsString(buffer, "test-namespace"));
    }

    @Test
    public void testSpanContextTraceparent() {
        // Test W3C traceparent format
        byte[] traceId = new byte[16];
        byte[] spanId = new byte[8];
        for (int i = 0; i < 16; i++) {
            traceId[i] = (byte) ((i + 1) * 16 + (i + 1)); // 0x11, 0x22, 0x33, ...
        }
        for (int i = 0; i < 8; i++) {
            spanId[i] = (byte) ((i + 1) * 17); // 0x11, 0x22, 0x33, ...
        }

        SpanContext context = new SpanContext(traceId, spanId, true);
        String traceparent = context.toTraceparent();

        // Should be: 00-{32 hex}-{16 hex}-01
        assertNotNull(traceparent);
        assertEquals(55, traceparent.length());
        assertTrue(traceparent.startsWith("00-"));
        assertTrue(traceparent.endsWith("-01")); // sampled flag

        // Parse it back
        SpanContext parsed = SpanContext.fromTraceparent(traceparent);
        assertNotNull(parsed);
        assertArrayEquals(traceId, parsed.getTraceId());
        assertArrayEquals(spanId, parsed.getSpanId());
        assertTrue(parsed.isSampled());
    }

    @Test
    public void testTraceContinuation() throws IOException {
        // Test continuing a trace from a remote context
        String remoteTraceparent = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";

        Trace trace = Trace.fromTraceparent(remoteTraceparent, "local-operation", SpanKind.SERVER);
        trace.getRootSpan().end();

        // The trace ID should match the remote trace ID
        assertEquals("0af7651916cd43dd8448eb211c80319c", trace.getTraceIdHex());
        assertTrue(trace.isSampled());

        // Serialize it
        TraceSerializer serializer = new TraceSerializer("test-service");
        ByteBuffer buffer = serializer.serialize(trace);

        assertTrue("Expected serialized data", buffer.remaining() > 0);
    }

    @Test
    public void testInvalidTraceparent() {
        // Test with invalid traceparent - should create new trace
        Trace trace = Trace.fromTraceparent("invalid-traceparent", "operation", SpanKind.SERVER);

        // Should have created a new trace (not null)
        assertNotNull(trace);
        assertNotNull(trace.getTraceIdHex());
        assertEquals(32, trace.getTraceIdHex().length());
    }

    // -- Helper methods --

    private void assertArrayEquals(byte[] expected, byte[] actual) {
        assertEquals("Array length mismatch", expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) {
            assertEquals("Byte mismatch at index " + i, expected[i], actual[i]);
        }
    }

    private String bufferToHex(ByteBuffer buffer) {
        StringBuilder sb = new StringBuilder();
        int pos = buffer.position();
        while (buffer.hasRemaining()) {
            sb.append(String.format("%02x", buffer.get()));
        }
        buffer.position(pos);
        return sb.toString();
    }

    private boolean containsString(ByteBuffer buffer, String search) {
        byte[] searchBytes = search.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int pos = buffer.position();
        int limit = buffer.limit();

        outer:
        for (int i = pos; i <= limit - searchBytes.length; i++) {
            for (int j = 0; j < searchBytes.length; j++) {
                if (buffer.get(i + j) != searchBytes[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

}
