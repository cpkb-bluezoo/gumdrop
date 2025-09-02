package org.bluezoo.gumdrop.util;

import org.junit.Test;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import java.io.*;
import java.util.*;
import javax.mail.internet.InternetHeaders;

/**
 * JUnit 4 test class for Multipart parsing.
 */
@RunWith(Parameterized.class)
public class MultipartTest {

    static String[] RESOURCES = new String[] {
        "form-data-1.eml",
        "form-data-2.eml",
        "form-data-3.eml",
        "form-data-4.eml",
        "form-data-5.eml",
        "form-data-6.eml",
        "form-data-7.eml"
    };

    String resourceName;

    public MultipartTest(String resourceName) {
        this.resourceName = resourceName;
    }

    @Parameters(name = "Test for resource: {0}")
    public static Collection<Object[]> data() {
        List<Object[]> parameters = new ArrayList<>();
        for (String resource : RESOURCES) {
            parameters.add(new Object[] { resource });
        }
        return parameters;
    }

    @Test
    public void testCountParts() throws Exception {
        Content content = new TestContent(resourceName);
        InputStream in = content.getInputStream();
        InternetHeaders headers = new InternetHeaders(in);
        int numParts = getNumParts(headers);
        boolean expectFail = isExpectFail(headers);
        try {
            Multipart multipart = new Multipart(headers, content);
            List<Part> parts = multipart.getParts();
            assertEquals(numParts, parts.size());
        } catch (Exception e) {
            if (!expectFail) {
                System.err.println(resourceName);
                e.printStackTrace(System.err);
            }
            assertTrue(expectFail);
        }
    }

    private int getNumParts(InternetHeaders headers) {
        String[] v = headers.getHeader("X-Num-Parts");
        return Integer.parseInt(v[0]);
    }

    private boolean isExpectFail(InternetHeaders headers) {
        return headers.getHeader("X-Expect-Fail") != null;
    }

    public static void main(String[] args) throws Exception {
        MultipartTest test = new MultipartTest(args[0]);
        test.testCountParts();
    }

    static class TestContent implements Content {

        InputStream in;

        TestContent(String resourceName) {
            in = MultipartTest.class.getResourceAsStream(resourceName);
        }

        @Override public InputStream getInputStream() throws IOException {
            return in;
        }

        @Override public OutputStream getOutputStream() throws IOException {
            throw new UnsupportedOperationException();
        }

        @Override public long length() {
            return -1L;
        }

        @Override public Content create(String name) {
            return new ByteArrayContent();
        }

    }

    static class ByteArrayContent implements Content {

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ByteArrayInputStream in;

        @Override public InputStream getInputStream() throws IOException {
            if (in == null) {
                in = new ByteArrayInputStream(bytes.toByteArray());
            }
            return in;
        }

        @Override public OutputStream getOutputStream() throws IOException {
            return bytes;
        }

        @Override public long length() {
            return (long) bytes.size();
        }

        @Override public Content create(String name) {
            return new ByteArrayContent();
        }

    }

}
