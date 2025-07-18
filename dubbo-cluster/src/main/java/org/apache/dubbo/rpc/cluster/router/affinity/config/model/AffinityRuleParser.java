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
package org.apache.dubbo.rpc.cluster.router.affinity.config.model;

import org.apache.dubbo.common.utils.StringUtils;

import java.util.Map;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import static org.apache.dubbo.rpc.cluster.Constants.CONFIG_VERSION_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.RULE_VERSION_V31;

/**
 * # dubbo/config/group/{$name}.affinity-router
 * configVersion: v3.1
 * scope: service # Or application
 * key: service.apache.com
 * enabled: true
 * runtime: true
 * affinityAware:
 *   key: region
 *   ratio: 20
 */
public class AffinityRuleParser {

    public static AffinityRouterRule parse(String rawRule) {
        AffinityRouterRule rule;
        Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        Map<String, Object> map = yaml.load(rawRule);
        String confVersion = (String) map.get(CONFIG_VERSION_KEY);

        rule = AffinityRouterRule.parseFromMap(map);
        if (StringUtils.isEmpty(rule.getAffinityKey()) || !confVersion.startsWith(RULE_VERSION_V31)) {
            rule.setValid(false);
        }
        rule.setRawRule(rawRule);

        return rule;
    }
}
