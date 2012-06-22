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

import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.providers.jdk.JDKAsyncHttpProvider;

public class ProviderUtil {


    public static AsyncHttpClient nettyProvider(AsyncHttpClientConfig config) {
        if (config == null) {
            return new AsyncHttpClient();
        } else {
            return new AsyncHttpClient(config);
        }
    }

    public static AsyncHttpClient jdkProvider(AsyncHttpClientConfig config) {
        if (config == null) {
            return new AsyncHttpClient(new JDKAsyncHttpProvider(new AsyncHttpClientConfig.Builder().build()));
        } else {
            return new AsyncHttpClient(new JDKAsyncHttpProvider(config));
        }
    }

}
