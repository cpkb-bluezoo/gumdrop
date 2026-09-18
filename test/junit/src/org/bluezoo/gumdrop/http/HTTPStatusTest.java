package org.bluezoo.gumdrop.http;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link HttpStatus}.
 */
public class HTTPStatusTest {

    @Test
    public void testInformationalCategory() {
        assertTrue(HttpStatus.CONTINUE.isInformational());
        assertTrue(HttpStatus.SWITCHING_PROTOCOLS.isInformational());
        assertTrue(HttpStatus.PROCESSING.isInformational());
        assertTrue(HttpStatus.EARLY_HINTS.isInformational());

        assertFalse(HttpStatus.OK.isInformational());
        assertFalse(HttpStatus.BAD_REQUEST.isInformational());
    }

    @Test
    public void testSuccessCategory() {
        assertTrue(HttpStatus.OK.isSuccess());
        assertTrue(HttpStatus.CREATED.isSuccess());
        assertTrue(HttpStatus.NO_CONTENT.isSuccess());
        assertTrue(HttpStatus.PARTIAL_CONTENT.isSuccess());
        assertTrue(HttpStatus.MULTI_STATUS.isSuccess());
        assertTrue(HttpStatus.IM_USED.isSuccess());

        assertFalse(HttpStatus.CONTINUE.isSuccess());
        assertFalse(HttpStatus.MOVED_PERMANENTLY.isSuccess());
    }

    @Test
    public void testRedirectionCategory() {
        assertTrue(HttpStatus.MULTIPLE_CHOICES.isRedirection());
        assertTrue(HttpStatus.MOVED_PERMANENTLY.isRedirection());
        assertTrue(HttpStatus.FOUND.isRedirection());
        assertTrue(HttpStatus.NOT_MODIFIED.isRedirection());
        assertTrue(HttpStatus.TEMPORARY_REDIRECT.isRedirection());
        assertTrue(HttpStatus.PERMANENT_REDIRECT.isRedirection());

        assertFalse(HttpStatus.OK.isRedirection());
        assertFalse(HttpStatus.BAD_REQUEST.isRedirection());
    }

    @Test
    public void testClientErrorCategory() {
        assertTrue(HttpStatus.BAD_REQUEST.isClientError());
        assertTrue(HttpStatus.UNAUTHORIZED.isClientError());
        assertTrue(HttpStatus.FORBIDDEN.isClientError());
        assertTrue(HttpStatus.NOT_FOUND.isClientError());
        assertTrue(HttpStatus.METHOD_NOT_ALLOWED.isClientError());
        assertTrue(HttpStatus.TOO_MANY_REQUESTS.isClientError());
        assertTrue(HttpStatus.UNAVAILABLE_FOR_LEGAL_REASONS.isClientError());

        assertFalse(HttpStatus.OK.isClientError());
        assertFalse(HttpStatus.INTERNAL_SERVER_ERROR.isClientError());
    }

    @Test
    public void testServerErrorCategory() {
        assertTrue(HttpStatus.INTERNAL_SERVER_ERROR.isServerError());
        assertTrue(HttpStatus.NOT_IMPLEMENTED.isServerError());
        assertTrue(HttpStatus.BAD_GATEWAY.isServerError());
        assertTrue(HttpStatus.SERVICE_UNAVAILABLE.isServerError());
        assertTrue(HttpStatus.GATEWAY_TIMEOUT.isServerError());
        assertTrue(HttpStatus.NETWORK_AUTHENTICATION_REQUIRED.isServerError());

        assertFalse(HttpStatus.OK.isServerError());
        assertFalse(HttpStatus.BAD_REQUEST.isServerError());
    }

    @Test
    public void testIsError() {
        assertTrue(HttpStatus.BAD_REQUEST.isError());
        assertTrue(HttpStatus.NOT_FOUND.isError());
        assertTrue(HttpStatus.INTERNAL_SERVER_ERROR.isError());
        assertTrue(HttpStatus.SERVICE_UNAVAILABLE.isError());

        assertFalse(HttpStatus.OK.isError());
        assertFalse(HttpStatus.CONTINUE.isError());
        assertFalse(HttpStatus.MOVED_PERMANENTLY.isError());
    }

