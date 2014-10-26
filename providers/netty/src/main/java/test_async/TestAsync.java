package test_async;

import org.asynchttpclient.*;
import org.asynchttpclient.providers.*;
import org.asynchttpclient.providers.netty.NettyAsyncHttpProvider;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

public class TestAsync {

	public static void main(String[] args) {
		testSync();
		testAsync();
		WebSocketClient.testWebSocketClient();
	}
	
	public static void testSync() {
		AsyncHttpClientConfig config = new AsyncHttpClientConfig.Builder().build();
		AsyncHttpClient asyncHttpClient = new DefaultAsyncHttpClient(new NettyAsyncHttpProvider(config), config);
		Future<Response> f = asyncHttpClient.prepareGet("http://www.google.com").execute();
		try {
			Response r = f.get();
			System.out.println("HTTP CODE: " +  r.getStatusText());
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (ExecutionException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		asyncHttpClient.close();
	}
	
	public static void testAsync() {
		AsyncHttpClientConfig config = new AsyncHttpClientConfig.Builder().build();
		AsyncHttpClient asyncHttpClient = new DefaultAsyncHttpClient(new NettyAsyncHttpProvider(config), config);
		asyncHttpClient.prepareGet("http://www.google.com").execute(new AsyncCompletionHandler<Response>() {
			@Override
			public Response onCompleted(Response response) throws Exception{
				System.out.println("HTTP CODE: " +  response.getStatusText());
				System.out.println("Async done.");
				return response;
			}
			
			@Override
			public void onThrowable(Throwable t) {
				System.out.println("Async failed.");
			}
		});
	}

}