# gumdrop
Multipurpose asynchronous Java server and servlet container

![gumdrop logo](https://www.nongnu.org/gumdrop/gumdrop.png "gumdrop logo")

This is gumdrop, a multipurpose Java server using asynchronous, event-driven
I/O. It supports:
- a generic, extensible server framework that can transparently handle TLS
  connections from clients
    - keystore/truststore configuration
    - client certificates
    - SSL protocols (TLS 1.2, 1.3)
    - cipher suite selection
    - thread pool configuration
    - internationalization and localization facilities
    - centralized and secure realm interface for authentication and
      authorization
    - connection filtering
- HTTP
    - versions 1.0 and 1.1
        - Chunked encoding
    - HTTP/2
        - all HTTP/2 frame types
        - HPACK compression
    - HTTPS with client certificate authentication, custom SSL parameters
      and cipher suites
    - HTTP Digest Authentication
    - simple file-based HTTP server
    - complete, conformant Java servlet container
        - servlet 4.0 implementation
        - hot deployment
        - filter chains
        - session management and clustering/replication facilities
        - complete multipart/form-data handling
        - annotation-driven configuration and web fragments
        - programmatic registration of web descriptors
        - asynchronous processing
        - enterprise DataSource and MailSession handling
        - secure classloader separation
        - enterprise JNDI integration
- SMTP
    - SMTPS
    - STARTTLS support
    - SMTP AUTH with LOGIN and PLAIN authentication
    - 8-bit clean message transport
    - memory efficient processing of large messages
    - attack prevention features
    - persistent connections
    - transaction reset
    - connection filtering policy settings for MTA mode or message submission
        - rate limiting
        - network block lists
        - max connections per IP
        - require authentication
- FTP
    - FTPS
    - pluggable realm authentication via standardized mechanism
    - extensible, customizable virtual filesystem
        - local filesystem implementation provided with secure chroot, cross
          platform, configurable read/write permissions
        - extensible for cloud/database resource access
        - uses high performance NIO channels for data transfer
    - simple application handler, abstracted away from protocol details
    - supports binary and ASCII transfer modes
    - passive and active transfer modes
    - resume and append support
    - allows abort to cancel in-progress transfers
    

This software is dual-licensed. See the LICENSING.md file for complete details.

The gumdroprc file is used to configure gumdrop.

The gumdrop server provides a functional J2EE servlet container. An example
web application and configuration is provided in the web directory. There
is also a Manager web application that can be used to administer the
container. Complete support for version 4.0 of the servlet specification
is under development.

The gumdrop logo is a gumdrop torus, generated using [POV-Ray](http://www.povray.org/).
A gumdrop torus is a [mathematical construct](http://www.povray.org/documentation/view/3.6.1/448/#s02_07_07_02_i75)
 - the gumdrop logo is such a torus viewed from an angle that makes it resemble
the letter G. All images were created using POV-Ray and/or Gimp and are
copyright 2005 Chris Burdess.

## Architecture

The server is configured with a number of connectors, which are bound to
ports. It then sits in a `select` loop waiting for socket data.
When an _accept_ request is received, it uses the corresponding
connector as a factory to create a new connection object to handle that IP
connection. Subsequent _read_ requests are demultiplexed to a thread
pool specific to the connector. Servers based on an asynchronous, or
reactor, model can thus be developed quite quickly in response to incoming
data. You implement the `receive` method to react to new data coming in, and
call the `send` method to send out your own responses.

Each connector manages a pool of threads to service the connection-specific
parsing of requests and responses. The size of the thread pool can be
configured and is always independent of the main server I/O processing loop.

There is an example file-based HTTP connector included in the project, you
can just point it at a directory.

The servlet container is a slightly special case because servlets use an
InputStream to read request body data instead of having it being delivered
them in an event-based fashion. So the servlet connector additionally manages
a pool of worker threads which are able to read from request body data
delivered via a pipe. Thus, servlets can either block reading the request
body, or use servlet 3.1 asynchronous methods via ReadListener to be
informed when the data becomes available. Some work has been done on servlet
context cluster synchronisation and hot deployment (these need updating for
newer NIO APIs).

The server framework transparently supports TLS connections. All you have
to do is set the `secure` connector property to `true`,
and communication with the client will be encrypted. You specify the
keystore to use for your server certificate and can configure whether to
require client certificates. Otherwise, if you develop a new protocol
connector you can just deal with the decrypted application data. SSL
encryption and decryption occurs in yet another thread, which can spawn
dependent delegated task threads as required by the SSL state engine for
each connection.

## Configuration

The configuration of gumdrop is primarily contained in the
`gumdroprc` file. The remainder of the configuration is supplied
by standard Java system properties, e.g. the logging subsystem which uses
the `java.util.logging` package. There are no external dependencies beyond
J2EE.

## Installation

You can currently use `ant` to build the project, and run the server from
the current directory using `./start gumdroprc`

You should then be able to point a browser at
[http://localhost:8080/](http://localhost:8080/) or
[https://localhost:8443/](https://localhost:8443/) to see the example web
application included, or configure `gumdroprc` to serve your own web
application.

## Ongoing work

The list of current tasks is in the TODO file in the project.

## Licensing

Gumdrop is dual-licensed to provide maximum flexibility:

### Open Source License (GPL v3)
Free for open source projects and GPL-compatible applications. 
See `LICENCE-GPL3` for full terms.

### Commercial License
Available for proprietary and commercial use without GPL obligations.
Contact Chris Burdess <dog@gnu.org> for commercial licensing.

**Special Note**: Mimecast Services Limited has been granted commercial
usage rights under a separate license agreement.

For complete licensing information, see [LICENSING.md](LICENSING.md).

-- Chris Burdess
