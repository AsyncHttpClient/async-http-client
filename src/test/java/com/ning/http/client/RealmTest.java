/*
 * Copyright (c) 2010-2011 Sonatype, Inc. All rights reserved.
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
}
