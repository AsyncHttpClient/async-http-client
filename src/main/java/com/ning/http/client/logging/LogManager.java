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
package com.ning.http.client.logging;

import java.util.concurrent.atomic.AtomicReference;

public class LogManager {
    private static final AtomicReference<LoggerProvider> providerRef = new AtomicReference<LoggerProvider>();

    private static void initProvider() {
        String providerClass = System.getProperty("com.ning.http.client.logging.LoggerProvider.class", JulLoggerProvider.class.getName());
        LoggerProvider providerToUse = null;

        try {
            Class<?> clazz = Class.forName(providerClass);

            if (LoggerProvider.class.isAssignableFrom(clazz)) {
                providerToUse = (LoggerProvider) clazz.newInstance();
            } else {
                providerToUse = new JulLoggerProvider();
                providerToUse.getLogger(LogManager.class).warn("Specified logger provider class %s does not implement the %s interface",
                        providerClass, LoggerProvider.class.getName());
            }
        }
        catch (Throwable ex) {
            providerToUse = new JulLoggerProvider();
            providerToUse.getLogger(LogManager.class).warn(ex, "Could not instantiate the logger provider class %s", providerClass);
        }
        finally {
            providerRef.set(providerToUse);
        }
    }

    public static Logger getLogger(Class<?> clazz) {
        LoggerProvider provider = providerRef.get();

        if (provider == null) {
            initProvider();
        }
        return providerRef.get().getLogger(clazz);
    }

    public static void setProvider(LoggerProvider provider) {
        providerRef.set(provider);
    }
}
