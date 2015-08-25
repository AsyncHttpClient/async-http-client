/*
 * Copyright (c) 2013-2015 Sonatype, Inc. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */

package com.ning.http.client.providers.grizzly;

import com.ning.http.client.uri.Uri;
import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.Grizzly;
import org.glassfish.grizzly.attributes.Attribute;

public class Utils {
    private static class NTLM_HOLDER {
        private static final Attribute<Boolean> IS_NTLM_DONE =
                Grizzly.DEFAULT_ATTRIBUTE_BUILDER.createAttribute(
                        "com.ning.http.client.providers.grizzly.ntlm-done");
    }
    // ------------------------------------------------------------ Constructors

    private Utils() {
    }

    // ---------------------------------------------------------- Public Methods

    public static boolean getAndSetNtlmAttempted(final Connection c) {
        final Boolean v = NTLM_HOLDER.IS_NTLM_DONE.get(c);
        if (v == null) {
            NTLM_HOLDER.IS_NTLM_DONE.set(c, Boolean.TRUE);
            return false;
        }
        
        return true;
    }
    
    public static void setNtlmEstablished(final Connection c) {
        NTLM_HOLDER.IS_NTLM_DONE.set(c, Boolean.TRUE);
    }

    public static boolean isNtlmEstablished(final Connection c) {
        return Boolean.TRUE.equals(NTLM_HOLDER.IS_NTLM_DONE.get(c));
    }
    
    public static boolean isSecure(final String uri) {
        return (uri.startsWith("https") || uri.startsWith("wss"));
    }
    
    public static boolean isSecure(final Uri uri) {
        final String scheme = uri.getScheme();
        return ("https".equals(scheme) || "wss".equals(scheme));
    }
    
    static String discoverTestName(final String defaultName) {
        final StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
        final int strackTraceLen = stackTrace.length;
        
        if (stackTrace[strackTraceLen - 1].getClassName().contains("surefire")) {
            for (int i = strackTraceLen - 2; i >= 0; i--) {
                if (stackTrace[i].getClassName().contains("com.ning.http.client.async")) {
                    return "grizzly-kernel-" +
                            stackTrace[i].getClassName() + "." + stackTrace[i].getMethodName();
                }
            }
        }
        
        return defaultName;
    }    
}
