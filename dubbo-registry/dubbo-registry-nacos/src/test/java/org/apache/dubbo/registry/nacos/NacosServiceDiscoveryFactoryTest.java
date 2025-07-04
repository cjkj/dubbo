/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.dubbo.registry.nacos;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.config.ApplicationConfig;
import org.apache.dubbo.registry.client.ServiceDiscovery;
import org.apache.dubbo.rpc.model.ApplicationModel;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Test for NacosServiceDiscoveryFactory
 */
class NacosServiceDiscoveryFactoryTest {

    private NacosServiceDiscoveryFactory nacosServiceDiscoveryFactory;

    @BeforeEach
    public void setup() {
        nacosServiceDiscoveryFactory = new NacosServiceDiscoveryFactory();
        ApplicationModel applicationModel = ApplicationModel.defaultModel();
        applicationModel
                .getApplicationConfigManager()
                .setApplication(new ApplicationConfig("NacosServiceDiscoveryFactoryTest"));
        nacosServiceDiscoveryFactory.setApplicationModel(applicationModel);
    }

    @Test
    void testGetServiceDiscoveryWithCache() {
        URL url = URL.valueOf("dubbo://test:8080?nacos.check=false");
        ServiceDiscovery discovery = nacosServiceDiscoveryFactory.createDiscovery(url);

        Assertions.assertTrue(discovery instanceof NacosServiceDiscovery);
    }

    @AfterEach
    public void tearDown() {
        ApplicationModel.defaultModel().destroy();
    }
}
