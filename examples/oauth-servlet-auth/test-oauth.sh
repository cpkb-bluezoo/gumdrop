#!/bin/bash
#
# OAuth authentication test script
#
# This script demonstrates how to test the OAuth servlet authentication example
# by making HTTP requests with Bearer tokens to various endpoints.
#
# Usage:
#   ./test-oauth.sh [base-url] [bearer-token]
#
# Examples:
#   ./test-oauth.sh http://localhost:8080/oauth-example eyJhbGci...
#   ./test-oauth.sh https://myserver.com/oauth-example $MY_TOKEN
#

set -e

# Configuration
BASE_URL="${1:-http://localhost:8080/oauth-example}"
BEARER_TOKEN="${2}"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Helper functions
print_header() {
    echo -e "\n${BLUE}========================================${NC}"
    echo -e "${BLUE} $1${NC}"
    echo -e "${BLUE}========================================${NC}\n"
}

print_test() {
    echo -e "${YELLOW}Testing: $1${NC}"
}

print_success() {
    echo -e "${GREEN}✓ $1${NC}"
}

print_error() {
    echo -e "${RED}✗ $1${NC}"
}

print_info() {
    echo -e "${BLUE}ℹ $1${NC}"
}

# Test function
test_endpoint() {
    local method="$1"
    local endpoint="$2"
    local description="$3"
    local expected_status="$4"
    local auth_header="$5"
    
    print_test "$description"
    
    local url="$BASE_URL$endpoint"
    local cmd="curl -s -w '%{http_code}' -X $method"
    
    if [ -n "$auth_header" ]; then
        cmd="$cmd -H 'Authorization: $auth_header'"
    fi
    
    cmd="$cmd -H 'Accept: application/json' '$url'"
    
    echo "Request: $method $url"
    if [ -n "$auth_header" ]; then
        echo "Authorization: ${auth_header:0:20}..."
    fi
    
    local response=$(eval $cmd)
    local status_code="${response: -3}"
    local body="${response%???}"
    
    if [ "$status_code" = "$expected_status" ]; then
        print_success "Status: $status_code (expected $expected_status)"
        if [ -n "$body" ]; then
            echo "$body" | python3 -m json.tool 2>/dev/null || echo "$body"
        fi
    else
        print_error "Status: $status_code (expected $expected_status)"
        echo "$body"
        return 1
    fi
    
    echo ""
}

# Main test execution
print_header "OAuth Servlet Authentication Test"

print_info "Base URL: $BASE_URL"
if [ -n "$BEARER_TOKEN" ]; then
    print_info "Using provided Bearer token: ${BEARER_TOKEN:0:20}..."
else
    print_info "No Bearer token provided - testing public and unauthorized endpoints only"
fi

# Test 1: Public health endpoint (no auth required)
print_header "Public Endpoints"
test_endpoint "GET" "/health" "Health check (public)" "200" ""

# Test 2: Public HEAD request
test_endpoint "HEAD" "/health" "Health check HEAD (public)" "200" ""

if [ -z "$BEARER_TOKEN" ]; then
    print_header "Unauthorized Access Tests"
    
    # Test 3: Protected endpoint without token
    test_endpoint "GET" "/api/test" "Protected endpoint without token" "401" ""
    
    # Test 4: Admin endpoint without token
    test_endpoint "GET" "/api/admin/test" "Admin endpoint without token" "401" ""
    
    print_info "To test authenticated endpoints, provide a Bearer token:"
    print_info "  $0 $BASE_URL YOUR_BEARER_TOKEN"
    
else
    print_header "Authenticated Endpoint Tests"
    
    # Test 3: Protected endpoint with token
    test_endpoint "GET" "/api/test" "Protected endpoint with token" "200" "Bearer $BEARER_TOKEN"
    
    # Test 4: POST to protected endpoint
    test_endpoint "POST" "/api/test" "Protected POST with token" "200" "Bearer $BEARER_TOKEN"
    
    # Test 5: PUT to protected endpoint  
    test_endpoint "PUT" "/api/test" "Protected PUT with token" "200" "Bearer $BEARER_TOKEN"
    
    # Test 6: DELETE with user role check
    test_endpoint "DELETE" "/api/test" "Protected DELETE with role check" "200" "Bearer $BEARER_TOKEN"
    
    print_header "Admin Endpoint Tests"
    
    # Test 7: Admin endpoint (may fail if user lacks admin role)
    test_endpoint "GET" "/api/admin/test" "Admin endpoint with token" "200" "Bearer $BEARER_TOKEN" || {
        print_info "Admin access failed - user may not have admin role"
    }
    
    # Test 8: Admin POST
    test_endpoint "POST" "/api/admin/test" "Admin POST" "200" "Bearer $BEARER_TOKEN" || {
        print_info "Admin POST failed - user may not have admin role"  
    }
    
    # Test 9: Admin DELETE with parameter
    test_endpoint "DELETE" "/api/admin/test?id=test-resource-123" "Admin DELETE with parameter" "200" "Bearer $BEARER_TOKEN" || {
        print_info "Admin DELETE failed - user may not have admin role"
    }
fi

print_header "Test Summary"

# Test invalid token format
if [ -n "$BEARER_TOKEN" ]; then
    print_header "Invalid Token Tests"
    test_endpoint "GET" "/api/test" "Invalid token format" "401" "Bearer invalid-token-format"
    test_endpoint "GET" "/api/test" "Malformed Authorization header" "401" "InvalidFormat"
fi

print_success "OAuth authentication tests completed"

print_info "Next steps:"
print_info "  1. Check server logs for authentication details"
print_info "  2. Verify OAuth server configuration if tests fail"
print_info "  3. Test with different tokens to verify role-based access"

# Example OAuth server token request
print_header "OAuth Token Example"
cat << 'EOF'
To get an OAuth token for testing, you can use curl with your OAuth server:

# Example for authorization code flow:
curl -X POST "https://your-oauth-server/oauth/token" \
     -H "Content-Type: application/x-www-form-urlencoded" \
     -d "grant_type=authorization_code" \
     -d "code=YOUR_AUTH_CODE" \
     -d "client_id=YOUR_CLIENT_ID" \
     -d "client_secret=YOUR_CLIENT_SECRET" \
     -d "redirect_uri=YOUR_REDIRECT_URI"

# Example for client credentials flow:
curl -X POST "https://your-oauth-server/oauth/token" \
     -H "Content-Type: application/x-www-form-urlencoded" \
     -d "grant_type=client_credentials" \
     -d "client_id=YOUR_CLIENT_ID" \
     -d "client_secret=YOUR_CLIENT_SECRET" \
     -d "scope=read write admin"

Extract the "access_token" field from the response and use it as the Bearer token.
EOF
