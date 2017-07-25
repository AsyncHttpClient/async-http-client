package org.asynchttpclient.netty.channel;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.websocketx.extensions.WebSocketExtensionData;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Created by elena on 24.07.17.
 */
public class MyWebSocketExtensionUtil {
    private static final String EXTENSION_SEPARATOR = ",";
    private static final String PARAMETER_SEPARATOR = ";";
    private static final char PARAMETER_EQUAL = '=';
    private static final Pattern PARAMETER = Pattern.compile("^([^=]+)(=[\\\"]?([^\\\"]+)[\\\"]?)?$");

    static boolean isWebsocketUpgrade(HttpHeaders headers) {
        return headers.containsValue(HttpHeaderNames.CONNECTION, HttpHeaderValues.UPGRADE, true) && headers.contains(HttpHeaderNames.UPGRADE, HttpHeaderValues.WEBSOCKET, true);
    }

    public static List<WebSocketExtensionData> extractExtensions(String extensionHeader) {
        String[] rawExtensions = extensionHeader.split(",");
        if(rawExtensions.length <= 0) {
            return Collections.emptyList();
        } else {
            List<WebSocketExtensionData> extensions = new ArrayList(rawExtensions.length);
            String[] var3 = rawExtensions;
            int var4 = rawExtensions.length;

            for(int var5 = 0; var5 < var4; ++var5) {
                String rawExtension = var3[var5];
                String[] extensionParameters = rawExtension.split(";");
                String name = extensionParameters[0].trim();
                Object parameters;
                if(extensionParameters.length > 1) {
                    parameters = new HashMap(extensionParameters.length - 1);

                    for(int i = 1; i < extensionParameters.length; ++i) {
                        String parameter = extensionParameters[i].trim();
                        Matcher parameterMatcher = PARAMETER.matcher(parameter);
                        if(parameterMatcher.matches() && parameterMatcher.group(1) != null) {
                            ((Map)parameters).put(parameterMatcher.group(1), parameterMatcher.group(3));
                        }
                    }
                } else {
                    parameters = Collections.emptyMap();
                }

                extensions.add(new WebSocketExtensionData(name, (Map)parameters));
            }

            return extensions;
        }
    }

    static String appendExtension(String currentHeaderValue, String extensionName, Map<String, String> extensionParameters) {
        StringBuilder newHeaderValue = new StringBuilder(currentHeaderValue != null?currentHeaderValue.length():extensionName.length() + 1);
        if(currentHeaderValue != null && !currentHeaderValue.trim().isEmpty()) {
            newHeaderValue.append(currentHeaderValue);
            newHeaderValue.append(",");
        }

        newHeaderValue.append(extensionName);
        Iterator var4 = extensionParameters.entrySet().iterator();

        while(var4.hasNext()) {
            Map.Entry<String, String> extensionParameter = (Map.Entry)var4.next();
            newHeaderValue.append(";");
            newHeaderValue.append((String)extensionParameter.getKey());
            if(extensionParameter.getValue() != null) {
                newHeaderValue.append('=');
                newHeaderValue.append((String)extensionParameter.getValue());
            }
        }

        return newHeaderValue.toString();
    }

    private MyWebSocketExtensionUtil() {
    }
}
