package org.asynchttpclient.providers.netty3.handler;

import org.asynchttpclient.uri.Uri;

public class Goo {

    public static void main(String[] args) {

        Uri foo = Uri.create("http://foo.com/bar?hello=world");
        Uri bar = Uri.create(foo, "/bar?foo=bar");
        
        System.err.println(bar.toUrl());
    }

}
