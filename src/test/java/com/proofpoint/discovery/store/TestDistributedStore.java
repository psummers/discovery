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
import com.proofpoint.discovery.DynamicStore;
import com.proofpoint.discovery.TestDynamicStore;

import java.time.Instant;
import java.util.function.Supplier;

public class TestDistributedStore
    extends TestDynamicStore
{
    @Override
    protected DynamicStore initializeStore(DiscoveryConfig config, Supplier<Instant> timeSupplier)
    {
        RemoteStore dummy = entry -> { };

        return new DistributedStore("dynamic", new InMemoryStore(config), dummy, new StoreConfig(), config, timeSupplier);
    }
}