    @Test
    public void testPseudoStatus() {
        assertTrue(HttpStatus.REDIRECT_LOOP.isPseudoStatus());
        assertTrue(HttpStatus.UNKNOWN.isPseudoStatus());

        assertFalse(HttpStatus.OK.isPseudoStatus());
        assertFalse(HttpStatus.INTERNAL_SERVER_ERROR.isPseudoStatus());
        assertFalse(HttpStatus.CONTINUE.isPseudoStatus());
    }

    @Test
    public void testPseudoStatusNotInCategories() {
        assertFalse(HttpStatus.REDIRECT_LOOP.isInformational());
        assertFalse(HttpStatus.REDIRECT_LOOP.isSuccess());
        assertFalse(HttpStatus.REDIRECT_LOOP.isRedirection());
        assertFalse(HttpStatus.REDIRECT_LOOP.isClientError());
        assertFalse(HttpStatus.REDIRECT_LOOP.isServerError());
        assertFalse(HttpStatus.REDIRECT_LOOP.isError());
    }

    @Test
    public void testFromCodeKnownStatuses() {
        assertEquals(HttpStatus.OK, HttpStatus.fromCode(200));
        assertEquals(HttpStatus.NOT_FOUND, HttpStatus.fromCode(404));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, HttpStatus.fromCode(500));
        assertEquals(HttpStatus.CONTINUE, HttpStatus.fromCode(100));
        assertEquals(HttpStatus.MOVED_PERMANENTLY, HttpStatus.fromCode(301));
        assertEquals(HttpStatus.IM_A_TEAPOT, HttpStatus.fromCode(418));
    }

    @Test
    public void testFromCodeUnrecognized() {
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, HttpStatus.fromCode(999));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, HttpStatus.fromCode(0));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, HttpStatus.fromCode(600));
    }

    @Test
    public void testStatusCodes() {
        assertEquals(100, HttpStatus.CONTINUE.code);
        assertEquals(200, HttpStatus.OK.code);
        assertEquals(301, HttpStatus.MOVED_PERMANENTLY.code);
        assertEquals(400, HttpStatus.BAD_REQUEST.code);
        assertEquals(404, HttpStatus.NOT_FOUND.code);
        assertEquals(500, HttpStatus.INTERNAL_SERVER_ERROR.code);
        assertEquals(-1, HttpStatus.REDIRECT_LOOP.code);
        assertEquals(-2, HttpStatus.UNKNOWN.code);
    }

    @Test
    public void testCategoriesMutuallyExclusive() {
        for (HttpStatus status : HttpStatus.values()) {
            if (status.isPseudoStatus()) {
                continue;
            }
            int categoryCount = 0;
            if (status.isInformational()) categoryCount++;
            if (status.isSuccess()) categoryCount++;
            if (status.isRedirection()) categoryCount++;
            if (status.isClientError()) categoryCount++;
            if (status.isServerError()) categoryCount++;
            assertEquals("Status " + status + " (" + status.code + ") should be in exactly one category",
                    1, categoryCount);
        }
    }

    @Test
    public void testIsErrorConsistentWithClientAndServerError() {
        for (HttpStatus status : HttpStatus.values()) {
            if (status.isPseudoStatus()) {
                continue;
            }
            assertEquals("isError() should equal isClientError() || isServerError() for " + status,
                    status.isClientError() || status.isServerError(), status.isError());
        }
    }

    @Test
    public void testFromCodeRoundTrip() {
        for (HttpStatus status : HttpStatus.values()) {
            if (status.isPseudoStatus()) {
                continue;
            }
            assertEquals("fromCode round-trip failed for " + status,
                    status, HttpStatus.fromCode(status.code));
        }
    }
}
