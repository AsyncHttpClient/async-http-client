package com.ning.http.client.providers.grizzly;

import com.ning.http.client.RequestBuilder;
import com.ning.http.multipart.MultipartEncodingUtil;
import com.ning.http.multipart.MultipartRequestEntity;
import com.ning.http.multipart.Part;
import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.memory.HeapBuffer;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * <p>
 * An utility class that permits to easily send a multipart request body to a {@link FeedableBodyGenerator}.
 * </p>
 *
 * <p>
 * Once the multipart {@link Part} are added and the call to {@link #feed()} done, the multipart body will be sent to an {@link OutputStream}
 * that feeds the {@link FeedableBodyGenerator} with chunks of data.
 * </p>
 *
 * <p>
 * The {@link #bufferSize} represents the size of the multiple data chunks {@link Buffer} that will be sent to the {@link FeedableBodyGenerator}.
 * Please not that it is not enough to guarantee a low memory consumption (particularly for multipart file uploads).
 * Using a blocking FeedableBodyGenerator may help: {@link FeedableBodyGenerator#FeedableBodyGenerator(java.util.Queue<BodyPart>)}
 * </p>
 *
 * <p>
 * One must provide the {@link #multipartBoundary} bytes to use. This boundary must be the same than the boundary bytes set in the Content-Type
 * header of the request. Note that a boundary can be generated with {@link MultipartRequestEntity#generateMultipartBoundary()}.
 * The easiest way to set the same {@link #multipartBoundary} in both the header and body is probably to use the
 * {@link #create(RequestBuilder)} method which will prepare the request and body generator for you.
 * </p>
 *
 * <p>Here you fill find a basic usage of this class:</p>
 * <code>
 * RequestBuilder requestBuilder = new RequestBuilder("POST").setUrl("http://myUrl/multipartUploadEndpoint");
 *
 * MultipartBodyGeneratorFeeder bodyFeeder = MultipartBodyGeneratorFeeder.create(requestBuilder);
 *
 * Request request = requestBuilder.build();
 *
 * ListenableFuture<Response> asyncRes = asyncHttpClient
 *           .prepareRequest(request)
 *           .execute(new AsyncCompletionHandlerBase());
 *
 * bodyFeeder.addBodyPart(new StringPart("param1", "x"))
 *           .addBodyPart(new StringPart("param2", "y"))
 *           .addBodyPart(new StringPart("param3", "z"))
 *           .addBodyPart(new FilePart("file", inputStream))
 *           .feed();
 *
 * Response uploadResponse = asyncRes.get();
 * </code>
 *
 * @author Lorber Sebastien <i>(lorber.sebastien@gmail.com)</i>
 */
public class MultipartBodyGeneratorFeeder {

    private static final int DEFAULT_BUFFER_SIZE = 100000; // a low buffer size would create unnecessary garbage
    private static final int DEFAULT_QUEUE_SIZE = 3; // 3 seems a nice default value since a queue rather empty or full in most cases


    protected final List<Part> parts = new ArrayList<Part>();
    protected final FeedableBodyGenerator feedableBodyGenerator;
    protected final int bufferSize;
    protected final byte[] multipartBoundary;

    public MultipartBodyGeneratorFeeder(FeedableBodyGenerator feedableBodyGenerator,byte[] multipartBoundary) {
        this(feedableBodyGenerator,multipartBoundary,DEFAULT_BUFFER_SIZE);
    }


    public MultipartBodyGeneratorFeeder(FeedableBodyGenerator feedableBodyGenerator,byte[] multipartBoundary,int bufferSize) {
        if ( feedableBodyGenerator == null ) {
            throw new IllegalArgumentException("The feedableBodyGenerator is required");
        }
        if ( multipartBoundary == null || multipartBoundary.length == 0 ) {
            throw new IllegalArgumentException("The multipartBoundary is required and must also be set as a ContentType header of your request");
        }
        if ( bufferSize < 1 ) {
            throw new IllegalArgumentException("The bufferSize can't be < 1");
        }
        this.feedableBodyGenerator = feedableBodyGenerator;
        this.multipartBoundary = multipartBoundary;
        this.bufferSize = bufferSize;
    }


    /**
     * Static factory method to create a MultipartBodyGeneratorFeeder for a given request.
     * This will set the multipart header and the feedableBodyGenerator (this is why we need the {@link RequestBuilder})
     * and use the same multipart boundary to generate the multipart body.
     *
     * @param requestBuilder
     * @param feedableBodyGenerator
     * @param bufferSize
     * @return
     */
    public static MultipartBodyGeneratorFeeder create(RequestBuilder requestBuilder,FeedableBodyGenerator feedableBodyGenerator,int bufferSize) {
        // We generate a common multipart boundary that will be used in the request header and the multipart body
        byte[] boundary = MultipartRequestEntity.generateMultipartBoundary();
        String boundaryString = MultipartEncodingUtil.getAsciiString(boundary);
        String contentTypeHeader = "multipart/form-data; boundary="+boundaryString;
        requestBuilder.addHeader("Content-Type",contentTypeHeader);
        requestBuilder.setBody(feedableBodyGenerator);
        return new MultipartBodyGeneratorFeeder(feedableBodyGenerator,boundary,bufferSize);
    }

    /**
     * Prepare a multipart request feeder with a bounded queue and chunks of data sent with a given size (not http chunks)
     * @param requestBuilder
     * @param maxQueueSize
     * @param bufferSize
     * @return
     */
    public static MultipartBodyGeneratorFeeder create(RequestBuilder requestBuilder,int maxQueueSize,int bufferSize) {
        FeedableBodyGenerator feedableBodyGenerator = new FeedableBodyGenerator(maxQueueSize);
        return create(requestBuilder,feedableBodyGenerator,bufferSize);
    }


    /**
     * This is the easiest way to use the {@link MultipartBodyGeneratorFeeder}.
     * This will prepare the request with a multipart Content-Type header, a {@link FeedableBodyGenerator}.
     *
     * The returned {@link MultipartBodyGeneratorFeeder} will feed the the generator and will block the feeding
     * if more than 10 chunks of data of 8192 bytes are already waiting be to written.
     * This ensures a relatively low memory consumption and could be an useful default config for file uploads.
     *
     * @param requestBuilder
     * @return
     */
    public static MultipartBodyGeneratorFeeder create(RequestBuilder requestBuilder) {
        FeedableBodyGenerator feedableBodyGenerator = new FeedableBodyGenerator(DEFAULT_QUEUE_SIZE);
        return create(requestBuilder,feedableBodyGenerator,DEFAULT_BUFFER_SIZE);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////////


    /**
     * Add a multipart {@link Part} to this feeder
     * @param part
     * @return
     */
    public MultipartBodyGeneratorFeeder addBodyPart(Part part) {
        parts.add(part);
        return this;
    }

    /**
     * Feeds the {@link FeedableBodyGenerator}.
     * This is where the real feeding happen, and this may block the calling thread for some time.
     * @throws IOException
     */
    public void feed() throws IOException {
        Part[] partsArray = parts.toArray(new Part[parts.size()]);
        OutputStream outputStream = null;
        IOException e = null;
        try {
            outputStream = createFeedingOutputStream();
            Part.sendParts(outputStream,partsArray,multipartBoundary);
        } catch ( IOException catched ) {
            e = catched;
            throw e;
        }
        finally {
            closeGracefully(outputStream,e);
        }
    }

    protected OutputStream createFeedingOutputStream() {
        return new BufferedOutputStream(new FeedBodyGeneratorOutputStream(feedableBodyGenerator),bufferSize);
    }

    // This is what JDK7 does with TryWithResource
    protected void closeGracefully(OutputStream outputStream,IOException e) throws IOException {
        if (outputStream != null) {
            if (e != null) {
                try {
                    outputStream.close();
                } catch (IOException closeE) {
                    // e.addSuppressed(closeE); // available only with JDK7
                }
            } else {
                outputStream.close();
            }
        }
    }


    /**
     * This is an {@link OutputStream} implementation that transform every single call to one of the write method,
     * to a {@link Buffer} that is sent to the {@link FeedableBodyGenerator#feed(org.glassfish.grizzly.Buffer, boolean)}
     * method of the underlying {@link FeedableBodyGenerator}.
     *
     * As every call to a write method creates a new Buffer (even when you write a single byte), it is very recommended to wrap this
     * {@link OutputStream} by a {@link BufferedOutputStream} to guarantee that the bytes will be sent by chunks to the {@link FeedableBodyGenerator}
     */
    public static class FeedBodyGeneratorOutputStream extends OutputStream {

        private final FeedableBodyGenerator feedableBodyGenerator;
        private final boolean sendIsLastOnClose;
        private final ByteArrayToBufferStrategy byteArrayToBufferStrategy;

        public FeedBodyGeneratorOutputStream(FeedableBodyGenerator feedableBodyGenerator,ByteArrayToBufferStrategy byteArrayToBufferStrategy,boolean sendIsLastOnClose) {
            if ( feedableBodyGenerator == null ) {
                throw new IllegalArgumentException("The feedableBodyGenerator is required");
            }
            if ( byteArrayToBufferStrategy == null ) {
                throw new IllegalArgumentException("The byteArrayToBufferStrategy is required");
            }
            this.feedableBodyGenerator = feedableBodyGenerator;
            this.byteArrayToBufferStrategy = byteArrayToBufferStrategy;
            this.sendIsLastOnClose = sendIsLastOnClose;
        }

        public FeedBodyGeneratorOutputStream(FeedableBodyGenerator feedableBodyGenerator) {
            this(feedableBodyGenerator,ByteArrayToBufferStrategy.DEFAULT,true);
        }

        @Override
        public void write(int b) throws IOException {
            byte[] arr = new byte[]{(byte)b};
            write(arr);
        }
        @Override
        public void write(byte b[]) throws IOException {
            write(b,0,b.length);
        }
        @Override
        public void write(byte b[], int off, int len) throws IOException {
            checkParamConsistency(b,off,len);
            if (len == 0) {
                return;
            }
            byte[] bytesToWrite = new byte[len];
            System.arraycopy(b,off,bytesToWrite,0,len);
            Buffer buffer = byteArrayToBufferStrategy.wrap(bytesToWrite);
            feedableBodyGenerator.feed(buffer,false);
        }

        @Override
        public void close() throws IOException {
            if ( sendIsLastOnClose ) {
                sendIsLastSignal();
            }
        }

        protected void sendIsLastSignal() throws IOException {
            feedableBodyGenerator.isLast();
        }

        // Content copied from the OutputStream class
        private void checkParamConsistency(byte b[], int off, int len) throws IOException {
            if (b == null) {
                throw new NullPointerException();
            } else if ((off < 0) || (off > b.length) || (len < 0) ||
                    ((off + len) > b.length) || ((off + len) < 0)) {
                throw new IndexOutOfBoundsException();
            }
        }
    }

    /**
     * The strategy to use to convert the byte[] received by the {@link FeedBodyGeneratorOutputStream} to a {@link Buffer}
     */
    public interface ByteArrayToBufferStrategy {
        Buffer wrap(byte[] bytes);

        ByteArrayToBufferStrategy DEFAULT = new ByteArrayToBufferStrategy() {
            @Override
            public Buffer wrap(byte[] bytes) {
                return HeapBuffer.wrap(bytes);
            }
        };
    }

}



