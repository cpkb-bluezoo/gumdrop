import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.handler.codec.http2.Http2StreamChannel;
import io.netty.handler.codec.http2.Http2StreamFrame;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.ApplicationProtocolNegotiationHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import io.netty.util.CharsetUtil;


import java.io.FileInputStream;
import java.security.KeyStore;
import java.util.HashMap;
import java.util.Map;
import javax.net.ssl.KeyManagerFactory;

/**
 * Minimal raw Netty HTTP server, structurally equivalent to
 * GumdropBenchServer: same three modes (plaintext / json / tls), same
 * response bodies, same hand-rolled JSON parsing (BenchJson, copy-pasted
 * verbatim from the Gumdrop side so neither side's JSON library performance
 * confounds the comparison).
 *
 * "tls" mode negotiates HTTP/2 via ALPN ("h2" then "http/1.1" fallback),
 * mirroring Gumdrop's HTTPListener which does the same automatically
 * whenever secure=true - so the same TLS port answers both HTTP/1.1 and
 * HTTP/2 on both servers, and the load client picks the version per run.
 */
public class NettyBenchServer {

    static final byte[] HELLO = "Hello, World!".getBytes(CharsetUtil.UTF_8);

    public static void main(String[] args) throws Exception {
        Map<String, String> opt = parseArgs(args);
        String mode = opt.getOrDefault("mode", "plaintext");
        int port = Integer.parseInt(opt.getOrDefault("port", "8080"));

        SslContext sslCtx = null;
        if ("tls".equals(mode)) {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            try (FileInputStream in = new FileInputStream(req(opt, "keystore"))) {
                ks.load(in, req(opt, "keystore-pass").toCharArray());
            }
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, req(opt, "keystore-pass").toCharArray());
            sslCtx = SslContextBuilder.forServer(kmf).sslProvider(SslProvider.JDK)
                    .applicationProtocolConfig(new ApplicationProtocolConfig(
                            ApplicationProtocolConfig.Protocol.ALPN,
                            ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
                            ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
                            ApplicationProtocolNames.HTTP_2, ApplicationProtocolNames.HTTP_1_1))
                    .build();
        }
        final SslContext finalSslCtx = sslCtx;

        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup();
        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
             .channel(NioServerSocketChannel.class)
             .option(ChannelOption.SO_BACKLOG, 1024)
             .childOption(ChannelOption.TCP_NODELAY, true)
             .childHandler(new ChannelInitializer<SocketChannel>() {
                 @Override
                 protected void initChannel(SocketChannel ch) {
                     if (finalSslCtx != null) {
                         ch.pipeline().addLast(finalSslCtx.newHandler(ch.alloc()));
                         // ALPN result decides HTTP/1.1 vs HTTP/2 pipeline, same
                         // as Gumdrop's HTTPListener does automatically.
                         ch.pipeline().addLast(new ApplicationProtocolNegotiationHandler(ApplicationProtocolNames.HTTP_1_1) {
                             @Override
                             protected void configurePipeline(ChannelHandlerContext ctx, String protocol) {
                                 if (ApplicationProtocolNames.HTTP_2.equals(protocol)) {
                                     ctx.pipeline().addLast(Http2FrameCodecBuilder.forServer().build());
                                     ctx.pipeline().addLast(new Http2MultiplexHandler(new Http2StreamInitializer(mode)));
                                 } else {
                                     ctx.pipeline().addLast(new HttpServerCodec());
                                     ctx.pipeline().addLast(new HttpObjectAggregator(1 << 20));
                                     ctx.pipeline().addLast("json".equals(mode) ? new JsonHandler() : new PlaintextHandler());
                                 }
                             }
                         });
                     } else {
                         ch.pipeline().addLast(new HttpServerCodec());
                         ch.pipeline().addLast(new HttpObjectAggregator(1 << 20));
                         ch.pipeline().addLast("json".equals(mode) ? new JsonHandler() : new PlaintextHandler());
                     }
                 }
             });

