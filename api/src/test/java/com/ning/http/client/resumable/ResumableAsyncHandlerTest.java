package com.ning.http.client.resumable;

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

import com.ning.http.client.Request;
import com.ning.http.client.RequestBuilder;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;

/**
 * @author Benjamin Hanzelmann
 */
public class ResumableAsyncHandlerTest {
    @Test
    public void testAdjustRange() {
        MapResumableProcessor proc = new MapResumableProcessor();

        ResumableAsyncHandler h = new ResumableAsyncHandler(proc);
        Request request = new RequestBuilder("GET").setUrl("http://test/url").build();
        Request newRequest = h.adjustRequestRange(request);
        assertEquals(newRequest.getUrl(), request.getUrl());
        String rangeHeader = newRequest.getHeaders().getFirstValue("Range");
        assertNull(rangeHeader);

        proc.put("http://test/url", 5000);
        newRequest = h.adjustRequestRange(request);
        assertEquals(newRequest.getUrl(), request.getUrl());
        rangeHeader = newRequest.getHeaders().getFirstValue("Range");
        assertEquals(rangeHeader, "bytes=5000-");
    }
}
