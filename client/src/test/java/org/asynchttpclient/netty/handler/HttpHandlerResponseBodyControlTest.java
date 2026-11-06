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

import io.netty.handler.codec.http.HttpHeaders;
import org.asynchttpclient.AsyncCompletionHandlerBase;
import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.HttpResponseBodyPart;
import org.asynchttpclient.HttpResponseStatus;
import org.asynchttpclient.ResponseBodyControl;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpHandlerResponseBodyControlTest {

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
}
