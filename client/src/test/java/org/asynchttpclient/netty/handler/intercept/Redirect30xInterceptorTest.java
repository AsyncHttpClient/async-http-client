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
package org.asynchttpclient.netty.handler.intercept;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link Redirect30xInterceptor#isRedirect(int)}: the 3xx class check and the
 * {@link Redirect30xInterceptor#REDIRECT_STATUSES} membership must agree, so a 3xx that is not a followed
 * redirect is rejected just like a non-3xx status.
 */
public class Redirect30xInterceptorTest {

    @Test
    public void acceptsTheFollowedRedirectStatuses() {
        for (int statusCode : new int[]{301, 302, 303, 307, 308}) {
            assertTrue(Redirect30xInterceptor.isRedirect(statusCode), statusCode + " should be a redirect");
        }
    }

    @Test
    public void rejects3xxStatusesThatAreNotFollowed() {
        // in the 3xx class, but not redirects this interceptor acts on: 304 in particular must fall through
        // to the normal response path rather than be treated as a redirect
        for (int statusCode : new int[]{300, 304, 305, 306, 399}) {
            assertFalse(Redirect30xInterceptor.isRedirect(statusCode), statusCode + " should not be a redirect");
        }
    }

    @Test
    public void rejectsStatusesOutsideThe3xxClass() {
        for (int statusCode : new int[]{100, 200, 204, 299, 400, 404, 500}) {
            assertFalse(Redirect30xInterceptor.isRedirect(statusCode), statusCode + " should not be a redirect");
        }
    }
}
