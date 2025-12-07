# POP3 Server Example

This example demonstrates how to set up and run a POP3 server using the gumdrop framework.

## Features Demonstrated

- Filesystem-based mailbox storage
- Simple in-memory authentication realm
- Both POP3 (port 110) and POP3S (port 995) servers
- Sample message creation
- APOP authentication support
- UTF-8 mode support

## Building

Compile the example along with gumdrop:

```bash
cd /path/to/gumdrop
ant compile
javac -cp build examples/pop3-server/POP3Example.java
```

## Running

```bash
java -cp build:examples/pop3-server POP3Example
```

Note: For POP3S (port 995), you'll need to configure a keystore with a valid certificate.

## Testing with Telnet

### Basic Authentication

```
$ telnet localhost 110
+OK POP3 server ready
USER alice
+OK User accepted
PASS password123
+OK Mailbox opened
STAT
+OK 2 512
LIST
+OK 2 messages
1 256
2 256
.
RETR 1
+OK 256 octets
[message content]
.
QUIT
+OK Mailbox updated and closed
```

### APOP Authentication

```
$ telnet localhost 110
+OK POP3 server ready <12345.67890@hostname>
APOP alice [md5-digest-here]
+OK Mailbox opened
STAT
+OK 2 512
QUIT
+OK Mailbox updated and closed
```

### Capabilities

```
$ telnet localhost 110
+OK POP3 server ready
CAPA
+OK Capability list follows
USER
UIDL
TOP
APOP
UTF8
IMPLEMENTATION gumdrop
.
QUIT
+OK POP3 server signing off
```

## Test Users

- **Username:** alice, **Password:** password123
- **Username:** bob, **Password:** secret456

## Mailbox Directory Structure

```
mailboxes/
  alice/
    1.eml
    2.eml
  bob/
    1.eml
```

## Configuration for Production

For production use, you should:

1. Use a proper realm (LDAP, database, etc.)
2. Configure TLS with valid certificates
3. Implement a more robust mailbox backend
4. Set appropriate timeouts and limits
5. Enable security features (login delay, rate limiting)

Example production configuration:

```java
POP3Server server = new POP3Server();
server.setPort(995);
server.setSecure(true);
server.setRealm(ldapRealm);
server.setMailboxFactory(databaseMailboxFactory);
server.setKeystoreFile("/etc/ssl/certs/mailserver.p12");
server.setKeystorePass(System.getenv("KEYSTORE_PASSWORD"));
server.setLoginDelay(5000); // 5 seconds after failed auth
server.setTransactionTimeout(600000); // 10 minutes
server.setEnableAPOP(true);
server.setEnableUTF8(true);
```

## Common POP3 Clients

This server works with standard POP3 clients:

- **Thunderbird** - Configure account with POP3 on port 110
- **Outlook** - Add email account with POP3 settings
- **Mail.app (macOS)** - Add account, select POP
- **fetchmail** - Command-line POP3 retrieval
- **mutt** - Terminal-based email client

## Troubleshooting

### Connection Refused

- Check that the server is running
- Verify the port is not blocked by firewall
- Ensure no other service is using the port

### Authentication Failed

- Verify username and password are correct
- Check realm configuration
- Review login delay settings

### TLS Errors

- Ensure keystore file exists and is readable
- Verify keystore password is correct
- Check certificate validity

### Mailbox Locked

- Another POP3 session may have the mailbox open
- Check for stale lock files in mailbox directory
- Restart the server to release locks

## RFC Compliance

This example server implements:

- **RFC 1939** - POP3 protocol
- **RFC 2449** - POP3 Extension Mechanism (CAPA)
- **RFC 2595** - TLS for POP3 (STLS)
- **RFC 6816** - UTF-8 support
- **RFC 8314** - Use of TLS for email access

