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
package org.apache.dubbo.remoting.zookeeper.curator5;

import org.apache.dubbo.common.URL;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.IsNot.not;
import static org.hamcrest.core.IsNull.nullValue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstructionWithAnswer;

class Curator5ZookeeperClientManagerTest {
    private ZookeeperClient zookeeperClient;
    private static MockedConstruction<Curator5ZookeeperClient> mockedCurator5ZookeeperClientConstruction;
    private static String zookeeperConnectionAddress1;

    @BeforeAll
    public static void beforeAll() {
        zookeeperConnectionAddress1 = "zookeeper://127.0.0.1:2181";
        Curator5ZookeeperClient mockCurator5ZookeeperClient = mock(Curator5ZookeeperClient.class);
        mockedCurator5ZookeeperClientConstruction =
                mockConstructionWithAnswer(Curator5ZookeeperClient.class, invocationOnMock -> invocationOnMock
                        .getMethod()
                        .invoke(mockCurator5ZookeeperClient, invocationOnMock.getArguments()));
    }

    @BeforeEach
    public void setUp() {
        zookeeperClient = new ZookeeperClientManager().connect(URL.valueOf(zookeeperConnectionAddress1 + "/service"));
    }

    @Test
    void testZookeeperClient() {
        assertThat(zookeeperClient, not(nullValue()));
        zookeeperClient.close();
    }

    @AfterAll
    public static void afterAll() {
        mockedCurator5ZookeeperClientConstruction.close();
    }
}
