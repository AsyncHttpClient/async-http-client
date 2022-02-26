package org.asynchttpclient.netty;

import org.asynchttpclient.DefaultAsyncHttpClient;
import org.asynchttpclient.DefaultAsyncHttpClientConfig;
import org.asynchttpclient.RequestBuilder;
import org.testng.annotations.BeforeTest;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.Arrays;
import java.util.concurrent.Exchanger;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.not;
import static org.testng.Assert.assertTrue;

public class NettyConnectionResetByPeerTest {

    private String resettingServerAddress;

    @BeforeTest
    public void setUp() {
        resettingServerAddress = createResettingServer();
    }

    @Test
    public void testAsyncHttpClientConnectionResetByPeer() throws InterruptedException {
        try {
            DefaultAsyncHttpClientConfig config = new DefaultAsyncHttpClientConfig.Builder()
                    .setRequestTimeout(1500)
                    .build();
            new DefaultAsyncHttpClient(config).executeRequest(
                    new RequestBuilder("GET").setUrl(resettingServerAddress)
            )
                    .get();
        } catch (ExecutionException e) {
            Throwable ex = e.getCause();
            assertThat(ex, is(instanceOf(IOException.class)));
        }
    }

    private static String createResettingServer() {
        return createServer(sock -> {
            try (Socket socket = sock) {
                socket.setSoLinger(true, 0);
                InputStream inputStream = socket.getInputStream();
                //to not eliminate read
                OutputStream os = new OutputStream() {
                    @Override
                    public void write(int b) {
                        // Do nothing
                    }
                };
                os.write(startRead(inputStream));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static String createServer(Consumer<Socket> handler) {
        Exchanger<Integer> portHolder = new Exchanger<>();
        Thread t = new Thread(() -> {
            try (ServerSocket ss = new ServerSocket(0)) {
                portHolder.exchange(ss.getLocalPort());
                while (true) {
                    handler.accept(ss.accept());
                }
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread()
                            .interrupt();
                }
                throw new RuntimeException(e);
            }
        });
        t.setDaemon(true);
        t.start();
        return tryGetAddress(portHolder);
    }

    private static String tryGetAddress(Exchanger<Integer> portHolder) {
        try {
            return "http://localhost:" + portHolder.exchange(0);
        } catch (InterruptedException e) {
            Thread.currentThread()
                    .interrupt();
            throw new RuntimeException(e);
        }
    }

    private static byte[] startRead(InputStream inputStream) throws IOException {
        byte[] buffer = new byte[4];
        int length = inputStream.read(buffer);
        return Arrays.copyOf(buffer, length);
    }

}
