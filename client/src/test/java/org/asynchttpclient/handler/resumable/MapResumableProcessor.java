/*
 *    Copyright (c) 2015-2023 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.handler.resumable;

import org.asynchttpclient.handler.resumable.ResumableAsyncHandler.ResumableProcessor;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * @author Benjamin Hanzelmann
 */
public class MapResumableProcessor implements ResumableProcessor {

    private final Map<String, Long> map = new HashMap<>();

    @Override
    public void put(String key, long transferredBytes) {
        map.put(key, transferredBytes);
    }

    @Override
    public void remove(String key) {
        map.remove(key);
    }

    /**
     * NOOP
     */
    @Override
    public void save(Map<String, Long> map) {

    }

    /**
     * NOOP
     */
    @Override
    public Map<String, Long> load() {
        return Collections.unmodifiableMap(map);
    }
}
