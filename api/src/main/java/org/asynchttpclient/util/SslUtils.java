/*
 * Copyright 2010 Ning, Inc.
 *
 * Ning licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package org.asynchttpclient.util;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.security.GeneralSecurityException;

/**
 * This class is a copy of http://github.com/sonatype/wagon-ning/raw/master/src/main/java/org/apache/maven/wagon/providers/http/SslUtils.java
 */
public class SslUtils {

    private static class SingletonHolder {
        public static final SslUtils instance = new SslUtils();
    }

    public static SslUtils getInstance() {
        return SingletonHolder.instance;
    }

    public SSLContext getSSLContext() throws GeneralSecurityException, IOException {
        return SSLContext.getDefault();
    }
}
