package org.asynchttpclient.netty.channel;

import io.netty.handler.codec.compression.ZlibCodecFactory;
import io.netty.handler.codec.http.websocketx.extensions.*;
//import io.netty.handler.codec.http.websocketx.extensions.compression.PerMessageDeflateClientExtensionHandshaker;
//import io.netty.handler.codec.http.websocketx.extensions.compression.PerMessageDeflateDecoder;
//import io.netty.handler.codec.http.websocketx.extensions.compression.PerMessageDeflateEncoder;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Created by elena on 24.07.17.
 */
public class MyPerMessageDeflateClientExtensionHandshaker implements WebSocketClientExtensionHandshaker {
    private final int compressionLevel;
    private final boolean allowClientWindowSize;
    private final int requestedServerWindowSize;
    private final boolean allowClientNoContext;
    private final boolean requestedServerNoContext;

    public MyPerMessageDeflateClientExtensionHandshaker() {
        this(6, ZlibCodecFactory.isSupportingWindowSizeAndMemLevel(), 15, false, false);
    }

    public MyPerMessageDeflateClientExtensionHandshaker(int compressionLevel, boolean allowClientWindowSize, int requestedServerWindowSize, boolean allowClientNoContext, boolean requestedServerNoContext) {
        if(requestedServerWindowSize <= 15 && requestedServerWindowSize >= 8) {
            if(compressionLevel >= 0 && compressionLevel <= 9) {
                this.compressionLevel = compressionLevel;
                this.allowClientWindowSize = allowClientWindowSize;
                this.requestedServerWindowSize = requestedServerWindowSize;
                this.allowClientNoContext = allowClientNoContext;
                this.requestedServerNoContext = requestedServerNoContext;
            } else {
                throw new IllegalArgumentException("compressionLevel: " + compressionLevel + " (expected: 0-9)");
            }
        } else {
            throw new IllegalArgumentException("requestedServerWindowSize: " + requestedServerWindowSize + " (expected: 8-15)");
        }
    }

    public WebSocketExtensionData newRequestData() {
        HashMap parameters = new HashMap(4);
        if(this.requestedServerWindowSize != 15) {
            parameters.put("server_no_context_takeover", (Object)null);
        }

        if(this.allowClientNoContext) {
            parameters.put("client_no_context_takeover", (Object)null);
        }

        if(this.requestedServerWindowSize != 15) {
            parameters.put("server_max_window_bits", Integer.toString(this.requestedServerWindowSize));
        }

        if(this.allowClientWindowSize) {
            parameters.put("client_max_window_bits", (Object)null);
        }

//        System.out.println("MyPerMessageDeflateClientExtensionHandshaker parameters="+parameters);
        return new WebSocketExtensionData("permessage-deflate", parameters);
    }

    public WebSocketClientExtension handshakeExtension(WebSocketExtensionData extensionData) {
        if(!"permessage-deflate".equals(extensionData.name())) {
            return null;
        } else {
            boolean succeed = true;
            int clientWindowSize = 15;
            int serverWindowSize = 15;
            boolean serverNoContext = false;
            boolean clientNoContext = false;
            Iterator parametersIterator = extensionData.parameters().entrySet().iterator();

            while(succeed && parametersIterator.hasNext()) {
                Map.Entry<String, String> parameter = (Map.Entry)parametersIterator.next();
                if("client_max_window_bits".equalsIgnoreCase((String)parameter.getKey())) {
                    if(this.allowClientWindowSize) {
                        clientWindowSize = Integer.parseInt((String)parameter.getValue());
                    } else {
                        succeed = false;
                    }
                } else if("server_max_window_bits".equalsIgnoreCase((String)parameter.getKey())) {
                    serverWindowSize = Integer.parseInt((String)parameter.getValue());
                    if(clientWindowSize > 15 || clientWindowSize < 8) {
                        succeed = false;
                    }
                } else if("client_no_context_takeover".equalsIgnoreCase((String)parameter.getKey())) {
                    if(this.allowClientNoContext) {
                        clientNoContext = true;
                    } else {
                        succeed = false;
                    }
                } else if("server_no_context_takeover".equalsIgnoreCase((String)parameter.getKey())) {
                    if(this.requestedServerNoContext) {
                        serverNoContext = true;
                    } else {
                        succeed = false;
                    }
                } else {
                    succeed = false;
                }
            }

            if(this.requestedServerNoContext && !serverNoContext || this.requestedServerWindowSize != serverWindowSize) {
                succeed = false;
            }

            return succeed?new MyPerMessageDeflateClientExtensionHandshaker.PermessageDeflateExtension(serverNoContext, serverWindowSize, clientNoContext, clientWindowSize):null;
        }
    }

    private final class PermessageDeflateExtension implements WebSocketClientExtension {
        private final boolean serverNoContext;
        private final int serverWindowSize;
        private final boolean clientNoContext;
        private final int clientWindowSize;

        public int rsv() {
            return 4;
        }

        public PermessageDeflateExtension(boolean serverNoContext, int serverWindowSize, boolean clientNoContext, int clientWindowSize) {
            this.serverNoContext = serverNoContext;
            this.serverWindowSize = serverWindowSize;
            this.clientNoContext = clientNoContext;
            this.clientWindowSize = clientWindowSize;
        }

        public WebSocketExtensionEncoder newExtensionEncoder() {
            return new MyPerMessageDeflateEncoder(MyPerMessageDeflateClientExtensionHandshaker.this.compressionLevel, this.serverWindowSize, this.serverNoContext);
        }

        public WebSocketExtensionDecoder newExtensionDecoder() {
            return new MyPerMessageDeflateDecoder(this.clientNoContext);
        }
    }
}
