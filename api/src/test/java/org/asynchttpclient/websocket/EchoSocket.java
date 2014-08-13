package org.asynchttpclient.websocket;

import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketAdapter;

import java.io.IOException;
import java.nio.ByteBuffer;

public class EchoSocket extends WebSocketAdapter {

    @Override
    public void onWebSocketConnect(Session sess) {
        super.onWebSocketConnect(sess);
        sess.setIdleTimeout(10000);
        sess.setMaximumMessageSize(1000);
    }

    @Override
    public void onWebSocketClose(int statusCode, String reason) {
        try {
            getSession().close();
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        super.onWebSocketClose(statusCode, reason);
    }

    @Override
    public void onWebSocketBinary(byte[] payload, int offset, int len) {
        if (isNotConnected()) {
            return;
        }
        try {
            getRemote().sendBytes(ByteBuffer.wrap(payload, offset, len));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onWebSocketText(String message) {
        if (isNotConnected()) {
            return;
        }
        try {
            if (message.equals("CLOSE"))
                getSession().close();
            else
                getRemote().sendString(message);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
