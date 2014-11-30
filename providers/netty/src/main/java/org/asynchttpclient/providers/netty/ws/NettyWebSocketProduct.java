package org.asynchttpclient.providers.netty.ws;

import java.util.List;
import org.asynchttpclient.HttpResponseBodyPart;
import org.asynchttpclient.websocket.WebSocketListener;
import org.asynchttpclient.websocket.WebSocketByteFragmentListener;
import org.asynchttpclient.providers.netty.NettyAsyncHttpProviderConfig;
import org.asynchttpclient.providers.netty.response.NettyResponseBodyPart;
import org.asynchttpclient.websocket.WebSocketTextFragmentListener;
import java.util.ArrayList;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Collection;

public class NettyWebSocketProduct {
	private final int maxBufferSize;
	private int bufferSize;
	private List<byte[]> _fragments;

	public NettyWebSocketProduct(NettyAsyncHttpProviderConfig nettyConfig) {
		maxBufferSize = nettyConfig.getWebSocketMaxBufferSize();
	}

	public int getMaxBufferSize() {
		return maxBufferSize;
	}

	public int getBufferSize() {
		return bufferSize;
	}

	public void setBufferSize(int bufferSize) {
		this.bufferSize = bufferSize;
	}

	public List<byte[]> get_fragments() {
		return _fragments;
	}

	public void set_fragments(List<byte[]> _fragments) {
		this._fragments = _fragments;
	}

	public void onBinaryFragment(HttpResponseBodyPart part,
			Collection<WebSocketListener> listeners,
			boolean interestedInByteMessages, NettyWebSocket nettyWebSocket) {
		for (WebSocketListener listener : listeners) {
			if (listener instanceof WebSocketByteFragmentListener)
				WebSocketByteFragmentListener.class.cast(listener).onFragment(
						part);
		}
		if (interestedInByteMessages) {
			byte[] fragment = NettyResponseBodyPart.class.cast(part)
					.getBodyPartBytes();
			if (part.isLast()) {
				if (bufferSize == 0) {
					nettyWebSocket.notifyByteListeners(fragment);
				} else {
					bufferFragment(fragment, nettyWebSocket);
					nettyWebSocket.notifyByteListeners(fragmentsBytes());
				}
				reset();
			} else
				bufferFragment(fragment, nettyWebSocket);
		}
	}

	public void onTextFragment(HttpResponseBodyPart part,
			Collection<WebSocketListener> listeners,
			boolean interestedInTextMessages, NettyWebSocket nettyWebSocket) {
		for (WebSocketListener listener : listeners) {
			if (listener instanceof WebSocketTextFragmentListener)
				WebSocketTextFragmentListener.class.cast(listener).onFragment(
						part);
		}
		if (interestedInTextMessages) {
			byte[] fragment = NettyResponseBodyPart.class.cast(part)
					.getBodyPartBytes();
			if (part.isLast()) {
				if (bufferSize == 0) {
					nettyWebSocket.notifyTextListeners(fragment);
				} else {
					bufferFragment(fragment, nettyWebSocket);
					nettyWebSocket.notifyTextListeners(fragmentsBytes());
				}
				reset();
			} else
				bufferFragment(fragment, nettyWebSocket);
		}
	}

	public void bufferFragment(byte[] buffer, NettyWebSocket nettyWebSocket) {
		bufferSize += buffer.length;
		if (bufferSize > maxBufferSize) {
			nettyWebSocket.onError(new Exception(
					"Exceeded Netty Web Socket maximum buffer size of "
							+ maxBufferSize));
			reset();
			nettyWebSocket.close();
		} else {
			fragments().add(buffer);
		}
	}

	public void reset() {
		fragments().clear();
		bufferSize = 0;
	}

	public List<byte[]> fragments() {
		if (_fragments == null)
			_fragments = new ArrayList<byte[]>(2);
		return _fragments;
	}

	public byte[] fragmentsBytes() {
		ByteArrayOutputStream os = new ByteArrayOutputStream(bufferSize);
		for (byte[] bytes : _fragments)
			try {
				os.write(bytes);
			} catch (IOException e) {
			}
		return os.toByteArray();
	}
}