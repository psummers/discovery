/*
 * Copyright 2010 Proofpoint, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.proofpoint.discovery;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.Files;
import com.google.inject.Binder;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Module;
import com.proofpoint.configuration.ConfigurationFactory;
import com.proofpoint.configuration.ConfigurationModule;
import com.proofpoint.discovery.client.DiscoveryAnnouncementClient;
import com.proofpoint.discovery.client.DiscoveryLookupClient;
import com.proofpoint.discovery.client.DiscoveryModule;
import com.proofpoint.discovery.client.ServiceAnnouncement;
import com.proofpoint.discovery.client.ServiceDescriptor;
import com.proofpoint.discovery.client.ServiceSelector;
import com.proofpoint.discovery.client.ServiceSelectorConfig;
import com.proofpoint.discovery.client.testing.SimpleServiceSelector;
import com.proofpoint.http.client.ApacheHttpClient;
import com.proofpoint.http.client.FullJsonResponseHandler.JsonResponse;
import com.proofpoint.http.client.HttpClient;
import com.proofpoint.http.client.Request;
import com.proofpoint.http.client.StatusResponseHandler.StatusResponse;
import com.proofpoint.http.server.testing.TestingHttpServer;
import com.proofpoint.http.server.testing.TestingHttpServerModule;
import com.proofpoint.jaxrs.JaxrsModule;
import com.proofpoint.json.JsonModule;
import com.proofpoint.node.NodeInfo;
import com.proofpoint.node.NodeModule;
import org.iq80.leveldb.util.FileUtils;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
import org.weakref.jmx.guice.MBeanModule;

import javax.management.MBeanServer;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import java.io.File;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.util.List;
import java.util.Map;

import static com.proofpoint.http.client.FullJsonResponseHandler.createFullJsonResponseHandler;
import static com.proofpoint.http.client.JsonBodyGenerator.jsonBodyGenerator;
import static com.proofpoint.http.client.Request.Builder.prepareDelete;
import static com.proofpoint.http.client.Request.Builder.preparePost;
import static com.proofpoint.http.client.StatusResponseHandler.createStatusResponseHandler;
import static com.proofpoint.json.JsonCodec.jsonCodec;
import static com.proofpoint.json.JsonCodec.mapJsonCodec;
import static javax.ws.rs.core.Response.Status;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

public class TestDiscoveryServer
{
    private TestingHttpServer server;
    private File tempDir;

    @BeforeMethod
    public void setup()
            throws Exception
    {
        tempDir = Files.createTempDir();

        // start server
        Map<String, String> serverProperties = ImmutableMap.<String, String>builder()
                    .put("node.environment", "testing")
                    .put("static.db.location", tempDir.getAbsolutePath())
                    .build();

        Injector serverInjector = Guice.createInjector(
                new MBeanModule(),
                new NodeModule(),
                new TestingHttpServerModule(),
                new JsonModule(),
                new JaxrsModule(),
                new DiscoveryServerModule(),
                new DiscoveryModule(),
                new ConfigurationModule(new ConfigurationFactory(serverProperties)),
                new Module() {
                    public void configure(Binder binder)
                    {
                        // TODO: use testing mbean server
                        binder.bind(MBeanServer.class).toInstance(ManagementFactory.getPlatformMBeanServer());
                    }
                });

        server = serverInjector.getInstance(TestingHttpServer.class);
        server.start();
    }

    @AfterMethod
    public void teardown()
            throws Exception
    {
        server.stop();
        FileUtils.deleteRecursively(tempDir);
    }

    @Test
    public void testDynamicAnnouncement()
            throws Exception
    {
        // publish announcement
        Map<String, String> announcerProperties = ImmutableMap.<String, String>builder()
            .put("node.environment", "testing")
            .put("node.pool", "red")
            .put("discovery.uri", server.getBaseUrl().toString())
            .build();

        Injector announcerInjector = Guice.createInjector(
                new NodeModule(),
                new JsonModule(),
                new ConfigurationModule(new ConfigurationFactory(announcerProperties)),
                new com.proofpoint.discovery.client.DiscoveryModule()
        );

        ServiceAnnouncement announcement = ServiceAnnouncement.serviceAnnouncement("apple")
                .addProperties(ImmutableMap.of("key", "value"))
                .build();

        DiscoveryAnnouncementClient client = announcerInjector.getInstance(DiscoveryAnnouncementClient.class);
        client.announce(ImmutableSet.of(announcement)).get();

        NodeInfo announcerNodeInfo = announcerInjector.getInstance(NodeInfo.class);

        List<ServiceDescriptor> services = selectorFor("apple", "red").selectAllServices();
        assertEquals(services.size(), 1);

        ServiceDescriptor service = services.get(0);
        assertNotNull(service.getId());
        assertEquals(service.getNodeId(), announcerNodeInfo.getNodeId());
        assertEquals(service.getLocation(), announcerNodeInfo.getLocation());
        assertEquals(service.getPool(), announcerNodeInfo.getPool());
        assertEquals(service.getProperties(), announcement.getProperties());


        // ensure that service is no longer visible
        client.unannounce().get();

        assertTrue(selectorFor("apple", "red").selectAllServices().isEmpty());
    }


    @Test
    public void testStaticAnnouncement()
            throws Exception
    {
        // create static announcement
        Map<String, Object> announcement = ImmutableMap.<String, Object>builder()
                .put("environment", "testing")
                .put("type", "apple")
                .put("pool", "red")
                .put("location", "/a/b/c")
                .put("properties", ImmutableMap.of("http", "http://host"))
                .build();

        HttpClient client = new ApacheHttpClient();

        Request request = preparePost()
                .setUri(uriFor("/v1/announcement/static"))
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON)
                .setBodyGenerator(jsonBodyGenerator(jsonCodec(Object.class), announcement))
                .build();
        JsonResponse<Map<String, Object>> createResponse = client.execute(request, createFullJsonResponseHandler(mapJsonCodec(String.class, Object.class)));

        assertEquals(createResponse.getStatusCode(), Status.CREATED.getStatusCode());
        String id = createResponse.getValue().get("id").toString();

        List<ServiceDescriptor> services = selectorFor("apple", "red").selectAllServices();
        assertEquals(services.size(), 1);

        ServiceDescriptor service = services.get(0);
        assertEquals(service.getId().toString(), id);
        assertNull(service.getNodeId());
        assertEquals(service.getLocation(), announcement.get("location"));
        assertEquals(service.getPool(), announcement.get("pool"));
        assertEquals(service.getProperties(), announcement.get("properties"));

        // remove announcement
        request = prepareDelete().setUri(uriFor("/v1/announcement/static/" + id)).build();
        StatusResponse deleteResponse = client.execute(request, createStatusResponseHandler());

        assertEquals(deleteResponse.getStatusCode(), Status.NO_CONTENT.getStatusCode());

        // ensure announcement is gone
        assertTrue(selectorFor("apple", "red").selectAllServices().isEmpty());
    }

    private ServiceSelector selectorFor(String type, String pool)
    {
        Map<String, String> clientProperties = ImmutableMap.<String, String>builder()
            .put("node.environment", "testing")
            .put("discovery.uri", server.getBaseUrl().toString())
            .put("discovery.apple.pool", "red")
            .build();

        Injector clientInjector = Guice.createInjector(
                new NodeModule(),
                new JsonModule(),
                new ConfigurationModule(new ConfigurationFactory(clientProperties)),
                new com.proofpoint.discovery.client.DiscoveryModule()
        );

        DiscoveryLookupClient client = clientInjector.getInstance(DiscoveryLookupClient.class);
        return new SimpleServiceSelector(type, new ServiceSelectorConfig().setPool(pool), client);
    }

    private URI uriFor(String path)
    {
        return server.getBaseUrl().resolve(path);
    }
}
