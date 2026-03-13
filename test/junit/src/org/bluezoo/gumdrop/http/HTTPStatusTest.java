package org.bluezoo.gumdrop.http;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link HTTPStatus}.
 */
public class HTTPStatusTest {

    @Test
    public void testInformationalCategory() {
        assertTrue(HTTPStatus.CONTINUE.isInformational());
        assertTrue(HTTPStatus.SWITCHING_PROTOCOLS.isInformational());
        assertTrue(HTTPStatus.PROCESSING.isInformational());
        assertTrue(HTTPStatus.EARLY_HINTS.isInformational());

        assertFalse(HTTPStatus.OK.isInformational());
        assertFalse(HTTPStatus.BAD_REQUEST.isInformational());
    }

    @Test
    public void testSuccessCategory() {
        assertTrue(HTTPStatus.OK.isSuccess());
        assertTrue(HTTPStatus.CREATED.isSuccess());
        assertTrue(HTTPStatus.NO_CONTENT.isSuccess());
        assertTrue(HTTPStatus.PARTIAL_CONTENT.isSuccess());
        assertTrue(HTTPStatus.MULTI_STATUS.isSuccess());
        assertTrue(HTTPStatus.IM_USED.isSuccess());

        assertFalse(HTTPStatus.CONTINUE.isSuccess());
        assertFalse(HTTPStatus.MOVED_PERMANENTLY.isSuccess());
    }

    @Test
    public void testRedirectionCategory() {
        assertTrue(HTTPStatus.MULTIPLE_CHOICES.isRedirection());
        assertTrue(HTTPStatus.MOVED_PERMANENTLY.isRedirection());
        assertTrue(HTTPStatus.FOUND.isRedirection());
        assertTrue(HTTPStatus.NOT_MODIFIED.isRedirection());
        assertTrue(HTTPStatus.TEMPORARY_REDIRECT.isRedirection());
        assertTrue(HTTPStatus.PERMANENT_REDIRECT.isRedirection());

        assertFalse(HTTPStatus.OK.isRedirection());
        assertFalse(HTTPStatus.BAD_REQUEST.isRedirection());
    }

    @Test
    public void testClientErrorCategory() {
        assertTrue(HTTPStatus.BAD_REQUEST.isClientError());
        assertTrue(HTTPStatus.UNAUTHORIZED.isClientError());
        assertTrue(HTTPStatus.FORBIDDEN.isClientError());
        assertTrue(HTTPStatus.NOT_FOUND.isClientError());
        assertTrue(HTTPStatus.METHOD_NOT_ALLOWED.isClientError());
        assertTrue(HTTPStatus.TOO_MANY_REQUESTS.isClientError());
        assertTrue(HTTPStatus.UNAVAILABLE_FOR_LEGAL_REASONS.isClientError());

        assertFalse(HTTPStatus.OK.isClientError());
        assertFalse(HTTPStatus.INTERNAL_SERVER_ERROR.isClientError());
    }

    @Test
    public void testServerErrorCategory() {
        assertTrue(HTTPStatus.INTERNAL_SERVER_ERROR.isServerError());
        assertTrue(HTTPStatus.NOT_IMPLEMENTED.isServerError());
        assertTrue(HTTPStatus.BAD_GATEWAY.isServerError());
        assertTrue(HTTPStatus.SERVICE_UNAVAILABLE.isServerError());
        assertTrue(HTTPStatus.GATEWAY_TIMEOUT.isServerError());
        assertTrue(HTTPStatus.NETWORK_AUTHENTICATION_REQUIRED.isServerError());

        assertFalse(HTTPStatus.OK.isServerError());
        assertFalse(HTTPStatus.BAD_REQUEST.isServerError());
    }

