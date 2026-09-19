/*
 * AuthoritativeDnsExample.java
 * Example authoritative DNS server using a BIND-style zone file.
 */

import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.GumdropConfig;
import org.bluezoo.gumdrop.dns.DnsListener;
import org.bluezoo.gumdrop.dns.server.AuthoritativeZoneHandler;
import org.bluezoo.gumdrop.dns.server.DnsServer;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Runs an authoritative DNS server for {@code example.com} on UDP port 5353.
 *
 * <p>Try:
 * <pre>{@code
 * dig @127.0.0.1 -p 5353 www.example.com A
 * dig @127.0.0.1 -p 5353 random.example.com A
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class AuthoritativeDnsExample {

    public static void main(String[] args) throws Exception {
        Path zonePath = Paths.get("examples/dns-authoritative/example.com.zone");
        if (args.length > 0) {
            zonePath = Paths.get(args[0]);
        }

        AuthoritativeZoneHandler handler = AuthoritativeZoneHandler.builder()
                .zoneFile(zonePath)
                .build();

        DnsServer dns = DnsServer.compose()
                .listener(new DnsListener().port(5353))
                .handler(handler)
                .server();

        Gumdrop gumdrop = Gumdrop.boot(GumdropConfig.create().workerThreads(1));
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    dns.stop();
                    gumdrop.shutdown();
                    gumdrop.join();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }));

        dns.start(gumdrop);
        System.out.println("Authoritative DNS for example.com on UDP 5353");
        System.out.println("Zone file: " + zonePath.toAbsolutePath());
        System.out.println("Press Ctrl+C to stop.");
        Thread.currentThread().join();
    }
}
