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
package com.ning.http.client;

import com.ning.http.client.Realm.AuthScheme;
import com.ning.http.client.Realm.RealmBuilder;
import org.testng.Assert;
import java.math.BigInteger;
import java.security.MessageDigest;

import org.testng.annotations.Test;

public class RealmTest {
    @Test(groups = "fast")
    public void testClone() {
        RealmBuilder builder = new RealmBuilder();
        builder.setPrincipal( "user" ).setPassword( "pass" );
        builder.setEnconding( "enc" ).setUsePreemptiveAuth( true );
        builder.setRealmName( "realm" ).setAlgorithm( "algo" );
        builder.setScheme( AuthScheme.BASIC );
        Realm orig = builder.build();
        
        Realm clone = new RealmBuilder().clone( orig ).build();
        Assert.assertEquals( clone.getPrincipal(), orig.getPrincipal() );
        Assert.assertEquals( clone.getPassword(), orig.getPassword() );
        Assert.assertEquals( clone.getEncoding(), orig.getEncoding() );
        Assert.assertEquals( clone.getUsePreemptiveAuth(), orig.getUsePreemptiveAuth() );
        Assert.assertEquals( clone.getRealmName(), orig.getRealmName() );
        Assert.assertEquals( clone.getAlgorithm(), orig.getAlgorithm() );
        Assert.assertEquals( clone.getAuthScheme(), orig.getAuthScheme() );
    }
    @Test(groups = "fast")
    public void testOldDigestEmptyString() {
        String qop="";
        testOldDigest(qop);
    }
    @Test(groups = "fast")
    public void testOldDigestNull() {
        String qop=null;
        testOldDigest(qop);
    }

    private void testOldDigest(String qop){
        String user="user";
        String pass="pass";
        String realm="realm";
        String nonce="nonce";
        String method="GET";
        String uri="/foo";
        RealmBuilder builder = new RealmBuilder();
        builder.setPrincipal( user ).setPassword( pass );
        builder.setNonce( nonce );
        builder.setUri( uri );
        builder.setMethodName(method);
        builder.setRealmName( realm );
        builder.setQop(qop);
        builder.setScheme( AuthScheme.DIGEST );
        Realm orig = builder.build();

        String ha1=getMd5(user +":" + realm +":"+pass);
        String ha2=getMd5(method +":"+ uri);
        String expectedResponse=getMd5(ha1 +":" + nonce +":"  + ha2);

        Assert.assertEquals(expectedResponse,orig.getResponse());
    }

    @Test(groups = "fast")
    public void testStrongDigest() {
        String user="user";
        String pass="pass";
        String realm="realm";
        String nonce="nonce";
        String method="GET";
        String uri="/foo";
        String qop="auth";
        RealmBuilder builder = new RealmBuilder();
        builder.setPrincipal( user ).setPassword( pass );
        builder.setNonce( nonce );
        builder.setUri( uri );
        builder.setMethodName(method);
        builder.setRealmName( realm );
        builder.setQop(qop);
        builder.setScheme( AuthScheme.DIGEST );
        Realm orig = builder.build();

        String nc = orig.getNc();
        String cnonce = orig.getCnonce();
        String ha1=getMd5(user +":" + realm +":"+pass);
        String ha2=getMd5(method +":"+ uri);
        String expectedResponse=getMd5(ha1 +":" + nonce +":" + nc + ":" + cnonce +":" + qop + ":" + ha2);

        Assert.assertEquals(expectedResponse,orig.getResponse());
    }

    private String getMd5(String what){
            try {
            MessageDigest md = MessageDigest.getInstance("MD5");
                md.update(what.getBytes("ISO-8859-1"));
                byte[] hash  = md.digest();
                BigInteger bi = new BigInteger(1, hash);
                String result = bi.toString(16);
                if (result.length() % 2 != 0) {
                    return "0" + result;
                }
                return result;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
    }
}
