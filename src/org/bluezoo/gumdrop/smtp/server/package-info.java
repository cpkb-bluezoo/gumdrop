/**
 * SMTP server-side facades and session composition SPI.
 *
 * <p>{@link SmtpServer} implements {@link SmtpServerSessionProvider}
 * ({@link org.bluezoo.gumdrop.ServerSessionProvider}). Stateless protocols
 * (HTTP, DNS) compose with request/query handlers instead.
 *
 * @see org.bluezoo.gumdrop.smtp.client.SmtpClientSessionProvider
 * @see docs/COMPOSITION.md
 */
package org.bluezoo.gumdrop.smtp.server;
