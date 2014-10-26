package test_async;

import java.util.concurrent.ExecutionException;

import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.DefaultAsyncHttpClient;
import org.asynchttpclient.providers.netty.NettyAsyncHttpProvider;
import org.asynchttpclient.websocket.WebSocket;
import org.asynchttpclient.websocket.WebSocketTextListener;
import org.asynchttpclient.websocket.WebSocketUpgradeHandler;

public class WebSocketClient {
	
	public static void testWebSocketClient() {
		System.out.println("Start WS");
		WebSocketClient wsc = new WebSocketClient();
		wsc.WSClient("wassup");
		wsc.WSClient("bye");
		System.out.println("End WS");
	}
	
	static final AsyncHttpClient c = new DefaultAsyncHttpClient();
	
	public static void WSClient(String toWS) {
		
		final String ws_url = "ws://localhost:9090/ws";
		/*AsyncHttpClientConfig config = new AsyncHttpClientConfig.Builder().build();
		AsyncHttpClient c = new DefaultAsyncHttpClient(new NettyAsyncHttpProvider(config), config);*/
		
		final String localMessage = toWS;
		try {
			WebSocket websocket = c.prepareGet(ws_url)
			      .execute(new WebSocketUpgradeHandler.Builder().addWebSocketListener(
			          new WebSocketTextListener() {
			
			          @Override
			          public void onMessage(String message) {
			        	  System.out.println("Received " + message);
			        	  if (message.contains("bye")) {
			        		  c.close();
			        	  }
			          }
			
			          @Override
			          public void onOpen(WebSocket websocket) {
			        	  System.out.println("Sending");
			              websocket.sendMessage(localMessage);
			          }
			
				      @Override
				      public void onError(Throwable t) {
				    	  System.out.println("error");
				      }

					  @Override
					  public void onClose(WebSocket websocket) {
						  websocket.close();
						  System.out.println("Closing");
					  }
				
			  }).build()).get();
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			c.close();
		} catch (ExecutionException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			c.close();
		} 
	}
}
