// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.node.admin.docker;

import com.yahoo.metrics.simple.MetricReceiver;
import com.yahoo.net.HostName;
import com.yahoo.vespa.defaults.Defaults;
import com.yahoo.vespa.hosted.dockerapi.ContainerName;
import com.yahoo.vespa.hosted.dockerapi.Docker;
import com.yahoo.vespa.hosted.dockerapi.DockerImage;
import com.yahoo.vespa.hosted.dockerapi.DockerTestUtils;
import com.yahoo.vespa.hosted.dockerapi.metrics.MetricReceiverWrapper;
import com.yahoo.vespa.hosted.node.admin.integrationTests.CallOrderVerifier;
import com.yahoo.vespa.hosted.node.admin.integrationTests.StorageMaintainerMock;
import com.yahoo.vespa.hosted.node.admin.nodeadmin.NodeAdminStateUpdater;
import com.yahoo.vespa.hosted.node.admin.provider.ComponentsProviderImpl;
import com.yahoo.vespa.hosted.node.admin.util.Environment;
import com.yahoo.vespa.hosted.node.admin.util.InetAddressResolver;
import com.yahoo.vespa.hosted.node.maintenance.Maintainer;
import com.yahoo.vespa.hosted.provision.Node;
import org.junit.Ignore;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Set;

import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * To get started:
 *  1. Add config-server and container nodes hostnames to /etc/hosts:
 *      $ sudo ./vespa/node-admin/scripts/etc-hosts.sh
 *  2. Set environmental variables in shell or e.g. ~/.bashrc:
 *      VESPA_HOME="/home/y"
 *      VESPA_WEB_SERVICE_PORT="4080"
 *      VESPA_BASE_IMAGE="<vespa image>"
 *  3. Create /home/docker/container-storage with read/write permissions
 *  4. Update {@link RunVespaLocal#appPath} to point to the application you want deployed
 *  5. Specify base image (see below) and download it with "docker pull <image>"
 *  6. Build node-admin with "mvn package"
 *  7. See com.yahoo.vespa.hosted.dockerapi.DockerTest in docker-api for how to make containers visible from Mac
 *
 *  Issues:
 *
 *  1. If you cannot make Docker Toolbox start, try starting Virtualbox and turn off the "default" machine
 *  2. If the above is not enough try "sudo ifconfig vboxnet0 down && sudo ifconfig vboxnet0 up" (see https://github.com/docker/kitematic/issues/1193)
 *  3. If you get an error with image vespa:local:latest, try building the image in node-admin folder manually: "docker build --tag vespa:local ."
 *
 * @author freva
 */
public class RunVespaLocal {
    private static final DockerImage vespaBaseImage = new DockerImage(System.getenv("VESPA_BASE_IMAGE"));
    private static final Environment environment = new Environment(
            Collections.singleton(LocalZoneUtils.CONFIG_SERVER_HOSTNAME), "prod", "vespa-local",
            HostName.getLocalhost(), new InetAddressResolver());
    private static final Maintainer maintainer = mock(Maintainer.class);
    private static Path appPath = Paths.get(System.getProperty("user.home") + "/dev/basic-search/target/application.zip");


    @Test
    @Ignore
    public void runVespaLocalTest() throws IOException, InterruptedException {
        System.out.println(Defaults.getDefaults().vespaHome());
        assumeTrue(DockerTestUtils.dockerDaemonIsPresent());

        DockerTestUtils.OS operatingSystem = DockerTestUtils.getSystemOS();
        if (operatingSystem == DockerTestUtils.OS.Mac_OS_X) {
            when(maintainer.pathInHostFromPathInNode(any(), any())).thenReturn(Paths.get("/tmp/"));
        } else {
            when(maintainer.pathInHostFromPathInNode(any(), any())).thenCallRealMethod();
        }
        when(maintainer.pathInNodeAdminToNodeCleanup(any())).thenReturn(Paths.get("/tmp"));
        when(maintainer.pathInNodeAdminFromPathInNode(any(), any())).thenAnswer(invocation -> {
            Object[] args = invocation.getArguments();
            return maintainer.pathInHostFromPathInNode((ContainerName) args[0], (String) args[1]);
        });

        Docker docker = DockerTestUtils.getDocker();
        LocalZoneUtils.buildVespaLocalDockerImage(docker, vespaBaseImage);
        assertTrue("Could not start config server", LocalZoneUtils.startConfigServerIfNeeded(docker, environment));

        NodeAdminStateUpdater nodeAdminStateUpdater = new ComponentsProviderImpl(docker,
                new MetricReceiverWrapper(MetricReceiver.nullImplementation),
                new StorageMaintainerMock(maintainer, new CallOrderVerifier()),
                environment).getNodeAdminStateUpdater();


        try {
            Set<String> hostnames = LocalZoneUtils.provisionNodes(HostName.getLocalhost(), 5);
            for (String hostname : hostnames) {
                try {
                    LocalZoneUtils.setState(Node.State.ready, hostname);
                } catch (RuntimeException e) {
                    System.err.println(e.getMessage());
                }
            }
        } catch (RuntimeException e) {
            System.err.println(e.getMessage());
        }

        LocalZoneUtils.prepareAppForDeployment(docker, appPath);

        while (true) {
            Thread.sleep(1000);
        }

//        nodeAdminStateUpdater.deconstruct();
    }
}