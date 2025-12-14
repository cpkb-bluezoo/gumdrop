# Integration Test Framework

This directory contains the integration test framework for Gumdrop, providing
end-to-end testing of server functionality with real network connections.

## Quick Start

```bash
# Build and set up test fixtures (certificates, directories)
ant integration-setup

# Run all integration tests
ant integration-test

# Run all tests with full reporting
ant integration-test-full

# Run specific protocol tests
ant integration-test-http
ant integration-test-smtp
ant integration-test-pop3
ant integration-test-imap
ant integration-test-ftp
ant integration-test-servlet
ant integration-test-telemetry
ant integration-test-buffer

# Generate HTML report from XML results
ant integration-report

# Clean up test artifacts
ant integration-clean
```

## Directory Structure

```
test/integration/
├── certs/                      # TLS certificates for testing
│   ├── test-keystore.p12       # Server keystore
│   ├── test-truststore.p12     # Trust store with CA
│   ├── client-testuser.p12     # Client cert for auth testing
│   └── client-admin.p12        # Admin client cert
├── config/                     # Server configuration files
│   ├── http-server-test.xml
│   ├── https-server-test.xml
│   ├── smtp-server-test.xml
│   └── ...
├── mailbox/                    # Test mailbox data
├── results/                    # Test output (generated)
│   ├── xml/                    # JUnit XML reports (CI/CD)
│   ├── html/                   # HTML reports
│   ├── diagnostics/            # Debug logs
│   └── reports/                # JUnit report data
└── src/                        # Test source code
    └── org/bluezoo/gumdrop/
        ├── AbstractServerIntegrationTest.java
        ├── IntegrationTestContext.java
        ├── IntegrationTestRule.java
        ├── TestCertificateManager.java
        ├── TestFixtureSetup.java
        ├── http/
        ├── smtp/
        ├── pop3/
        ├── imap/
        └── ...
```

## Key Components

### AbstractServerIntegrationTest

Base class for all server integration tests. Provides:
- Automatic server lifecycle management (start/stop)
- Configuration file loading
- Port availability checking
- Test diagnostics integration

```java
public class MyServerTest extends AbstractServerIntegrationTest {
    @Override
    protected File getTestConfigFile() {
        return new File("test/integration/config/my-server-test.xml");
    }
    
    @Test
    public void testSomething() {
        // Server is already running
        assertTrue(isPortListening("127.0.0.1", 8080));
    }
}
```

### IntegrationTestContext

Singleton providing shared test utilities:
- **Port Allocation**: Dynamic port assignment to avoid conflicts
- **Event Logging**: Detailed event capture for debugging
- **Test Timing**: Performance measurement
- **Environment Validation**: Pre-flight checks

```java
IntegrationTestContext ctx = IntegrationTestContext.getInstance();

// Allocate unique port
int port = ctx.allocatePort("http-server");

// Log test events
ctx.logEvent("TEST", "Starting connection test");

// Write diagnostics on failure
ctx.writeDiagnostics("test-failure-report.txt");
```

### IntegrationTestRule

JUnit Rule for enhanced test diagnostics:
- Records test start/end times
- Captures test outcomes with context
- Provides failure diagnostics

```java
public class MyTest {
    @Rule
    public IntegrationTestRule testRule = new IntegrationTestRule();
    
    @Test
    public void myTest() {
        testRule.checkpoint("after-setup");
        // test code
        testRule.log("custom message");
    }
}
```

### TestCertificateManager

Manages TLS certificates for testing:
- Generates self-signed CA certificates
- Creates server certificates
- Creates client certificates for authentication testing
- Produces PKCS12 keystores

```java
TestCertificateManager mgr = new TestCertificateManager(certsDir);
mgr.generateCA("Test CA", 365);
mgr.generateServerCertificate("localhost", 365);
mgr.saveServerKeystore(new File(certsDir, "server.p12"), "password");

// Client certificate for auth testing
ClientCertificate client = mgr.generateClientCertificate(
    "user@example.com", "Test User", 365);
SSLContext ctx = client.createSSLContext("password");
```

## Test Output

### XML Reports (CI/CD)

JUnit XML reports are generated in `test/integration/results/xml/`:
```
TEST-org.bluezoo.gumdrop.http.HTTPServerIntegrationTest.xml
TEST-org.bluezoo.gumdrop.smtp.SMTPServerIntegrationTest.xml
...
```

These can be consumed by CI/CD systems (Jenkins, GitHub Actions, etc.).

### HTML Reports

Run `ant integration-report` to generate HTML reports in 
`test/integration/results/html/index.html`.

### Diagnostics

Debug logs are written to `test/integration/results/diagnostics/`:
- Event timelines
- Port allocations
- Failure context
- System state at failure

## Writing New Tests

1. Create a new test class extending `AbstractServerIntegrationTest`
2. Create a configuration file in `test/integration/config/`
3. Implement `getTestConfigFile()` to return your config
4. Write test methods using JUnit assertions

```java
public class FTPServerIntegrationTest extends AbstractServerIntegrationTest {
    
    @Override
    protected File getTestConfigFile() {
        return new File("test/integration/config/ftp-server-test.xml");
    }
    
    @Override
    protected long getStartupTimeout() {
        return 10000; // 10 seconds for slow startup
    }
    
    @Test
    public void testFTPLogin() throws Exception {
        log("Testing FTP login");
        // Connect to server on configured port
        // Perform login
        // Verify response
    }
}
```

## Troubleshooting

### Port Conflicts

If tests fail with "Address already in use":
1. Check for zombie processes: `lsof -i :PORT`
2. Wait for TIME_WAIT to expire (usually 60-120 seconds)
3. Use dynamic port allocation via `IntegrationTestContext.allocatePort()`

### Certificate Issues

If TLS tests fail:
1. Run `ant integration-setup` to regenerate certificates
2. Check certificate validity (30-day refresh)
3. Verify keystore password matches config (default: `testpass`)

### Timeout Failures

If tests timeout waiting for server:
1. Check server logs for startup errors
2. Increase timeout via `getStartupTimeout()`
3. Check for port conflicts preventing binding

### Viewing Diagnostics

After test failure:
1. Check `test/integration/results/diagnostics/` for event logs
2. Check `test/integration/results/xml/` for detailed failure info
3. Run `ant integration-report` and view HTML report

## Environment Requirements

- Java 8 or higher
- Available ports in range 30000-40000 (for dynamic allocation)
- Write access to `test/integration/` directory
- Network loopback interface (127.0.0.1) available

## CI/CD Integration

The test framework produces JUnit XML output suitable for CI/CD:

```yaml
# GitHub Actions example
- name: Run Integration Tests
  run: ant integration-test-full
  
- name: Upload Test Results
  uses: actions/upload-artifact@v3
  with:
    name: integration-test-results
    path: test/integration/results/
    
- name: Publish Test Report
  uses: mikepenz/action-junit-report@v3
  with:
    report_paths: 'test/integration/results/xml/TEST-*.xml'
```

## Best Practices

1. **Isolation**: Each test should be independent and not rely on state from other tests
2. **Cleanup**: Use `@After` methods to clean up resources
3. **Timeouts**: Use JUnit `@Rule Timeout` to prevent hung tests
4. **Logging**: Use `testRule.log()` and `testRule.checkpoint()` for debugging
5. **Assertions**: Use `assertWithDiagnostics()` for enhanced failure context
6. **Ports**: Use dynamic port allocation for parallel test execution

