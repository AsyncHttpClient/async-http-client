package com.ning.http.client.extra;

import com.ning.http.client.HttpResponseBodyPart;

public class HttpResponseBodyPartProgress implements HttpProgress {
	private final HttpResponseBodyPart part;

	public HttpResponseBodyPartProgress(HttpResponseBodyPart part) {
		this.part = part;
	}

	public HttpResponseBodyPart getPart() {
		return part;
	}
	
	@Override
	public String toString() {
		return "HttpResponseBodyPartProgress [part=" + part + "]";
	}
}
