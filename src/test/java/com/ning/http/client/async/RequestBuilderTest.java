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
 */
package com.ning.http.client.async;

import com.ning.http.client.Request;
import com.ning.http.client.RequestBuilder;
import com.ning.http.client.RequestType;
import org.testng.annotations.Test;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

import static java.lang.String.format;
import static org.testng.Assert.assertEquals;

public class RequestBuilderTest {
    @Test(groups = "standalone")
    public void testEncodesQueryParameters() throws UnsupportedEncodingException
    {
        String value = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKQLMNOPQRSTUVWXYZ1234567809`~!@#$%^&*()_+-=,.<>/?;:'\"[]{}\\| ";
        RequestBuilder builder = new RequestBuilder(RequestType.GET).
                setUrl("http://example.com/").
                addQueryParameter("name", value);

        Request request = builder.build();
        assertEquals(request.getUrl(), format("http://example.com/?name=%s", URLEncoder.encode(value, "UTF-8").replace("+", "%20")));
    }
}
