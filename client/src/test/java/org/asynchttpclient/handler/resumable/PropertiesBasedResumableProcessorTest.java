/*
 * Copyright (c) 2010 Sonatype, Inc. All rights reserved.
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
package org.asynchttpclient.handler.resumable;

import io.github.artsok.RepeatedIfExceptionsTest;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * @author Benjamin Hanzelmann
 */
public class PropertiesBasedResumableProcessorTest {

    @RepeatedIfExceptionsTest(repeats = 10)
    public void testSaveLoad() {
        PropertiesBasedResumableProcessor processor = new PropertiesBasedResumableProcessor();
        processor.put("http://localhost/test.url", 15L);
        processor.put("http://localhost/test2.url", 50L);
        processor.save(null);
        processor = new PropertiesBasedResumableProcessor();

        Map<String, Long> map = processor.load();
        assertEquals(2, map.size());
        assertEquals(Long.valueOf(15L), map.get("http://localhost/test.url"));
        assertEquals(Long.valueOf(50L), map.get("http://localhost/test2.url"));
    }

    @RepeatedIfExceptionsTest(repeats = 10)
    public void testRemove() {
        PropertiesBasedResumableProcessor processor = new PropertiesBasedResumableProcessor();
        processor.put("http://localhost/test.url", 15L);
        processor.put("http://localhost/test2.url", 50L);
        processor.remove("http://localhost/test.url");
        processor.save(null);
        processor = new PropertiesBasedResumableProcessor();

        Map<String, Long> propertiesMap = processor.load();
        assertEquals(1, propertiesMap.size());
        assertEquals(Long.valueOf(50L), propertiesMap.get("http://localhost/test2.url"));
    }
}
