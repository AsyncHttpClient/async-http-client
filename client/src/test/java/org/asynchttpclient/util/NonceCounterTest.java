/*
 *    Copyright (c) 2025 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class NonceCounterTest {

    @Test
    void sequentialCallsIncrement() {
        NonceCounter counter = new NonceCounter();
        assertEquals("00000001", counter.nextNc("nonce1"));
        assertEquals("00000002", counter.nextNc("nonce1"));
        assertEquals("00000003", counter.nextNc("nonce1"));
    }

    @Test
    void differentNoncesTrackIndependently() {
        NonceCounter counter = new NonceCounter();
        assertEquals("00000001", counter.nextNc("nonceA"));
        assertEquals("00000001", counter.nextNc("nonceB"));
        assertEquals("00000002", counter.nextNc("nonceA"));
        assertEquals("00000002", counter.nextNc("nonceB"));
    }

    @Test
    void resetClearsCount() {
        NonceCounter counter = new NonceCounter();
        assertEquals("00000001", counter.nextNc("nonce1"));
        assertEquals("00000002", counter.nextNc("nonce1"));
        counter.reset("nonce1");
        assertEquals("00000001", counter.nextNc("nonce1"));
    }

    @Test
    void resetDoesNotAffectOtherNonces() {
        NonceCounter counter = new NonceCounter();
        counter.nextNc("nonceA");
        counter.nextNc("nonceB");
        counter.reset("nonceA");
        assertEquals("00000001", counter.nextNc("nonceA"));
        assertEquals("00000002", counter.nextNc("nonceB"));
    }

    @Test
    void highCountStillFormats8Digits() {
        NonceCounter counter = new NonceCounter();
        for (int i = 0; i < 255; i++) {
            counter.nextNc("nonce");
        }
        assertEquals("00000100", counter.nextNc("nonce"));
    }
}
