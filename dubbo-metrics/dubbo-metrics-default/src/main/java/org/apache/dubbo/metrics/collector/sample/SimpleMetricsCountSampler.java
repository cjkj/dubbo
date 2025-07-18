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
package org.apache.dubbo.metrics.collector.sample;

import org.apache.dubbo.common.utils.Assert;
import org.apache.dubbo.common.utils.ConcurrentHashMapUtils;
import org.apache.dubbo.metrics.model.Metric;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * @param <S> request source
 * @param <K> metricsName
 * @param <M> metric
 */
public abstract class SimpleMetricsCountSampler<S, K, M extends Metric> implements MetricsCountSampler<S, K, M> {

    private final ConcurrentHashMap<M, AtomicLong> EMPTY_COUNT = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<K, ConcurrentHashMap<M, AtomicLong>> metricCounter = new ConcurrentHashMap<>();

    @Override
    public void inc(S source, K metricName) {
        getAtomicCounter(source, metricName).incrementAndGet();
    }

    @Override
    public Optional<ConcurrentHashMap<M, AtomicLong>> getCount(K metricName) {
        return Optional.ofNullable(metricCounter.get(metricName) == null ? EMPTY_COUNT : metricCounter.get(metricName));
    }

    protected abstract void countConfigure(MetricsCountSampleConfigurer<S, K, M> sampleConfigure);

    private AtomicLong getAtomicCounter(S source, K metricsName) {
        MetricsCountSampleConfigurer<S, K, M> sampleConfigure = new MetricsCountSampleConfigurer<>();
        sampleConfigure.setSource(source);
        sampleConfigure.setMetricsName(metricsName);

        this.countConfigure(sampleConfigure);

        ConcurrentHashMap<M, AtomicLong> metricAtomic = metricCounter.get(metricsName);

        if (metricAtomic == null) {
            metricAtomic =
                    ConcurrentHashMapUtils.computeIfAbsent(metricCounter, metricsName, k -> new ConcurrentHashMap<>());
        }

        Assert.notNull(sampleConfigure.getMetric(), "metrics is null");

        AtomicLong atomicCounter = metricAtomic.get(sampleConfigure.getMetric());

        if (atomicCounter == null) {
            atomicCounter = ConcurrentHashMapUtils.computeIfAbsent(
                    metricAtomic, sampleConfigure.getMetric(), k -> new AtomicLong());
        }
        return atomicCounter;
    }
}
