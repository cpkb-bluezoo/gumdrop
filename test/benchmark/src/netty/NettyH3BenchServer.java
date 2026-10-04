import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.incubator.codec.http3.DefaultHttp3DataFrame;
import io.netty.incubator.codec.http3.DefaultHttp3HeadersFrame;
import io.netty.incubator.codec.http3.Http3;
import io.netty.incubator.codec.http3.Http3DataFrame;
import io.netty.incubator.codec.http3.Http3HeadersFrame;
import io.netty.incubator.codec.http3.Http3RequestStreamInboundHandler;
import io.netty.incubator.codec.http3.Http3ServerConnectionHandler;
import io.netty.incubator.codec.quic.InsecureQuicTokenHandler;
import io.netty.incubator.codec.quic.QuicChannel;
import io.netty.incubator.codec.quic.QuicSslContext;
import io.netty.incubator.codec.quic.QuicSslContextBuilder;
import io.netty.incubator.codec.quic.QuicStreamChannel;
import io.netty.util.CharsetUtil;
import io.netty.util.ReferenceCountUtil;

import java.io.File;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Minimal raw Netty HTTP/3 server: the counterpart of GumdropBenchServer's
 * h3 mode. Netty's HTTP/3 is its incubator codec over a QUIC transport that
 * is not written in Java: netty-incubator-codec-native-quic wraps Cloudflare's
 * quiche (Rust) and BoringSSL through JNI, and the jar for a platform carries
 * that native library prebuilt. So this compares Gumdrop's pure Java QUIC and
 * TLS with native code doing the same work.
 *
 * It is a separate class from NettyBenchServer because it needs the incubator
 * jars, which the other scenarios do not.
 */
public class NettyH3BenchServer {

    static final byte[] HELLO = "Hello, World!".getBytes(CharsetUtil.UTF_8);

    public static void main(String[] args) throws Exception {
        Map<String, String> opt = NettyBenchServer.parseArgs(args);
        int port = Integer.parseInt(opt.getOrDefault("port", "8443"));
        String certFile = NettyBenchServer.req(opt, "cert");
        String keyFile = NettyBenchServer.req(opt, "key");
        int threads = Integer.parseInt(opt.getOrDefault("threads", "1"));

        NioEventLoopGroup group = new NioEventLoopGroup(threads);
        QuicSslContext sslContext = QuicSslContextBuilder.forServer(
                        new File(keyFile), null, new File(certFile))
                .applicationProtocols(Http3.supportedApplicationProtocols()).build();
        ChannelHandler codec = Http3.newQuicServerCodecBuilder()
                .sslContext(sslContext)
                .maxIdleTimeout(30000, TimeUnit.MILLISECONDS)
                .initialMaxData(10000000)
                .initialMaxStreamDataBidirectionalLocal(1000000)
                .initialMaxStreamDataBidirectionalRemote(1000000)
                .initialMaxStreamsBidirectional(100)
                .tokenHandler(InsecureQuicTokenHandler.INSTANCE)
                .handler(new ChannelInitializer<QuicChannel>() {
                    @Override
                    protected void initChannel(QuicChannel ch) {
                        ch.pipeline().addLast(new Http3ServerConnectionHandler(
                                new ChannelInitializer<QuicStreamChannel>() {
                                    @Override
                                    protected void initChannel(QuicStreamChannel ch) {
                                        ch.pipeline().addLast(new PlaintextHandler());
                                    }
                                }));
                    }
                }).build();
        Bootstrap bs = new Bootstrap();
        Channel channel = bs.group(group)
                .channel(NioDatagramChannel.class)
                .handler(codec)
                .bind(new InetSocketAddress(port)).sync().channel();
        System.out.println("NettyH3BenchServer port=" + port + " ready");
        channel.closeFuture().sync();
        group.shutdownGracefully();
    }

    static class PlaintextHandler extends Http3RequestStreamInboundHandler {
        @Override
        protected void channelRead(ChannelHandlerContext ctx, Http3HeadersFrame frame) {
            ReferenceCountUtil.release(frame);
        }

        @Override
        protected void channelRead(ChannelHandlerContext ctx, Http3DataFrame frame) {
            ReferenceCountUtil.release(frame);
        }

        @Override
        protected void channelInputClosed(ChannelHandlerContext ctx) {
            Http3HeadersFrame headersFrame = new DefaultHttp3HeadersFrame();
            headersFrame.headers().status("200");
            headersFrame.headers().add("content-type", "text/plain");
            ctx.write(headersFrame);
            ctx.writeAndFlush(new DefaultHttp3DataFrame(Unpooled.wrappedBuffer(HELLO)))
                    .addListener(QuicStreamChannel.SHUTDOWN_OUTPUT);
        }
    }
}
