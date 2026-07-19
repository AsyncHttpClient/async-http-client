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

public class Redirect30xInterceptorTest {

    @Test
    public void primitiveRedirectStatusCheckMatchesRedirectStatuses() {
        for (int statusCode : new int[]{301, 302, 303, 307, 308}) {
            assertTrue(Redirect30xInterceptor.isRedirectStatus(statusCode), "status " + statusCode);
        }

        for (int statusCode : new int[]{100, 200, 300, 304, 305, 306, 309, 400, 500}) {
            assertFalse(Redirect30xInterceptor.isRedirectStatus(statusCode), "status " + statusCode);
        }
    }
}