    @Test
    public void testIsError() {
        assertTrue(HTTPStatus.BAD_REQUEST.isError());
        assertTrue(HTTPStatus.NOT_FOUND.isError());
        assertTrue(HTTPStatus.INTERNAL_SERVER_ERROR.isError());
        assertTrue(HTTPStatus.SERVICE_UNAVAILABLE.isError());

        assertFalse(HTTPStatus.OK.isError());
        assertFalse(HTTPStatus.CONTINUE.isError());
        assertFalse(HTTPStatus.MOVED_PERMANENTLY.isError());
    }

    @Test
    public void testPseudoStatus() {
        assertTrue(HTTPStatus.REDIRECT_LOOP.isPseudoStatus());
        assertTrue(HTTPStatus.UNKNOWN.isPseudoStatus());

        assertFalse(HTTPStatus.OK.isPseudoStatus());
        assertFalse(HTTPStatus.INTERNAL_SERVER_ERROR.isPseudoStatus());
        assertFalse(HTTPStatus.CONTINUE.isPseudoStatus());
    }

    @Test
    public void testPseudoStatusNotInCategories() {
        assertFalse(HTTPStatus.REDIRECT_LOOP.isInformational());
        assertFalse(HTTPStatus.REDIRECT_LOOP.isSuccess());
        assertFalse(HTTPStatus.REDIRECT_LOOP.isRedirection());
        assertFalse(HTTPStatus.REDIRECT_LOOP.isClientError());
        assertFalse(HTTPStatus.REDIRECT_LOOP.isServerError());
        assertFalse(HTTPStatus.REDIRECT_LOOP.isError());
    }

    @Test
    public void testFromCodeKnownStatuses() {
        assertEquals(HTTPStatus.OK, HTTPStatus.fromCode(200));
        assertEquals(HTTPStatus.NOT_FOUND, HTTPStatus.fromCode(404));
        assertEquals(HTTPStatus.INTERNAL_SERVER_ERROR, HTTPStatus.fromCode(500));
        assertEquals(HTTPStatus.CONTINUE, HTTPStatus.fromCode(100));
        assertEquals(HTTPStatus.MOVED_PERMANENTLY, HTTPStatus.fromCode(301));
        assertEquals(HTTPStatus.IM_A_TEAPOT, HTTPStatus.fromCode(418));
    }

    @Test
    public void testFromCodeUnrecognized() {
        assertEquals(HTTPStatus.INTERNAL_SERVER_ERROR, HTTPStatus.fromCode(999));
        assertEquals(HTTPStatus.INTERNAL_SERVER_ERROR, HTTPStatus.fromCode(0));
        assertEquals(HTTPStatus.INTERNAL_SERVER_ERROR, HTTPStatus.fromCode(600));
    }

    @Test
    public void testStatusCodes() {
        assertEquals(100, HTTPStatus.CONTINUE.code);
        assertEquals(200, HTTPStatus.OK.code);
        assertEquals(301, HTTPStatus.MOVED_PERMANENTLY.code);
        assertEquals(400, HTTPStatus.BAD_REQUEST.code);
        assertEquals(404, HTTPStatus.NOT_FOUND.code);
        assertEquals(500, HTTPStatus.INTERNAL_SERVER_ERROR.code);
        assertEquals(-1, HTTPStatus.REDIRECT_LOOP.code);
        assertEquals(-2, HTTPStatus.UNKNOWN.code);
    }

    @Test
    public void testCategoriesMutuallyExclusive() {
        for (HTTPStatus status : HTTPStatus.values()) {
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
        for (HTTPStatus status : HTTPStatus.values()) {
            if (status.isPseudoStatus()) {
                continue;
            }
            assertEquals("isError() should equal isClientError() || isServerError() for " + status,
                    status.isClientError() || status.isServerError(), status.isError());
        }
    }

    @Test
    public void testFromCodeRoundTrip() {
        for (HTTPStatus status : HTTPStatus.values()) {
            if (status.isPseudoStatus()) {
                continue;
            }
            assertEquals("fromCode round-trip failed for " + status,
                    status, HTTPStatus.fromCode(status.code));
        }
    }
}
