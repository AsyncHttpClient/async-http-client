package com.ning.http.client.providers.netty.util;

import static com.ning.http.util.MiscUtils.isNonEmpty;

import org.jboss.netty.handler.codec.http.HttpHeaders;

import com.ning.http.client.uri.UriComponents;

import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;

public final class HttpUtil {

    public static final String HTTP = "http";
    public static final String HTTPS = "https";
    public static final String WEBSOCKET = "ws";
    public static final String WEBSOCKET_SSL = "wss";

    private HttpUtil() {
    }

    public static boolean isNTLM(List<String> auth) {
        return isNonEmpty(auth) && auth.get(0).startsWith("NTLM");
    }

    public static List<String> getNettyHeaderValuesByCaseInsensitiveName(HttpHeaders headers, String name) {
        ArrayList<String> l = new ArrayList<String>();
        for (Entry<String, String> e : headers) {
            if (e.getKey().equalsIgnoreCase(name)) {
                l.add(e.getValue().trim());
            }
        }
        return l;
    }

    public static boolean isWebSocket(String scheme) {
        return WEBSOCKET.equalsIgnoreCase(scheme) || WEBSOCKET_SSL.equalsIgnoreCase(scheme);
    }

    public static boolean isSecure(String scheme) {
        return HTTPS.equalsIgnoreCase(scheme) || WEBSOCKET_SSL.equalsIgnoreCase(scheme);
    }

    public static boolean isSecure(UriComponents uri) {
        return isSecure(uri.getScheme());
    }
}
