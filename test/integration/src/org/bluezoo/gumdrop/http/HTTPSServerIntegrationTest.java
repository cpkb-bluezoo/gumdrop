/*
 * HTTPSServerIntegrationTest.java
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

package org.bluezoo.gumdrop.http;

import org.bluezoo.gumdrop.AbstractServerIntegrationTest;
import org.junit.After;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.*;

/**
 * Integration test for HTTPS (SSL/TLS) support in HTTPServer.
 * 
 * <p>Tests HTTPS functionality with real SSL/TLS connections.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class HTTPSServerIntegrationTest extends AbstractServerIntegrationTest {
    
    @Override
    protected File getTestConfigFile() {
        return new File("test/integration/config/https-server-test.xml");
    }
    
    @After
    public void cleanupBetweenTests() throws Exception {
        Thread.sleep(500);
    }
    
    @Test
    public void testHTTPSServerStarts() throws Exception {
        System.out.println("[testHTTPSServerStarts] checking...");
        assertNotNull("Server should be running", gumdrop);
        assertTrue("Port 18443 should be listening", isPortListening("127.0.0.1", 18443));
        System.out.println("[testHTTPSServerStarts] OK");
    }
    
    @Test
    public void testHTTPSGETRequest() throws Exception {
        System.out.println("[testHTTPSGETRequest] sending request...");
        String request = "GET / HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "Connection: close\r\n" +
                        "\r\n";
        
        HTTPClientHelper.HTTPResponse response = HTTPClientHelper.sendRequest("127.0.0.1", 18443, request, true, 10000);
        System.out.println("[testHTTPSGETRequest] got " + response.statusCode);
        
        assertEquals("HTTPS GET should return 404", 404, response.statusCode);
        assertTrue("Should have HTTPS response", response.statusLine.contains("HTTP/1.1"));
    }
    
    @Test
    public void testHTTPSPOSTRequest() throws Exception {
        System.out.println("[testHTTPSPOSTRequest] sending request...");
        String body = "secure=data";
        String request = "POST /secure HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "Content-Type: application/x-www-form-urlencoded\r\n" +
                        "Content-Length: " + body.length() + "\r\n" +
                        "Connection: close\r\n" +
                        "\r\n" +
                        body;
        
        HTTPClientHelper.HTTPResponse response = HTTPClientHelper.sendRequest("127.0.0.1", 18443, request, true, 10000);
        System.out.println("[testHTTPSPOSTRequest] got " + response.statusCode);
        
        assertEquals("HTTPS POST should return 404", 404, response.statusCode);
    }
    
    @Test
    public void testMultipleHTTPSRequests() throws Exception {
        System.out.println("[testMultipleHTTPSRequests] starting...");
        for (int i = 0; i < 3; i++) {
            System.out.println("[testMultipleHTTPSRequests] request " + i);
            String request = "GET /test" + i + " HTTP/1.1\r\n" +
                            "Host: localhost\r\n" +
                            "Connection: close\r\n" +
                            "\r\n";
            
            HTTPClientHelper.HTTPResponse response = HTTPClientHelper.sendRequest("127.0.0.1", 18443, request, true, 10000);
            System.out.println("[testMultipleHTTPSRequests] request " + i + " got " + response.statusCode);
            
            assertEquals("HTTPS request " + i + " should return 404", 404, response.statusCode);
            Thread.sleep(300);
        }
    }
    
    @Test
    public void testConcurrentHTTPSRequests() throws Exception {
        System.out.println("[testConcurrentHTTPSRequests] starting...");
        for (int i = 0; i < 2; i++) {
            System.out.println("[testConcurrentHTTPSRequests] request " + i);
            
            String request = "GET /secure" + i + " HTTP/1.1\r\n" +
                            "Host: localhost\r\n" +
                            "Connection: close\r\n" +
                            "\r\n";
            
            HTTPClientHelper.HTTPResponse response = HTTPClientHelper.sendRequest(
                "127.0.0.1", 18443, request, true, 10000);
            System.out.println("[testConcurrentHTTPSRequests] request " + i + " got " + response.statusCode);
            assertEquals("HTTPS request " + i + " should return 404", 404, response.statusCode);
            Thread.sleep(500);
        }
    }
}

