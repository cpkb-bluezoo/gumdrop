/*
 * SimpleHTTPClientTest.java
 * Simple test to verify HTTP client functionality.
 */

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.http.client.DefaultHttpResponseHandler;
import org.bluezoo.gumdrop.http.client.HttpClientHandler;
import org.bluezoo.gumdrop.http.HttpClient;
import org.bluezoo.gumdrop.http.client.HttpRequest;
import org.bluezoo.gumdrop.http.client.HttpResponse;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Simple test to verify HTTP client functionality.
 *
 * <p>This example demonstrates how to use the Gumdrop HTTP client to make
 * basic HTTP requests and handle responses in an event-driven manner.
 *
 * <p>The client is connected with {@code connect(gumdrop, handler)}; requests
 * are created and sent once the handler reports {@code onConnected}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SimpleHTTPClientTest {

    /**
     * Client handler that runs {@link #ready()} once the connection is
     * established. In Gumdrop 3 a request can only be created after
     * {@code connect(gumdrop, handler)} has reported {@code onConnected}.
     */
    private abstract static class WhenConnected implements HttpClientHandler {
        private final String label;
        private final CountDownLatch latch;

        WhenConnected(String label, CountDownLatch latch) {
            this.label = label;
            this.latch = latch;
        }

        abstract void ready();

        @Override
        public void onConnected(Endpoint endpoint) {
            ready();
        }

        @Override
        public void onSecurityEstablished(SecurityInfo info) {
        }

        @Override
        public void onError(Exception cause) {
            System.err.println(label + ": connection failed: " + cause.getMessage());
            latch.countDown();
        }

        @Override
        public void onDisconnected() {
        }
    }

    private static final String TEST_HOST = "httpbin.org";
    private static final int TEST_PORT = 80;

    public static void main(String[] args) {
        System.out.println("Starting HTTP Client Test");

        Gumdrop gumdrop = Gumdrop.boot();
        try {
            // Test 1: Simple GET request
            testSimpleGET(gumdrop);

            // Test 2: POST request with body
            testPOST(gumdrop);

            System.out.println("\nAll tests completed successfully!");

        } catch (Exception e) {
            System.err.println("Test failed: " + e.getMessage());
            e.printStackTrace();
        } finally {
            gumdrop.shutdown();
        }
    }

    /**
     * Tests a simple GET request to httpbin.org/get.
     *
     * <p>This demonstrates the simplest usage pattern: connect, then send a
     * GET from the {@code onConnected} callback.
     */
    private static void testSimpleGET(final Gumdrop gumdrop) throws Exception {
        System.out.println("\n=== Testing Simple GET Request ===");

        final CountDownLatch latch = new CountDownLatch(1);
        final StringBuilder responseBody = new StringBuilder();

        final HttpClient client = new HttpClient(TEST_HOST, TEST_PORT);

        client.connect(gumdrop, new WhenConnected("SimpleHTTPClientTest", latch) {
            @Override
            void ready() {
                HttpRequest request = client.get("/get");
                request.header("User-Agent", "Gumdrop-HTTP-Client/1.0");
                request.header("Accept", "application/json");

                request.send(new DefaultHttpResponseHandler() {
                    @Override
                    public void ok(HttpResponse response) {
                        System.out.println("Response: " + response.getStatus());
                    }

                    @Override
                    public void error(HttpResponse response) {
                        System.out.println("Error response: " + response.getStatus());
                    }

                    @Override
                    public void responseBodyContent(ByteBuffer data) {
                        String chunk = StandardCharsets.UTF_8.decode(data).toString();
                        responseBody.append(chunk);
                    }

                    @Override
                    public void close() {
                        System.out.println("Response complete");
                        System.out.println("  Body length: " + responseBody.length() + " characters");

                        // Print first 200 characters of response
                        String preview = responseBody.toString();
                        if (preview.length() > 200) {
                            preview = preview.substring(0, 200) + "...";
                        }
                        System.out.println("  Preview: " + preview);

                        client.close();
                        latch.countDown();
                    }

                    @Override
                    public void failed(Exception ex) {
                        System.err.println("Request failed: " + ex.getMessage());
                        client.close();
                        latch.countDown();
                    }
                });

                System.out.println("Sent: GET /get");
            }
        });

        // Wait for response (timeout after 30 seconds)
        boolean completed = latch.await(30, TimeUnit.SECONDS);
        if (!completed) {
            client.close();
            throw new Exception("Test timed out after 30 seconds");
        }

        System.out.println("GET test completed successfully");
    }

    /**
     * Tests a POST request with JSON body to httpbin.org/post.
     */
    private static void testPOST(final Gumdrop gumdrop) throws Exception {
        System.out.println("\n=== Testing POST Request ===");

        final CountDownLatch latch = new CountDownLatch(1);
        final StringBuilder responseBody = new StringBuilder();

        final HttpClient client = new HttpClient(TEST_HOST, TEST_PORT);

        // Prepare JSON body
        String jsonBody = "{\"name\":\"Gumdrop\",\"type\":\"HTTP Client\",\"version\":\"1.0\"}";
        byte[] bodyBytes = jsonBody.getBytes(StandardCharsets.UTF_8);

        // Create and send POST request
        client.connect(gumdrop, new WhenConnected("SimpleHTTPClientTest", latch) {
            @Override
            void ready() {
                HttpRequest request = client.post("/post");
                request.header("User-Agent", "Gumdrop-HTTP-Client/1.0");
                request.header("Content-Type", "application/json");
                request.header("Content-Length", String.valueOf(bodyBytes.length));

                // Start request body, send data, then end
                request.startRequestBody(new DefaultHttpResponseHandler() {
                    @Override
                    public void ok(HttpResponse response) {
                        System.out.println("Response: " + response.getStatus());
                    }

                    @Override
                    public void error(HttpResponse response) {
                        System.out.println("Error response: " + response.getStatus());
                    }

                    @Override
                    public void responseBodyContent(ByteBuffer data) {
                        String chunk = StandardCharsets.UTF_8.decode(data).toString();
                        responseBody.append(chunk);
                    }

                    @Override
                    public void close() {
                        System.out.println("Response complete");
                        System.out.println("  Body length: " + responseBody.length() + " characters");
                        client.close();
                        latch.countDown();
                    }

                    @Override
                    public void failed(Exception ex) {
                        System.err.println("Request failed: " + ex.getMessage());
                        client.close();
                        latch.countDown();
                    }
                });

                request.requestBodyContent(ByteBuffer.wrap(bodyBytes));
                request.endRequestBody();

                System.out.println("Sent: POST /post (" + bodyBytes.length + " bytes)");
            }
        });

        // Wait for response (timeout after 30 seconds)
        boolean completed = latch.await(30, TimeUnit.SECONDS);
        if (!completed) {
            client.close();
            throw new Exception("Test timed out after 30 seconds");
        }

        System.out.println("POST test completed successfully");
    }
}
