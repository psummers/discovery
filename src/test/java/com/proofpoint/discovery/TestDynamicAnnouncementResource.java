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
import com.proofpoint.discovery.DiscoveryConfig.StringSet;
import com.proofpoint.discovery.store.RealTimeSupplier;
import com.proofpoint.node.NodeInfo;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;

public class TestDynamicAnnouncementResource
{
    private InMemoryDynamicStore store;
    private DynamicAnnouncementResource resource;

    @BeforeMethod
    public void setup()
    {
        store = new InMemoryDynamicStore(new DiscoveryConfig(), new RealTimeSupplier());
        resource = new DynamicAnnouncementResource(store, new NodeInfo("testing"), new DiscoveryConfig());
    }

    @Test
    public void testPutNew()
    {
        DynamicServiceAnnouncement serviceAnnouncement = new DynamicServiceAnnouncement(Id.random(), "storage", ImmutableMap.of("http", "http://localhost:1111"));
        DynamicAnnouncement announcement = new DynamicAnnouncement("testing", "alpha", "/a/b/c", ImmutableSet.of(
                serviceAnnouncement)
        );

        Id<Node> nodeId = Id.random();
        Response response = resource.put(nodeId, announcement);

        assertNotNull(response);
        assertEquals(response.getStatus(), Response.Status.ACCEPTED.getStatusCode());

        assertEquals(store.getAll().count(), 1);
        Service service = store.getAll().iterator().next();

        assertNotNull(service.getId());
        assertEquals(service.getNodeId(), nodeId);
        assertEquals(service.getLocation(), announcement.getLocation());
        assertEquals(service.getType(), serviceAnnouncement.getType());
        assertEquals(service.getPool(), announcement.getPool());
        assertEquals(service.getProperties(), serviceAnnouncement.getProperties());
    }

    @Test
    public void testReplace()
    {
        Id<Node> nodeId = Id.random();
        DynamicAnnouncement previous = new DynamicAnnouncement("testing", "alpha", "/a/b/c", ImmutableSet.of(
                new DynamicServiceAnnouncement(Id.random(), "storage", ImmutableMap.of("key", "existing"))
        ));

        store.put(nodeId, previous);

        DynamicServiceAnnouncement serviceAnnouncement = new DynamicServiceAnnouncement(Id.random(), "storage", ImmutableMap.of("key", "new"));
        DynamicAnnouncement announcement = new DynamicAnnouncement("testing", "alpha", "/a/b/c", ImmutableSet.of(
                serviceAnnouncement)
        );

        Response response = resource.put(nodeId, announcement);

        assertNotNull(response);
        assertEquals(response.getStatus(), Response.Status.ACCEPTED.getStatusCode());

        assertEquals(store.getAll().count(), 1);
        Service service = store.getAll().iterator().next();

        assertNotNull(service.getId());
        assertEquals(service.getNodeId(), nodeId);
        assertEquals(service.getLocation(), announcement.getLocation());
        assertEquals(service.getType(), serviceAnnouncement.getType());
        assertEquals(service.getPool(), announcement.getPool());
        assertEquals(service.getProperties(), serviceAnnouncement.getProperties());
    }

    @Test
    public void testEnvironmentConflict()
    {
        DynamicAnnouncement announcement = new DynamicAnnouncement("production", "alpha", "/a/b/c", ImmutableSet.of(
                new DynamicServiceAnnouncement(Id.random(), "storage", ImmutableMap.of("http", "http://localhost:1111")))
        );

        Id<Node> nodeId = Id.random();
        Response response = resource.put(nodeId, announcement);

        assertNotNull(response);
        assertEquals(response.getStatus(), Response.Status.BAD_REQUEST.getStatusCode());

        assertEquals(store.getAll().count(), 0);
    }

    @Test
    public void testPutProxied()
    {
        resource = new DynamicAnnouncementResource(store, new NodeInfo("testing"),
                new DiscoveryConfig().setProxyProxiedTypes(StringSet.of("storage")));

        DynamicAnnouncement announcement = new DynamicAnnouncement("testing", "alpha", "/a/b/c", ImmutableSet.of(
                new DynamicServiceAnnouncement(Id.random(), "storage", ImmutableMap.of("http", "http://localhost:1111")))
        );

        Id<Node> nodeId = Id.random();
        Response response = resource.put(nodeId, announcement);

        assertNotNull(response);
        assertEquals(response.getStatus(), Status.FORBIDDEN.getStatusCode());

        assertEquals(store.getAll().count(), 0);
    }

    @Test
    public void testDeleteExisting()
    {
        Id<Node> blueNodeId = Id.random();
        DynamicServiceAnnouncement serviceAnnouncement = new DynamicServiceAnnouncement(Id.random(), "storage", ImmutableMap.of("key", "valueBlue"));
        DynamicAnnouncement blue = new DynamicAnnouncement("testing", "alpha", "/a/b/c", ImmutableSet.of(
                serviceAnnouncement
        ));

        Id<Node> redNodeId = Id.random();
        DynamicAnnouncement red = new DynamicAnnouncement("testing", "alpha", "/a/b/c", ImmutableSet.of(
                serviceAnnouncement
        ));

        store.put(redNodeId, red);
        store.put(blueNodeId, blue);

        resource.delete(blueNodeId);

        assertEquals(store.getAll().count(), 1);
        Service service = store.getAll().iterator().next();

        assertNotNull(service.getId());
        assertEquals(service.getNodeId(), redNodeId);
        assertEquals(service.getLocation(), red.getLocation());
        assertEquals(service.getType(), serviceAnnouncement.getType());
        assertEquals(service.getPool(), red.getPool());
        assertEquals(service.getProperties(), serviceAnnouncement.getProperties());
    }

    @Test
    public void testDeleteMissing()
    {
        resource.delete(Id.random());

        assertEquals(store.getAll().count(), 0);
    }

    @Test
    public void testMakesUpLocation()
    {
        DynamicAnnouncement announcement = new DynamicAnnouncement("testing", "alpha", null, ImmutableSet.of(
                new DynamicServiceAnnouncement(Id.random(), "storage", ImmutableMap.of("http", "http://localhost:1111")))
        );

        Id<Node> nodeId = Id.random();
        Response response = resource.put(nodeId, announcement);

        assertNotNull(response);
        assertEquals(response.getStatus(), Response.Status.ACCEPTED.getStatusCode());

        assertEquals(store.getAll().count(), 1);
        Service service = store.getAll().iterator().next();
        assertEquals(service.getId(), service.getId());
        assertNotNull(service.getLocation());
    }
}
