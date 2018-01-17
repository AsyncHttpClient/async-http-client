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

import org.testng.annotations.Test;

import java.util.Map;

import static org.testng.Assert.assertEquals;

/**
 * @author Benjamin Hanzelmann
 */
public class PropertiesBasedResumableProcesserTest {

  @Test
  public void testSaveLoad() {
    PropertiesBasedResumableProcessor p = new PropertiesBasedResumableProcessor();
    p.put("http://localhost/test.url", 15L);
    p.put("http://localhost/test2.url", 50L);
    p.save(null);
    p = new PropertiesBasedResumableProcessor();
    Map<String, Long> m = p.load();
    assertEquals(m.size(), 2);
    assertEquals(m.get("http://localhost/test.url"), Long.valueOf(15L));
    assertEquals(m.get("http://localhost/test2.url"), Long.valueOf(50L));
  }

  @Test
  public void testRemove() {
    PropertiesBasedResumableProcessor propertiesProcessor = new PropertiesBasedResumableProcessor();
    propertiesProcessor.put("http://localhost/test.url", 15L);
    propertiesProcessor.put("http://localhost/test2.url", 50L);
    propertiesProcessor.remove("http://localhost/test.url");
    propertiesProcessor.save(null);
    propertiesProcessor = new PropertiesBasedResumableProcessor();
    Map<String, Long> propertiesMap = propertiesProcessor.load();
    assertEquals(propertiesMap.size(), 1);
    assertEquals(propertiesMap.get("http://localhost/test2.url"), Long.valueOf(50L));
  }
}
