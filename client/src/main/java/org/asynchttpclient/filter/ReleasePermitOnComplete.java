/*
 *    Copyright (c) 2023 AsyncHttpClient Project. All rights reserved.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.asynchttpclient.filter;

import org.asynchttpclient.AsyncHandler;

import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Semaphore;

/**
 * Wrapper for {@link AsyncHandler}s to release a permit on {@link AsyncHandler#onCompleted()}. This is done via a dynamic proxy to preserve all interfaces of the wrapped handler.
 */
public final class ReleasePermitOnComplete {

    private ReleasePermitOnComplete() {
        // Prevent outside initialization
    }

    /**
     * Wrap handler to release the permit of the semaphore on {@link AsyncHandler#onCompleted()}.
     *
     * @param handler   the handler to be wrapped
     * @param available the Semaphore to be released when the wrapped handler is completed
     * @param <T>       the handler result type
     * @return the wrapped handler
     */
    @SuppressWarnings("unchecked")
    public static <T> AsyncHandler<T> wrap(final AsyncHandler<T> handler, final Semaphore available) {
        Class<?> handlerClass = handler.getClass();
        ClassLoader classLoader = handlerClass.getClassLoader();
        Class<?>[] interfaces = allInterfaces(handlerClass);

        return (AsyncHandler<T>) Proxy.newProxyInstance(classLoader, interfaces, (proxy, method, args) -> {
            try {
                return method.invoke(handler, args);
            } finally {
                switch (method.getName()) {
                    case "onCompleted":
                    case "onThrowable":
                        available.release();
                    default:
                }
            }
        });
    }

    /**
     * Extract all interfaces of a class.
     *
     * @param handlerClass the handler class
     * @return all interfaces implemented by this class
     */
    private static Class<?>[] allInterfaces(Class<?> handlerClass) {
        Set<Class<?>> allInterfaces = new HashSet<>();
        for (Class<?> clazz = handlerClass; clazz != null; clazz = clazz.getSuperclass()) {
            Collections.addAll(allInterfaces, clazz.getInterfaces());
        }
        return allInterfaces.toArray(new Class[0]);
    }
}
