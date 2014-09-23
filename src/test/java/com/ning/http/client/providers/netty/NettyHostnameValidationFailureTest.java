/*
 * Copyright 2014 AsyncHttpClient Project
 *
 * AsyncHttpClient Project licenses this file to you under the Apache License, version 2.0
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
package com.ning.http.client.providers.netty;

import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.Response;
import org.jboss.netty.logging.InternalLoggerFactory;
import org.jboss.netty.logging.Slf4JLoggerFactory;
import org.testng.annotations.Test;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLSession;
import java.io.IOException;
import java.net.ConnectException;
import java.util.concurrent.ExecutionException;

import static org.testng.Assert.*;

/**
 *
 */
public class NettyHostnameValidationFailureTest {

    @Test(groups = "standalone")
    public void testHostnameValidationFailure()  {
        //InternalLoggerFactory.setDefaultFactory(new Slf4JLoggerFactory());

        AsyncHttpClientConfig.Builder builder = new AsyncHttpClientConfig.Builder()//
                .setAllowPoolingConnection(true)//
                .setHostnameVerifier(new HostnameVerifier() {
                    public boolean verify(String arg0, SSLSession arg1) {
                        return false;
                    }
                })
                .setConnectionTimeoutInMs(60000);
        AsyncHttpClient client = new AsyncHttpClient(builder.build());

        try {
            client.prepareGet("https://www.google.fr").execute().get();
            fail("Should have thrown an exception");
        } catch (ExecutionException e) {
            assertTrue(e.getMessage().contains("HostnameVerifier exception."));
        } catch (Exception e) {
            fail(e.getClass().getCanonicalName());
        }  finally {
            client.close();
        }
    }
}