            ChannelFuture f = b.bind(port).sync();
            System.out.println("NettyBenchServer mode=" + mode + " port=" + port + " ready");
            f.channel().closeFuture().sync();
        } finally {
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }

    static class PlaintextHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) {
            ByteBuf content = Unpooled.wrappedBuffer(HELLO);
            respond(ctx, req, content, "text/plain");
        }
    }

    static class JsonHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) {
            BenchJson.Extractor extractor = new BenchJson.Extractor();
            // nioBuffers() gives zero-copy ByteBuffer views over the ByteBuf's
            // backing memory - no byte[] copy, matching the Gumdrop side.
            for (java.nio.ByteBuffer nioBuf : req.content().nioBuffers()) {
                extractor.receive(nioBuf);
            }
            long id = extractor.finish();
            byte[] json = BenchJson.buildResponse(id);
            ByteBuf content = Unpooled.wrappedBuffer(json);
            respond(ctx, req, content, "application/json");
        }
    }

    static class Http2StreamInitializer extends ChannelInitializer<Http2StreamChannel> {
        private final String mode;

        Http2StreamInitializer(String mode) {
            this.mode = mode;
        }

        @Override
        protected void initChannel(Http2StreamChannel ch) {
            ch.pipeline().addLast("json".equals(mode) ? new Http2JsonHandler() : new Http2PlaintextHandler());
        }
    }

    static class Http2PlaintextHandler extends SimpleChannelInboundHandler<Http2HeadersFrame> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, Http2HeadersFrame frame) {
            if (frame.isEndStream()) {
                h2Respond(ctx, Unpooled.wrappedBuffer(HELLO), "text/plain");
            }
        }
    }

    static class Http2JsonHandler extends SimpleChannelInboundHandler<Http2StreamFrame> {
        private final BenchJson.Extractor extractor = new BenchJson.Extractor();

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, Http2StreamFrame frame) {
            if (frame instanceof Http2DataFrame) {
                Http2DataFrame data = (Http2DataFrame) frame;
                for (java.nio.ByteBuffer nioBuf : data.content().nioBuffers()) {
                    extractor.receive(nioBuf);
                }
                if (data.isEndStream()) {
                    long id = extractor.finish();
                    byte[] json = BenchJson.buildResponse(id);
                    h2Respond(ctx, Unpooled.wrappedBuffer(json), "application/json");
                }
            }
        }
    }

    static void h2Respond(ChannelHandlerContext ctx, ByteBuf content, String contentType) {
        Http2Headers headers = new DefaultHttp2Headers();
        headers.status("200");
        headers.set(HttpHeaderNames.CONTENT_TYPE, contentType);
        ctx.write(new DefaultHttp2HeadersFrame(headers));
        ctx.writeAndFlush(new DefaultHttp2DataFrame(content, true));
    }

    static void respond(ChannelHandlerContext ctx, FullHttpRequest req, ByteBuf content, String contentType) {
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.OK, content);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType);
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes());
        boolean keepAlive = HttpUtil.isKeepAlive(req);
        if (keepAlive) {
            response.headers().set(HttpHeaderNames.CONNECTION, "keep-alive");
        }
        ChannelFuture f = ctx.writeAndFlush(response);
        if (!keepAlive) {
            f.addListener(ChannelFutureListener.CLOSE);
        }
    }

    static Map<String, String> parseArgs(String[] args) {
        Map<String, String> m = new HashMap<>();
        for (String a : args) {
            if (a.startsWith("--")) {
                String kv = a.substring(2);
                int eq = kv.indexOf('=');
                if (eq >= 0) m.put(kv.substring(0, eq), kv.substring(eq + 1));
                else m.put(kv, "true");
            }
        }
        return m;
    }

    static String req(Map<String, String> opt, String key) {
        String v = opt.get(key);
        if (v == null) throw new IllegalArgumentException("missing --" + key);
        return v;
    }
}
