package org.asynchttpclient.ws;

import static org.asynchttpclient.Dsl.asyncHttpClient;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.asynchttpclient.AsyncHttpClient;
import org.eclipse.jetty.websocket.server.WebSocketHandler;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

public class WebSocketWriteCompleteListenerTest extends AbstractBasicTest {

    private CompletableFuture<Void> closeFuture;
    private CompletableFuture<Void> resultFuture;

    @Override
    public WebSocketHandler getWebSocketHandler() {
        return new WebSocketHandler() {
            @Override
            public void configure(WebSocketServletFactory factory) {
                factory.register(EchoSocket.class);
            }
        };
    }

    @BeforeMethod
    public void setup() {
        closeFuture = new CompletableFuture<>();
        resultFuture = new CompletableFuture<>();
    }

    @Test(groups = "standalone", timeOut = 60000)
    public void sendTextMessage() throws Exception {
        try (AsyncHttpClient c = asyncHttpClient()) {
            getWebSocket(c).sendMessage("TEXT", resultHandler());
            resultFuture.get(10, TimeUnit.SECONDS);
        }
    }

    @Test(groups = "standalone", timeOut = 60000, expectedExceptions = ExecutionException.class)
    public void sendTextMessageExpectFailure() throws Exception {
        try (AsyncHttpClient c = asyncHttpClient()) {
            WebSocket websocket = getWebSocket(c);
            websocket.close();
            closeFuture.get();
            websocket.sendMessage("TEXT", resultHandler());
            resultFuture.get(10, TimeUnit.SECONDS);
        }
    }

    @Test(groups = "standalone", timeOut = 60000)
    public void sendByteMessage() throws Exception {
        try (AsyncHttpClient c = asyncHttpClient()) {
            getWebSocket(c).sendMessage("BYTES".getBytes(), resultHandler());
            resultFuture.get(10, TimeUnit.SECONDS);
        }
    }

    @Test(groups = "standalone", timeOut = 60000, expectedExceptions = ExecutionException.class)
    public void sendByteMessageExpectFailure() throws Exception {
        try (AsyncHttpClient c = asyncHttpClient()) {
            WebSocket websocket = getWebSocket(c);
            websocket.close();
            closeFuture.get();

            websocket.sendMessage("BYTES".getBytes(), resultHandler());
            resultFuture.get(10, TimeUnit.SECONDS);
        }
    }

    @Test(groups = "standalone", timeOut = 60000)
    public void sendPingMessage() throws Exception {
        try (AsyncHttpClient c = asyncHttpClient()) {
            getWebSocket(c).sendPing("PING".getBytes(), resultHandler());
            resultFuture.get(10, TimeUnit.SECONDS);
        }
    }

    @Test(groups = "standalone", timeOut = 60000, expectedExceptions = ExecutionException.class)
    public void sendPingMessageExpectFailure() throws Exception {
        try (AsyncHttpClient c = asyncHttpClient()) {
            WebSocket websocket = getWebSocket(c);
            websocket.close();
            closeFuture.get();

            websocket.sendPing("PING".getBytes(), resultHandler());
            resultFuture.get(10, TimeUnit.SECONDS);
        }
    }

    @Test(groups = "standalone", timeOut = 60000)
    public void sendPongMessage() throws Exception {
        try (AsyncHttpClient c = asyncHttpClient()) {
            getWebSocket(c).sendPong("PONG".getBytes(), resultHandler());
            resultFuture.get(10, TimeUnit.SECONDS);
        }
    }

    @Test(groups = "standalone", timeOut = 60000, expectedExceptions = ExecutionException.class)
    public void sendPongMessageExpectFailure() throws Exception {
        try (AsyncHttpClient c = asyncHttpClient()) {
            WebSocket websocket = getWebSocket(c);
            websocket.close();
            closeFuture.get();

            websocket.sendPong("PONG".getBytes(), resultHandler());
            resultFuture.get(10, TimeUnit.SECONDS);
        }
    }

    @Test(groups = "standalone", timeOut = 60000)
    public void streamBytes() throws Exception {
        try (AsyncHttpClient c = asyncHttpClient()) {
            getWebSocket(c).stream("STREAM".getBytes(), true, resultHandler());
            resultFuture.get(10, TimeUnit.SECONDS);
        }
    }

    @Test(groups = "standalone", timeOut = 60000, expectedExceptions = ExecutionException.class)
    public void streamBytesExpectFailure() throws Exception {
        try (AsyncHttpClient c = asyncHttpClient()) {
            WebSocket websocket = getWebSocket(c);
            websocket.close();
            closeFuture.get();

            websocket.stream("STREAM".getBytes(), true, resultHandler());
            resultFuture.get(10, TimeUnit.SECONDS);
        }
    }

    @Test(groups = "standalone")
    public void streamText() throws Exception {
        try (AsyncHttpClient c = asyncHttpClient()) {
            getWebSocket(c).stream("STREAM", true, resultHandler());
            resultFuture.get();
        }
    }

    @Test(groups = "standalone", expectedExceptions = ExecutionException.class)
    public void streamTextExpectFailure() throws Exception {
        try (AsyncHttpClient c = asyncHttpClient()) {
            WebSocket websocket = getWebSocket(c);
            websocket.close();
            closeFuture.get();

            websocket.stream("STREAM", true, resultHandler());
            resultFuture.get();
        }
    }

    private WebSocket getWebSocket(final AsyncHttpClient c) throws Exception {
        return c.prepareGet(getTargetUrl()).execute(new WebSocketUpgradeHandler.Builder().addWebSocketListener(new DefaultWebSocketListener() {
            @Override
            public void onClose(final WebSocket websocket) {
                closeFuture.complete(null);
            }
        }).build()).get();
    }

    private WebSocketWriteCompleteListener resultHandler() {
        return result -> {
            if (result.isSuccess()) {
                this.resultFuture.complete(null);
            } else {
                this.resultFuture.completeExceptionally(result.getFailure());
            }
        };
    }

}
