package com.ning.http.client.async;

import com.ning.http.client.AsyncCompletionHandlerBase;
import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.HttpResponseBodyPart;
import com.ning.http.client.RequestBuilder;
import com.ning.http.client.Response;
import com.ning.http.client.extra.ResumableRandomAccessFileListener;
import com.ning.http.client.resumable.PropertiesBasedResumableProcessor;
import com.ning.http.client.resumable.ResumableAsyncHandler;
import com.ning.http.client.resumable.ResumableListener;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.testng.annotations.Test;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.testng.Assert.assertEquals;

/**
 * @author Benjamin Hanzelmann
 */
public class ResumableFileTest
    extends AbstractBasicTest
{
    private static final File TMP = new File( System.getProperty( "java.io.tmpdir" ),
                                              "ahc-tests-" + UUID.randomUUID().toString().substring( 0, 8 ) );

    private int counter = 0;

    @Override
    public AbstractHandler configureHandler()
        throws Exception
    {
        return new AbstractHandler()
        {

            public void handle( String arg0, Request arg1, HttpServletRequest req, HttpServletResponse resp )
                throws IOException, ServletException
            {

                counter++;

                resp.setStatus( 200 );
                resp.setContentLength( 15 );
                for ( int i = 0; i < counter; i++ )
                {
                    resp.getOutputStream().write( ( "TEST" + counter ).getBytes() );
                }
                resp.getOutputStream().flush();
                resp.getOutputStream().close();

                arg1.setHandled( true );

            }
        };
    }

    @Override
    public AsyncHttpClient getAsyncHttpClient( AsyncHttpClientConfig config )
    {
        return null;
    }

    @Test( groups = { "standalone", "default_provider" }, enabled = false )
    public void basicTest()
        throws Throwable
    {

        AsyncHttpClient c = new AsyncHttpClient();
        ResumableAsyncHandler<Response> a =
            new ResumableAsyncHandler<Response>( new PropertiesBasedResumableProcessor() );
        a.setResumableListener( new ResumableRandomAccessFileListener( new RandomAccessFile( "shrek1.avi", "rw" ) ) );

        Response r = c.prepareGet( "http://192.168.2.106:8081/shrek1.AVI" ).execute( a ).get();

        assertEquals( r.getStatusCode(), 200 );
    }

   @Test( groups = { "standalone", "default_provider" }, enabled = false )
    public void basicResumeListenerTest()
        throws Throwable
    {

        AsyncHttpClient c = new AsyncHttpClient();
        final RandomAccessFile file = new RandomAccessFile( "file.avi", "rw" );
        ResumableAsyncHandler<Response> a = new ResumableAsyncHandler<Response>();
        a.setResumableListener( new ResumableListener() {

            public void onBytesReceived(ByteBuffer byteBuffer) throws IOException {
                file.seek( file.length() );
                file.write( byteBuffer.array() );
            }

            public void onAllBytesReceived() {
                try {
                    file.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }

            public long length() {
                try {
                    return file.length();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                return 0;
            }

        } );

        Response r = c.prepareGet( "http://192.168.2.106:8081/shrek1.AVI" ).execute( a ).get();

        assertEquals( r.getStatusCode(), 200 );
    }

    @Test( groups = { "standalone", "default_provider" }, enabled = false)
    public void basicDefaultTest()
        throws Throwable
    {
        final AsyncHttpClient c = new AsyncHttpClient();
        final CountDownLatch latch = new CountDownLatch( 1 );

        final RandomAccessFile file = new RandomAccessFile( "shrek1.avi", "rw" );
        final com.ning.http.client.Request request = c.prepareGet( "http://192.168.2.106:8082/shrek1.AVI" ).setRangeOffset( file.length() ).build();
        final AtomicReference<Response> response = new AtomicReference<Response>();

        c.executeRequest(request,
            new AsyncCompletionHandlerBase()
            {

                public void onThrowable(Throwable t) {

                    if (IOException.class.isAssignableFrom( t.getClass() )) {

                        try
                        {
                            com.ning.http.client.Request newRequest = new RequestBuilder( request ).setRangeOffset( file.length() ).build();
                            c.executeRequest( newRequest, this );
                        }
                        catch ( IOException e )
                        {
                            e.printStackTrace();
                        }

                    }
                }

                /* @Override */
                public STATE onBodyPartReceived( final HttpResponseBodyPart content )
                    throws Exception
                {

                    file.seek( file.length() );
                    file.write( content.getBodyPartBytes() );

                    return STATE.CONTINUE;
                }

                public Response onCompleted(Response r) throws Exception {
                    response.set(r);
                    latch.countDown();
                    return super.onCompleted(r);
                }

            } );

        latch.await();
        assertEquals( response.get().getStatusCode(), 206 );
        file.close();
    }

}
