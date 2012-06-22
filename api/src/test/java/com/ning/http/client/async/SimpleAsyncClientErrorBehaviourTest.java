/*
 * Copyright (c) 2010-2012 Sonatype, Inc. All rights reserved.
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
package com.ning.http.client.async;

import static org.testng.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.Future;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.handler.AbstractHandler;
import org.testng.annotations.Test;

import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.Response;
import com.ning.http.client.SimpleAsyncHttpClient;
import com.ning.http.client.SimpleAsyncHttpClient.ErrorDocumentBehaviour;
import com.ning.http.client.consumers.OutputStreamBodyConsumer;

/**
 * @author Benjamin Hanzelmann
 *
 */
public class SimpleAsyncClientErrorBehaviourTest extends AbstractBasicTest {

    @Test(groups = {"standalone", "default_provider"})
    public void testAccumulateErrorBody() throws Throwable {
        SimpleAsyncHttpClient client = new SimpleAsyncHttpClient.Builder().setUrl(getTargetUrl() + "/nonexistent").setErrorDocumentBehaviour( ErrorDocumentBehaviour.ACCUMULATE ).build();
    
        ByteArrayOutputStream o = new ByteArrayOutputStream(10);
        Future<Response> future = client.get(new OutputStreamBodyConsumer(o));
    
        System.out.println("waiting for response");
        Response response = future.get();
        assertEquals(response.getStatusCode(), 404);
        assertEquals(o.toString(), "");
        assertTrue(response.getResponseBody().startsWith("<html>"));
    
        client.close();
    }

    @Test(groups = {"standalone", "default_provider"})
    public void testOmitErrorBody() throws Throwable {
        SimpleAsyncHttpClient client = new SimpleAsyncHttpClient.Builder().setUrl(getTargetUrl() + "/nonexistent").setErrorDocumentBehaviour( ErrorDocumentBehaviour.OMIT ).build();
    
        ByteArrayOutputStream o = new ByteArrayOutputStream(10);
        Future<Response> future = client.get(new OutputStreamBodyConsumer(o));
    
        System.out.println("waiting for response");
        Response response = future.get();
        assertEquals(response.getStatusCode(), 404);
        assertEquals(o.toString(), "");
        assertEquals(response.getResponseBody(), "");
        client.close();
    }

    @Override
    public AsyncHttpClient getAsyncHttpClient( AsyncHttpClientConfig config )
    {
        // disabled
        return null;
    }

    @Override
    public AbstractHandler configureHandler()
        throws Exception
    {
        return new AbstractHandler() {
    
            public void handle( String target, org.eclipse.jetty.server.Request baseRequest,
                                HttpServletRequest request, HttpServletResponse response )
                throws IOException, ServletException
            {
                response.sendError( 404 );
                baseRequest.setHandled( true );
            }
        };
    }

}
