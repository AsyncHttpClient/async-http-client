package org.asynchttpclient.reactivestreams;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.HttpResponseBodyPart;
import org.asynchttpclient.handler.StreamedAsyncHandler;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.reactivestreams.example.unicast.AsyncIterablePublisher;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Ignore;
import org.testng.annotations.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.config;
import static org.asynchttpclient.test.TestUtils.LARGE_IMAGE_BYTES;

public class ReactiveStreamsHandlerDuplicateTest {
    private NioEventLoopGroup bossGroup;
    private NioEventLoopGroup eventLoop;
    private ChannelFuture channelFuture;

    public static class CustomHttpServerHandler extends SimpleChannelInboundHandler<HttpObject> {
        private static final ConcurrentHashMap<String, Object> channels = new ConcurrentHashMap<String, Object>();
        private HttpRequest request;
        //language=JSON
        private static final byte[] response = ("{\n" +
                                                "  \"field1\": " +
                                                "\"value1\",\n" +
                                                "  \"field2\": \"value2\",\n" +
                                                "  \"field3\": \"value3\"\n" +
                                                "}").getBytes(StandardCharsets.UTF_8);


        protected CustomHttpServerHandler() {
            super(HttpObject.class);
        }

        @Override
        public void channelReadComplete(ChannelHandlerContext ctx) {
            ctx.flush();
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, HttpObject msg) {
            if (msg instanceof HttpRequest) {
                request = (HttpRequest) msg;
                ByteBuf byteBuf = Unpooled.wrappedBuffer(response);
                ReadOnlyHttpHeaders httpHeaders = new ReadOnlyHttpHeaders(false, "content-length", String.valueOf(CustomHttpServerHandler.response.length));
                DefaultFullHttpResponse response = new DefaultFullHttpResponse(
                    request.protocolVersion(), HttpResponseStatus.OK, byteBuf, httpHeaders, EmptyHttpHeaders.INSTANCE
                );
                ctx.writeAndFlush(response);
            }
            if (msg instanceof LastHttpContent) {
            }
            channels.put(ctx.channel().id().asLongText(), ctx.channel().id().asLongText());
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            cause.printStackTrace();
            ctx.close();
        }
    }


    @BeforeMethod
    public void setUp() throws InterruptedException {
        ServerBootstrap b = new ServerBootstrap();
        this.bossGroup = new NioEventLoopGroup(1);
        this.eventLoop = new NioEventLoopGroup(16);
        this.channelFuture = b.group(bossGroup, eventLoop)
            .channel(NioServerSocketChannel.class)
            .childHandler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) throws Exception {
                    ChannelPipeline p = ch.pipeline();
                    p.addLast(new HttpRequestDecoder());
                    p.addLast(new HttpResponseEncoder());
                    p.addLast(new CustomHttpServerHandler());
                }
            })
            .bind(8080)
            .sync()
            .await();
    }

    @AfterMethod
    public void tearDown() throws InterruptedException {
        channelFuture.channel().close().await();
        this.eventLoop.shutdownGracefully().await();
        this.bossGroup.shutdownGracefully().await();
    }

    @Test
    @Ignore
    public void testStreamingPutImageBig() throws Exception {
        int requests = 100000 * 4;
        int maxInFlight = 10;
        ExecutorService executorService = Executors.newFixedThreadPool(maxInFlight * 2);
        Executor publishService = Runnable::run;
        CountDownLatch cl = new CountDownLatch(requests);
        try (AsyncHttpClient client = asyncHttpClient(config()
            .setRequestTimeout(100 * 6000)
            .setConnectTimeout(10000)
            .setMaxConnections(maxInFlight*2))) {
            Semaphore semaphore = new Semaphore(maxInFlight);
            for (int i = 0; i < requests; i++) {
                executorService.submit(() -> {
                    try {
                        semaphore.acquire();
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                    client.preparePut(getTargetUrl())
                        .setHeader("test", "test")
                        .setBody(new AsyncIterablePublisher<>(new ReactiveStreamsTest.ByteBufIterable(LARGE_IMAGE_BYTES, 2342), publishService))
                        .execute(new StreamedAsyncHandler<Object>() {
                            @Override
                            public State onStream(Publisher<HttpResponseBodyPart> publisher) {
                                publisher.subscribe(new Subscriber<HttpResponseBodyPart>() {
                                    @Override
                                    public void onSubscribe(Subscription subscription) {
                                        subscription.request(Integer.MAX_VALUE);
                                    }

                                    @Override
                                    public void onNext(HttpResponseBodyPart httpResponseBodyPart) {

                                    }

                                    @Override
                                    public void onError(Throwable throwable) {
                                        throwable.printStackTrace();
                                    }

                                    @Override
                                    public void onComplete() {
                                        semaphore.release();
                                        cl.countDown();
                                    }
                                });
                                return State.CONTINUE;
                            }

                            @Override
                            public State onStatusReceived(org.asynchttpclient.HttpResponseStatus responseStatus) throws Exception {
                                return State.CONTINUE;
                            }

                            @Override
                            public State onHeadersReceived(HttpHeaders headers) throws Exception {
                                return State.CONTINUE;
                            }

                            @Override
                            public State onBodyPartReceived(HttpResponseBodyPart bodyPart) throws Exception {
                                return State.CONTINUE;
                            }

                            @Override
                            public void onThrowable(Throwable t) {
                                t.printStackTrace();
                                semaphore.release();
                                cl.countDown();
                            }

                            @Override
                            public Object onCompleted() throws Exception {
                                return null;
                            }
                        });
                });
            }
            cl.await();
        }
        System.out.println(CustomHttpServerHandler.channels.size());
//        publishService.shutdown();
        executorService.shutdown();
    }

    private String getTargetUrl() {
        return "http://localhost:8080/";
    }

}
