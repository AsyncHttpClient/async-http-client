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
 *
 */
package ning.http.client;

import ning.http.collection.Pair;

import java.util.Iterator;

public class ImmutableHeaders extends Headers {
    public ImmutableHeaders(Headers headers) {
        initHeaders(headers);
    }

    private void initHeaders(Headers headers) {
        super.addAll(headers);
    }

    @Override
    public Headers add(String name, String value) {
        throw new UnsupportedOperationException("Headers are immutable");
    }

    @Override
    public Headers addAll(Headers srcHeaders) {
        throw new UnsupportedOperationException("Headers are immutable");
    }

    @Override
    public Headers addVisitTracking(boolean enabled) {
        throw new UnsupportedOperationException("Headers are immutable");
    }

    @Override
    public Iterator<Pair<String, String>> iterator() {
        final Iterator<Pair<String, String>> iter = super.iterator();
        return new Iterator<Pair<String, String>>() {
            public boolean hasNext() {
                return iter.hasNext();
            }

            public Pair<String, String> next() {
                return iter.next();
            }

            public void remove() {
                throw new UnsupportedOperationException("Headers are immutable");
            }
        };
    }
}
