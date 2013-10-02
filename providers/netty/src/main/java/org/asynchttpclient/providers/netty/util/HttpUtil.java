package org.asynchttpclient.providers.netty.util;

import static org.asynchttpclient.util.MiscUtil.isNonEmpty;

import java.net.URI;
import java.util.List;

public class HttpUtil {

    private HttpUtil() {
    }

    public static final String HTTPS = "https";
    public static final String HTTP = "http";
    public static final String WEBSOCKET = "ws";
    public static final String WEBSOCKET_SSL = "wss";

    public static boolean isNTLM(List<String> auth) {
        return isNonEmpty(auth) && auth.get(0).startsWith("NTLM");
    }

    public static boolean isWebSocket(URI uri) {
        return WEBSOCKET.equalsIgnoreCase(uri.getScheme()) || WEBSOCKET_SSL.equalsIgnoreCase(uri.getScheme());
    }

    public static boolean isSecure(String scheme) {
        return HTTPS.equalsIgnoreCase(scheme) || WEBSOCKET_SSL.equalsIgnoreCase(scheme);
    }

    public static boolean isSecure(URI uri) {
        return isSecure(uri.getScheme());
    }
}
