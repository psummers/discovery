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
package com.proofpoint.discovery.store;

import com.proofpoint.discovery.DiscoveryConfig;
import com.proofpoint.discovery.DynamicAnnouncement;
import com.proofpoint.discovery.DynamicStore;
import com.proofpoint.discovery.Id;
import com.proofpoint.discovery.Node;
import com.proofpoint.discovery.Service;
import com.proofpoint.reporting.Gauge;
import com.proofpoint.units.Duration;
import org.weakref.jmx.Managed;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.proofpoint.concurrent.Threads.daemonThreadsNamed;
import static com.proofpoint.discovery.DynamicServiceAnnouncement.toServiceWith;
import static com.proofpoint.discovery.Service.matchesPool;
import static com.proofpoint.discovery.Service.matchesType;
import static com.proofpoint.discovery.store.Entry.entry;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.Executors.newSingleThreadScheduledExecutor;

/**
 * A simple, eventually consistent, fully replicated, distributed key-value store.
 */
public class DistributedStore
    implements DynamicStore
{
    private final String name;
    private final InMemoryStore localStore;
    private final RemoteStore remoteStore;
    private final Supplier<Instant> timeSupplier;
    private final Duration tombstoneMaxAge;
    private final Duration garbageCollectionInterval;
    private final Duration maxAge;

    private final ScheduledExecutorService garbageCollector;
    private final AtomicLong lastGcTimestamp = new AtomicLong();

    @Inject
    public DistributedStore(
            String name,
            InMemoryStore localStore,
            RemoteStore remoteStore,
            StoreConfig config,
            DiscoveryConfig discoveryConfig,
            Supplier<Instant> timeSupplier)
    {
        this.name = requireNonNull(name, "name is null");
        this.localStore = requireNonNull(localStore, "localStore is null");
        this.remoteStore = requireNonNull(remoteStore, "remoteStore is null");
        this.timeSupplier = requireNonNull(timeSupplier, "timeSupplier is null");

        requireNonNull(config, "config is null");
        tombstoneMaxAge = config.getTombstoneMaxAge();
        garbageCollectionInterval = config.getGarbageCollectionInterval();

        maxAge = requireNonNull(discoveryConfig, "discoveryConfig is null").getMaxAge();

        garbageCollector = newSingleThreadScheduledExecutor(daemonThreadsNamed("distributed-store-gc-" + name));
    }

    @PostConstruct
    @SuppressWarnings("FutureReturnValueIgnored")
    public void start()
    {
        garbageCollector.scheduleAtFixedRate(this::removeExpiredEntries, 0, garbageCollectionInterval.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Managed
    public String getName()
    {
        return name;
    }

    @Managed
    public long getLastGcTimestamp()
    {
        return lastGcTimestamp.get();
    }

    @Managed
    public void removeExpiredEntries()
    {
        for (Entry entry : localStore.getAll()) {
            if (isExpired(entry)) {
                localStore.delete(entry.getKey(), entry.getTimestamp());
            }
        }

        lastGcTimestamp.set(System.currentTimeMillis());
    }

    @Gauge
    public long getActiveEntryCount()
    {
        long count = 0;
        for (Entry entry : localStore.getAll()) {
            if (!isExpired(entry) && entry.getValue() != null) {
                ++count;
            }
        }
        return count;
    }

    private boolean isExpired(Entry entry)
    {
        long ageInMs = timeSupplier.get().toEpochMilli() - entry.getTimestamp();

        return (entry.getValue() == null && ageInMs > tombstoneMaxAge.toMillis()) ||  // TODO: this is repeated in StoreResource
                (entry.getMaxAgeInMs() != null && ageInMs > entry.getMaxAgeInMs());
    }

    @PreDestroy
    public void shutdown()
    {
        garbageCollector.shutdownNow();
    }

    @Override
    public void put(Id<Node> nodeId, DynamicAnnouncement announcement)
    {
        requireNonNull(nodeId, "nodeId is null");
        requireNonNull(announcement, "announcement is null");

        long now = timeSupplier.get().toEpochMilli();

        List<Service> services = announcement.getServiceAnnouncements().stream()
                .map(toServiceWith(nodeId, announcement.getLocation(), announcement.getPool()))
                .collect(Collectors.toList());
        Entry entry = entry(nodeId.getBytes(), services, now, maxAge.toMillis());

        localStore.put(entry);
        remoteStore.put(entry);
    }

    @Override
    public void delete(Id<Node> nodeId)
    {
        requireNonNull(nodeId, "nodeId is null");

        long now = timeSupplier.get().toEpochMilli();

        Entry entry = entry(nodeId.getBytes(), (List<Service>) null, now, null);

        localStore.put(entry);
        remoteStore.put(entry);
    }

    @Override
    public Stream<Service> get(String type)
    {
        return getAll()
                .filter(matchesType(type));
    }

    @Override
    public Stream<Service> get(String type, String pool)
    {
        return getAll()
                .filter(matchesType(type).and(matchesPool(pool)));
    }

    @Override
    public Stream<Service> getAll()
    {
        return localStore.getAll().stream()
                .filter(expired().negate().and(tombstone().negate()))
                .flatMap(entry -> entry.getValue().stream());
    }

    private Predicate<Entry> expired()
    {
        return this::isExpired;
    }

    private static Predicate<Entry> tombstone()
    {
        return entry -> entry.getValue() == null;
    }
}
