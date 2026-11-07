/*
 *    Copyright (c) 2026 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.netty.handler;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.HttpHeaders;
import org.asynchttpclient.AsyncCompletionHandlerBase;
import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.DefaultAsyncHttpClientConfig;
import org.asynchttpclient.HttpResponseBodyPart;
import org.asynchttpclient.HttpResponseStatus;
import org.asynchttpclient.ResponseBodyControl;
import org.asynchttpclient.channel.ChannelPoolPartitioning;
import org.asynchttpclient.netty.NettyResponseBodyControl;
import org.asynchttpclient.netty.NettyResponseFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;

import static org.asynchttpclient.Dsl.get;
import static org.asynchttpclient.netty.ResponseBodyControlAccess.controlOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpHandlerResponseBodyControlTest {

    private EmbeddedChannel channel;
    private HttpHandler httpHandler;

    @BeforeEach
    void setUp() {
        channel = new EmbeddedChannel();
        httpHandler = new HttpHandler(new DefaultAsyncHttpClientConfig.Builder().build(), null, null);
    }

    @AfterEach
    void tearDown() {
        channel.finishAndReleaseAll();
    }

    private static NettyResponseFuture<?> newFuture(AsyncHandler<?> handler) {
        return new NettyResponseFuture<>(get("http://localhost/").build(), handler, null, 0,
                ChannelPoolPartitioning.PerHostChannelPoolPartitioning.INSTANCE, null, null);
    }

    @Test
    void handlerInheritingOnResponseBodyStartNeedsNoControl() {
        assertFalse(HttpHandler.usesResponseBodyControl(new AsyncCompletionHandlerBase()));
        assertFalse(HttpHandler.usesResponseBodyControl(new PlainHandler()));
        assertFalse(HttpHandler.usesResponseBodyControl(new PlainHandler() {
        }));
    }

    @Test
    void handlerOverridingOnResponseBodyStartGetsControl() {
        assertTrue(HttpHandler.usesResponseBodyControl(new ControllingHandler()));
    }

    @Test
    void subclassOfOverridingHandlerGetsControl() {
        assertTrue(HttpHandler.usesResponseBodyControl(new ControllingHandler() {
        }));
    }

    @Test
    void handlerInheritingOverrideFromSubInterfaceGetsControl() {
        assertTrue(HttpHandler.usesResponseBodyControl(new SubInterfaceHandler()));
    }

    @Test
    void handlerWithSignatureNamingMissingTypeGetsControl() throws Exception {
        String handlerName = OptionalDependencyHandler.class.getName();
        HidingClassLoader loader = new HidingClassLoader(getClass().getClassLoader(), handlerName,
                OptionalType.class.getName());
        AsyncHandler<?> handler = (AsyncHandler<?>) loader.loadClass(handlerName).getDeclaredConstructor()
                .newInstance();
        assertThrows(NoClassDefFoundError.class,
                () -> handler.getClass().getMethod("onResponseBodyStart", ResponseBodyControl.class));

        assertTrue(HttpHandler.usesResponseBodyControl(handler));
    }

    @Test
    void inheritedOnResponseBodyStartCreatesNoControl() throws Exception {
        AsyncHandler<?> handler = new AsyncCompletionHandlerBase();
        NettyResponseFuture<?> future = newFuture(handler);

        assertFalse(httpHandler.abortAfterStartingResponseBody(channel, future, handler));

        assertNull(controlOf(future));
        assertTrue(channel.config().isAutoRead());
    }

    @Test
    void inheritedOnResponseBodyStartCompletesLeftoverControl() throws Exception {
        AsyncHandler<?> handler = new AsyncCompletionHandlerBase();
        NettyResponseFuture<?> future = newFuture(handler);
        NettyResponseBodyControl leftover = NettyResponseBodyControl.create(future, channel, (f, c, b) -> {
        });
        leftover.suspend();
        assertFalse(channel.config().isAutoRead());

        assertFalse(httpHandler.abortAfterStartingResponseBody(channel, future, handler));

        assertNull(controlOf(future));
        assertFalse(NettyResponseBodyControl.isSuspended(future, channel));
        assertTrue(channel.config().isAutoRead());
    }

    @Test
    void overriddenOnResponseBodyStartGetsActiveControl() throws Exception {
        AsyncHandler<?> handler = new ControllingHandler();
        NettyResponseFuture<?> future = newFuture(handler);

        assertFalse(httpHandler.abortAfterStartingResponseBody(channel, future, handler));

        assertNotNull(controlOf(future));
        assertTrue(NettyResponseBodyControl.isSuspended(future, channel));
        assertFalse(channel.config().isAutoRead());
        NettyResponseBodyControl.complete(future);
        assertTrue(channel.config().isAutoRead());
    }

    private static class PlainHandler implements AsyncHandler<Void> {

        @Override
        public State onStatusReceived(HttpResponseStatus responseStatus) {
            return State.CONTINUE;
        }

        @Override
        public State onHeadersReceived(HttpHeaders headers) {
            return State.CONTINUE;
        }

        @Override
        public State onBodyPartReceived(HttpResponseBodyPart bodyPart) {
            return State.CONTINUE;
        }

        @Override
        public void onThrowable(Throwable t) {
        }

        @Override
        public Void onCompleted() {
            return null;
        }
    }

    private static class ControllingHandler extends PlainHandler {

        @Override
        public State onResponseBodyStart(ResponseBodyControl control) {
            control.suspend();
            return State.CONTINUE;
        }
    }

    private interface SuspendingHandler extends AsyncHandler<Void> {

        @Override
        default State onResponseBodyStart(ResponseBodyControl control) {
            control.suspend();
            return State.CONTINUE;
        }
    }

    private static class SubInterfaceHandler extends PlainHandler implements SuspendingHandler {
    }

    /**
     * Stands for a type from an optional dependency that is absent at runtime.
     */
    public static final class OptionalType {
    }

    /**
     * Inherits onResponseBodyStart but names {@link OptionalType} in a public signature.
     */
    public static final class OptionalDependencyHandler implements AsyncHandler<Void> {

        public void attach(OptionalType type) {
        }

        @Override
        public State onStatusReceived(HttpResponseStatus responseStatus) {
            return State.CONTINUE;
        }

        @Override
        public State onHeadersReceived(HttpHeaders headers) {
            return State.CONTINUE;
        }

        @Override
        public State onBodyPartReceived(HttpResponseBodyPart bodyPart) {
            return State.CONTINUE;
        }

        @Override
        public void onThrowable(Throwable t) {
        }

        @Override
        public Void onCompleted() {
            return null;
        }
    }

    /**
     * Defines one class itself and hides another, as a deployment missing an optional dependency would.
     */
    private static final class HidingClassLoader extends ClassLoader {

        private final String definedName;
        private final String hiddenName;

        HidingClassLoader(ClassLoader parent, String definedName, String hiddenName) {
            super(parent);
            this.definedName = definedName;
            this.hiddenName = hiddenName;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.equals(hiddenName)) {
                throw new ClassNotFoundException(name);
            }
            if (!name.equals(definedName)) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    byte[] bytes = readClass(name);
                    loaded = defineClass(name, bytes, 0, bytes.length);
                }
                return loaded;
            }
        }

        private byte[] readClass(String name) throws ClassNotFoundException {
            try (InputStream in = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                if (in == null) {
                    throw new ClassNotFoundException(name);
                }
                return in.readAllBytes();
            } catch (IOException e) {
                throw new ClassNotFoundException(name, e);
            }
        }
    }
}
