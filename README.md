# gumdrop
Multipurpose asynchronous Java server and servlet container

This is gumdrop, a multipurpose Java server using asynchronous, event-driven
I/O. It supports HTTP and HTTPS. HTTP/2 support and FTP are under development.

This software is distributed under the GNU General Public Licence; see the
file COPYING for details.

The gumdroprc file is used to configure gumdrop.

The gumdrop server provides a complete 2.4 servlet container. An example
web application and configuration is provided in the web directory. There
is also a Manager web application that can be used to administer the
container. Complete support for later version of the servlet specification
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


-- Chris Burdess, June 2005
