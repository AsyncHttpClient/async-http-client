/*
 *    Copyright (c) 2026 AsyncHttpClient Project. All rights reserved.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.asynchttpclient.proxy;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

final class LocalProxyTestTarget {

    static final int HTTP_PORT = 80;
    static final int HTTPS_PORT = 443;
    static final String BODY_MARKER = "ahc-test-target";

    private LocalProxyTestTarget() {
    }

    static GenericContainer<?> start(Network network) {
        GenericContainer<?> target = new GenericContainer<>(DockerImageName.parse("nginx:1.27-alpine"))
                .withNetwork(network)
                .withNetworkAliases("ahc-test-target")
                .withCopyFileToContainer(MountableFile.forClasspathResource("proxy-target/nginx.conf", 0644), "/etc/nginx/nginx.conf")
                .withCopyFileToContainer(MountableFile.forClasspathResource("proxy-target/server.crt", 0644), "/etc/nginx/certs/server.crt")
                .withCopyFileToContainer(MountableFile.forClasspathResource("proxy-target/server.key", 0644), "/etc/nginx/certs/server.key")
                .withExposedPorts(HTTP_PORT, HTTPS_PORT)
                .waitingFor(Wait.forListeningPorts(HTTP_PORT, HTTPS_PORT));
        target.start();
        return target;
    }

    static String networkIpOf(GenericContainer<?> container) {
        return container.getContainerInfo().getNetworkSettings().getNetworks().values().stream()
                .map(n -> n.getIpAddress())
                .filter(ip -> ip != null && !ip.isEmpty())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("target container has no network IP"));
    }
}
