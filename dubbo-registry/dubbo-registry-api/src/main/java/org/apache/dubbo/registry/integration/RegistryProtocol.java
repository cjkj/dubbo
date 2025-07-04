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
package org.apache.dubbo.registry.integration;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.config.configcenter.DynamicConfiguration;
import org.apache.dubbo.common.constants.RegistryConstants;
import org.apache.dubbo.common.deploy.ApplicationDeployer;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.logger.ErrorTypeAwareLogger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.threadpool.manager.FrameworkExecutorRepository;
import org.apache.dubbo.common.timer.HashedWheelTimer;
import org.apache.dubbo.common.url.component.ServiceConfigURL;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.ConcurrentHashMapUtils;
import org.apache.dubbo.common.utils.ConcurrentHashSet;
import org.apache.dubbo.common.utils.NamedThreadFactory;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.common.utils.UrlUtils;
import org.apache.dubbo.metrics.event.MetricsEventBus;
import org.apache.dubbo.metrics.registry.event.RegistryEvent;
import org.apache.dubbo.registry.NotifyListener;
import org.apache.dubbo.registry.Registry;
import org.apache.dubbo.registry.RegistryFactory;
import org.apache.dubbo.registry.RegistryService;
import org.apache.dubbo.registry.client.ServiceDiscoveryRegistryDirectory;
import org.apache.dubbo.registry.client.migration.MigrationClusterInvoker;
import org.apache.dubbo.registry.client.migration.ServiceDiscoveryMigrationInvoker;
import org.apache.dubbo.registry.retry.ReExportTask;
import org.apache.dubbo.registry.support.SkipFailbackWrapperException;
import org.apache.dubbo.rpc.Exporter;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Protocol;
import org.apache.dubbo.rpc.ProtocolServer;
import org.apache.dubbo.rpc.ProxyFactory;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.cluster.Cluster;
import org.apache.dubbo.rpc.cluster.ClusterInvoker;
import org.apache.dubbo.rpc.cluster.Configurator;
import org.apache.dubbo.rpc.cluster.Constants;
import org.apache.dubbo.rpc.cluster.governance.GovernanceRuleRepository;
import org.apache.dubbo.rpc.model.ApplicationModel;
import org.apache.dubbo.rpc.model.FrameworkModel;
import org.apache.dubbo.rpc.model.ModuleModel;
import org.apache.dubbo.rpc.model.ProviderModel;
import org.apache.dubbo.rpc.model.ScopeModel;
import org.apache.dubbo.rpc.model.ScopeModelAware;
import org.apache.dubbo.rpc.model.ScopeModelUtil;
import org.apache.dubbo.rpc.protocol.InvokerWrapper;
import org.apache.dubbo.rpc.support.ProtocolUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static org.apache.dubbo.common.constants.CommonConstants.APPLICATION_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.CLUSTER_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.COMMA_SPLIT_PATTERN;
import static org.apache.dubbo.common.constants.CommonConstants.CONSUMER;
import static org.apache.dubbo.common.constants.CommonConstants.DUBBO_VERSION_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.ENABLED_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.EXTRA_KEYS_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.GROUP_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.INTERFACE_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.IPV6_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.LOADBALANCE_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.MERGEABLE_CLUSTER_NAME;
import static org.apache.dubbo.common.constants.CommonConstants.PACKABLE_METHOD_FACTORY_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PASSWORD_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PATH_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PROTOCOL_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.REGISTRY_PROTOCOL_LISTENER_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.RELEASE_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.SIDE_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.TIMEOUT_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.USERNAME_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.VERSION_KEY;
import static org.apache.dubbo.common.constants.LoggerCodeConstants.INTERNAL_ERROR;
import static org.apache.dubbo.common.constants.LoggerCodeConstants.REGISTRY_UNSUPPORTED_CATEGORY;
import static org.apache.dubbo.common.constants.RegistryConstants.ALL_CATEGORIES;
import static org.apache.dubbo.common.constants.RegistryConstants.CATEGORY_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.CONFIGURATORS_CATEGORY;
import static org.apache.dubbo.common.constants.RegistryConstants.DYNAMIC_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.OVERRIDE_PROTOCOL;
import static org.apache.dubbo.common.constants.RegistryConstants.REGISTRY_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.SERVICE_REGISTRY_PROTOCOL;
import static org.apache.dubbo.common.utils.StringUtils.isEmpty;
import static org.apache.dubbo.common.utils.UrlUtils.classifyUrls;
import static org.apache.dubbo.registry.Constants.CONFIGURATORS_SUFFIX;
import static org.apache.dubbo.registry.Constants.DEFAULT_REGISTRY_RETRY_PERIOD;
import static org.apache.dubbo.registry.Constants.ENABLE_26X_CONFIGURATION_LISTEN;
import static org.apache.dubbo.registry.Constants.ENABLE_CONFIGURATION_LISTEN;
import static org.apache.dubbo.registry.Constants.PROVIDER_PROTOCOL;
import static org.apache.dubbo.registry.Constants.REGISTER_IP_KEY;
import static org.apache.dubbo.registry.Constants.REGISTER_KEY;
import static org.apache.dubbo.registry.Constants.REGISTRY_RETRY_PERIOD_KEY;
import static org.apache.dubbo.registry.Constants.SIMPLIFIED_KEY;
import static org.apache.dubbo.remoting.Constants.CHECK_KEY;
import static org.apache.dubbo.remoting.Constants.CODEC_KEY;
import static org.apache.dubbo.remoting.Constants.CONNECTIONS_KEY;
import static org.apache.dubbo.remoting.Constants.EXCHANGER_KEY;
import static org.apache.dubbo.remoting.Constants.PREFER_SERIALIZATION_KEY;
import static org.apache.dubbo.remoting.Constants.SERIALIZATION_KEY;
import static org.apache.dubbo.rpc.Constants.AUTHENTICATOR_KEY;
import static org.apache.dubbo.rpc.Constants.AUTH_KEY;
import static org.apache.dubbo.rpc.Constants.DEPRECATED_KEY;
import static org.apache.dubbo.rpc.Constants.GENERIC_KEY;
import static org.apache.dubbo.rpc.Constants.MOCK_KEY;
import static org.apache.dubbo.rpc.Constants.TOKEN_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.CONSUMER_URL_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.EXPORT_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.REFER_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.WARMUP_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.WEIGHT_KEY;
import static org.apache.dubbo.rpc.model.ScopeModelUtil.getApplicationModel;

/**
 * TODO, replace RegistryProtocol completely in the future.
 */
public class RegistryProtocol implements Protocol, ScopeModelAware {
    public static final String[] DEFAULT_REGISTER_PROVIDER_KEYS = {
        APPLICATION_KEY,
        CODEC_KEY,
        EXCHANGER_KEY,
        SERIALIZATION_KEY,
        PREFER_SERIALIZATION_KEY,
        CLUSTER_KEY,
        CONNECTIONS_KEY,
        DEPRECATED_KEY,
        GROUP_KEY,
        LOADBALANCE_KEY,
        MOCK_KEY,
        PATH_KEY,
        TIMEOUT_KEY,
        TOKEN_KEY,
        VERSION_KEY,
        WARMUP_KEY,
        WEIGHT_KEY,
        DUBBO_VERSION_KEY,
        RELEASE_KEY,
        SIDE_KEY,
        IPV6_KEY,
        PACKABLE_METHOD_FACTORY_KEY,
        AUTH_KEY,
        AUTHENTICATOR_KEY,
        USERNAME_KEY,
        PASSWORD_KEY
    };

    public static final String[] DEFAULT_REGISTER_CONSUMER_KEYS = {
        APPLICATION_KEY, VERSION_KEY, GROUP_KEY, DUBBO_VERSION_KEY, RELEASE_KEY
    };

    private static final ErrorTypeAwareLogger logger = LoggerFactory.getErrorTypeAwareLogger(RegistryProtocol.class);

    private final Map<String, ServiceConfigurationListener> serviceConfigurationListeners = new ConcurrentHashMap<>();
    // To solve the problem of RMI repeated exposure port conflicts, the services that have been exposed are no longer
    // exposed.
    // provider url <--> registry url <--> exporter
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, ExporterChangeableWrapper<?>>> bounds =
            new ConcurrentHashMap<>();
    protected Protocol protocol;
    protected ProxyFactory proxyFactory;

    private ConcurrentMap<URL, ReExportTask> reExportFailedTasks = new ConcurrentHashMap<>();
    private HashedWheelTimer retryTimer = new HashedWheelTimer(
            new NamedThreadFactory("DubboReexportTimer", true),
            DEFAULT_REGISTRY_RETRY_PERIOD,
            TimeUnit.MILLISECONDS,
            128);
    private FrameworkModel frameworkModel;
    private ExporterFactory exporterFactory;

    public RegistryProtocol() {}

    @Override
    public void setFrameworkModel(FrameworkModel frameworkModel) {
        this.frameworkModel = frameworkModel;
        this.exporterFactory = frameworkModel.getBeanFactory().getBean(ExporterFactory.class);
    }

    public void setProtocol(Protocol protocol) {
        this.protocol = protocol;
    }

    public void setProxyFactory(ProxyFactory proxyFactory) {
        this.proxyFactory = proxyFactory;
    }

    @Override
    public int getDefaultPort() {
        return 9090;
    }

    public Map<URL, Set<NotifyListener>> getOverrideListeners() {
        Map<URL, Set<NotifyListener>> map = new HashMap<>();
        List<ApplicationModel> applicationModels = frameworkModel.getApplicationModels();
        if (applicationModels.size() == 1) {
            return applicationModels
                    .get(0)
                    .getBeanFactory()
                    .getBean(ProviderConfigurationListener.class)
                    .getOverrideListeners();
        } else {
            for (ApplicationModel applicationModel : applicationModels) {
                map.putAll(applicationModel
                        .getBeanFactory()
                        .getBean(ProviderConfigurationListener.class)
                        .getOverrideListeners());
            }
        }
        return map;
    }

    private static void register(Registry registry, URL registeredProviderUrl) {
        ApplicationDeployer deployer =
                registeredProviderUrl.getOrDefaultApplicationModel().getDeployer();
        try {
            deployer.increaseServiceRefreshCount();
            String registryName = Optional.ofNullable(registry.getUrl())
                    .map(u -> u.getParameter(
                            RegistryConstants.REGISTRY_CLUSTER_KEY,
                            UrlUtils.isServiceDiscoveryURL(u) ? u.getParameter(REGISTRY_KEY) : u.getProtocol()))
                    .filter(StringUtils::isNotEmpty)
                    .orElse("unknown");
            MetricsEventBus.post(
                    RegistryEvent.toRsEvent(
                            registeredProviderUrl.getApplicationModel(),
                            registeredProviderUrl.getServiceKey(),
                            1,
                            Collections.singletonList(registryName)),
                    () -> {
                        registry.register(registeredProviderUrl);
                        return null;
                    });
        } finally {
            deployer.decreaseServiceRefreshCount();
        }
    }

    private void registerStatedUrl(URL registryUrl, URL registeredProviderUrl, boolean registered) {
        ProviderModel model = (ProviderModel) registeredProviderUrl.getServiceModel();
        model.addStatedUrl(new ProviderModel.RegisterStatedURL(registeredProviderUrl, registryUrl, registered));
    }

    @Override
    public <T> Exporter<T> export(final Invoker<T> originInvoker) throws RpcException {
        URL registryUrl = getRegistryUrl(originInvoker);
        // url to export locally
        URL providerUrl = getProviderUrl(originInvoker);

        // Subscribe the override data
        // FIXME When the provider subscribes, it will affect the scene : a certain JVM exposes the service and call
        //  the same service. Because the subscribed is cached key with the name of the service, it causes the
        //  subscription information to cover.
        final URL overrideSubscribeUrl = getSubscribedOverrideUrl(providerUrl);
        final OverrideListener overrideSubscribeListener = new OverrideListener(overrideSubscribeUrl, originInvoker);
        ConcurrentHashMap<URL, Set<NotifyListener>> overrideListeners =
                getProviderConfigurationListener(overrideSubscribeUrl).getOverrideListeners();
        ConcurrentHashMapUtils.computeIfAbsent(overrideListeners, overrideSubscribeUrl, k -> new ConcurrentHashSet<>())
                .add(overrideSubscribeListener);

        providerUrl = overrideUrlWithConfig(providerUrl, overrideSubscribeListener);
        // export invoker
        final ExporterChangeableWrapper<T> exporter = doLocalExport(originInvoker, providerUrl);

        // url to registry
        final Registry registry = getRegistry(registryUrl);
        final URL registeredProviderUrl = customizeURL(providerUrl, registryUrl);

        // decide if we need to delay publish (provider itself and registry should both need to register)
        boolean register = providerUrl.getParameter(REGISTER_KEY, true) && registryUrl.getParameter(REGISTER_KEY, true);
        if (register) {
            register(registry, registeredProviderUrl);
        }

        // register stated url on provider model
        registerStatedUrl(registryUrl, registeredProviderUrl, register);

        exporter.setRegisterUrl(registeredProviderUrl);
        exporter.setSubscribeUrl(overrideSubscribeUrl);
        exporter.setNotifyListener(overrideSubscribeListener);
        exporter.setRegistered(register);

        ApplicationModel applicationModel = getApplicationModel(providerUrl.getScopeModel());
        if (applicationModel
                .modelEnvironment()
                .getConfiguration()
                .convert(Boolean.class, ENABLE_26X_CONFIGURATION_LISTEN, true)) {
            if (!registry.isServiceDiscovery()) {
                // Deprecated! Subscribe to override rules in 2.6.x or before.
                registry.subscribe(overrideSubscribeUrl, overrideSubscribeListener);
            }
        }

        notifyExport(exporter);
        // Ensure that a new exporter instance is returned every time export
        return new DestroyableExporter<>(exporter);
    }

    private <T> void notifyExport(ExporterChangeableWrapper<T> exporter) {
        ScopeModel scopeModel = exporter.getRegisterUrl().getScopeModel();
        List<RegistryProtocolListener> listeners = ScopeModelUtil.getExtensionLoader(
                        RegistryProtocolListener.class, scopeModel)
                .getActivateExtension(exporter.getOriginInvoker().getUrl(), REGISTRY_PROTOCOL_LISTENER_KEY);
        if (CollectionUtils.isNotEmpty(listeners)) {
            for (RegistryProtocolListener listener : listeners) {
                listener.onExport(this, exporter);
            }
        }
    }

    private URL overrideUrlWithConfig(URL providerUrl, OverrideListener listener) {
        ProviderConfigurationListener providerConfigurationListener = getProviderConfigurationListener(providerUrl);
        providerUrl = providerConfigurationListener.overrideUrl(providerUrl);

        ServiceConfigurationListener serviceConfigurationListener =
                new ServiceConfigurationListener(providerUrl.getOrDefaultModuleModel(), providerUrl, listener);
        serviceConfigurationListeners.put(providerUrl.getServiceKey(), serviceConfigurationListener);
        return serviceConfigurationListener.overrideUrl(providerUrl);
    }

    @SuppressWarnings("unchecked")
    private <T> ExporterChangeableWrapper<T> doLocalExport(final Invoker<T> originInvoker, URL providerUrl) {
        String providerUrlKey = getProviderUrlKey(originInvoker);
        String registryUrlKey = getRegistryUrlKey(originInvoker);
        Invoker<?> invokerDelegate = new InvokerDelegate<>(originInvoker, providerUrl);

        ReferenceCountExporter<?> exporter =
                exporterFactory.createExporter(providerUrlKey, () -> protocol.export(invokerDelegate));
        return (ExporterChangeableWrapper<T>) ConcurrentHashMapUtils.computeIfAbsent(
                ConcurrentHashMapUtils.computeIfAbsent(bounds, providerUrlKey, k -> new ConcurrentHashMap<>()),
                registryUrlKey,
                s -> new ExporterChangeableWrapper<>((ReferenceCountExporter<T>) exporter, originInvoker));
    }

    public <T> void reExport(Exporter<T> exporter, URL newInvokerUrl) {
        if (exporter instanceof ExporterChangeableWrapper) {
            ExporterChangeableWrapper<T> exporterWrapper = (ExporterChangeableWrapper<T>) exporter;
            Invoker<T> originInvoker = exporterWrapper.getOriginInvoker();
            reExport(originInvoker, newInvokerUrl);
        }
    }

    /**
     * Reexport the invoker of the modified url
     *
     * @param originInvoker
     * @param newInvokerUrl
     * @param <T>
     */
    @SuppressWarnings("unchecked")
    public <T> void reExport(final Invoker<T> originInvoker, URL newInvokerUrl) {
        String providerUrlKey = getProviderUrlKey(originInvoker);
        String registryUrlKey = getRegistryUrlKey(originInvoker);
        Map<String, ExporterChangeableWrapper<?>> registryMap = bounds.get(providerUrlKey);
        if (registryMap == null) {
            logger.warn(
                    INTERNAL_ERROR,
                    "error state, exporterMap can not be null",
                    "",
                    "error state, exporterMap can not be null",
                    new IllegalStateException("error state, exporterMap can not be null"));
            return;
        }
        ExporterChangeableWrapper<T> exporter = (ExporterChangeableWrapper<T>) registryMap.get(registryUrlKey);
        if (exporter == null) {
            logger.warn(
                    INTERNAL_ERROR,
                    "error state, exporterMap can not be null",
                    "",
                    "error state, exporterMap can not be null",
                    new IllegalStateException("error state, exporterMap can not be null"));
            return;
        }
        URL registeredUrl = exporter.getRegisterUrl();

        URL registryUrl = getRegistryUrl(originInvoker);
        URL newProviderUrl = customizeURL(newInvokerUrl, registryUrl);

        // update local exporter
        Invoker<T> invokerDelegate = new InvokerDelegate<>(originInvoker, newInvokerUrl);
        exporter.setExporter(protocol.export(invokerDelegate));

        // update registry
        if (!newProviderUrl.equals(registeredUrl)) {
            try {
                doReExport(originInvoker, exporter, registryUrl, registeredUrl, newProviderUrl);
            } catch (Exception e) {
                ReExportTask oldTask = reExportFailedTasks.get(registeredUrl);
                if (oldTask != null) {
                    return;
                }
                ReExportTask task = new ReExportTask(
                        () -> doReExport(originInvoker, exporter, registryUrl, registeredUrl, newProviderUrl),
                        registeredUrl,
                        null);
                oldTask = reExportFailedTasks.putIfAbsent(registeredUrl, task);
                if (oldTask == null) {
                    // never has a retry task. then start a new task for retry.
                    retryTimer.newTimeout(
                            task,
                            registryUrl.getParameter(REGISTRY_RETRY_PERIOD_KEY, DEFAULT_REGISTRY_RETRY_PERIOD),
                            TimeUnit.MILLISECONDS);
                }
            }
        }
    }

    private <T> void doReExport(
            final Invoker<T> originInvoker,
            ExporterChangeableWrapper<T> exporter,
            URL registryUrl,
            URL oldProviderUrl,
            URL newProviderUrl) {
        if (exporter.isRegistered()) {
            Registry registry;
            try {
                registry = getRegistry(getRegistryUrl(originInvoker));
            } catch (Exception e) {
                throw new SkipFailbackWrapperException(e);
            }

            logger.info("Try to unregister old url: " + oldProviderUrl);
            registry.reExportUnregister(oldProviderUrl);

            logger.info("Try to register new url: " + newProviderUrl);
            registry.reExportRegister(newProviderUrl);
        }
        try {
            ProviderModel.RegisterStatedURL statedUrl = getStatedUrl(registryUrl, newProviderUrl);
            statedUrl.setProviderUrl(newProviderUrl);
            exporter.setRegisterUrl(newProviderUrl);
        } catch (Exception e) {
            throw new SkipFailbackWrapperException(e);
        }
    }

    private ProviderModel.RegisterStatedURL getStatedUrl(URL registryUrl, URL providerUrl) {
        ProviderModel providerModel =
                frameworkModel.getServiceRepository().lookupExportedService(providerUrl.getServiceKey());

        List<ProviderModel.RegisterStatedURL> statedUrls = providerModel.getStatedUrl();
        return statedUrls.stream()
                .filter(u -> u.getRegistryUrl().equals(registryUrl)
                        && u.getProviderUrl().getProtocol().equals(providerUrl.getProtocol()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("There should have at least one registered url."));
    }

    /**
     * Get an instance of registry based on the address of invoker
     *
     * @param registryUrl
     * @return
     */
    protected Registry getRegistry(final URL registryUrl) {
        RegistryFactory registryFactory = ScopeModelUtil.getExtensionLoader(
                        RegistryFactory.class, registryUrl.getScopeModel())
                .getAdaptiveExtension();
        return registryFactory.getRegistry(registryUrl);
    }

    protected URL getRegistryUrl(Invoker<?> originInvoker) {
        return originInvoker.getUrl();
    }

    protected URL getRegistryUrl(URL url) {
        if (SERVICE_REGISTRY_PROTOCOL.equals(url.getProtocol())) {
            return url;
        }
        return url.addParameter(REGISTRY_KEY, url.getProtocol()).setProtocol(SERVICE_REGISTRY_PROTOCOL);
    }

    /**
     * Return the url that is registered to the registry and filter the url parameter once
     *
     * @param providerUrl provider service url
     * @param registryUrl registry center url
     * @return url to registry.
     */
    private URL customizeURL(final URL providerUrl, final URL registryUrl) {
        URL newProviderURL = providerUrl.putAttribute(SIMPLIFIED_KEY, registryUrl.getParameter(SIMPLIFIED_KEY, false));
        newProviderURL = newProviderURL.putAttribute(EXTRA_KEYS_KEY, registryUrl.getParameter(EXTRA_KEYS_KEY, ""));
        ApplicationModel applicationModel = providerUrl.getOrDefaultApplicationModel();
        ExtensionLoader<ServiceURLCustomizer> loader = applicationModel.getExtensionLoader(ServiceURLCustomizer.class);
        for (ServiceURLCustomizer customizer : loader.getSupportedExtensionInstances()) {
            newProviderURL = customizer.customize(newProviderURL, applicationModel);
        }
        return newProviderURL;
    }

    private URL getSubscribedOverrideUrl(URL registeredProviderUrl) {
        return registeredProviderUrl
                .setProtocol(PROVIDER_PROTOCOL)
                .addParameters(CATEGORY_KEY, CONFIGURATORS_CATEGORY, CHECK_KEY, String.valueOf(false));
    }

    /**
     * Get the address of the providerUrl through the url of the invoker
     *
     * @param originInvoker
     * @return
     */
    private URL getProviderUrl(final Invoker<?> originInvoker) {
        Object providerURL = originInvoker.getUrl().getAttribute(EXPORT_KEY);
        if (!(providerURL instanceof URL)) {
            throw new IllegalArgumentException("The registry export url is null! registry: "
                    + originInvoker.getUrl().getAddress());
        }
        return (URL) providerURL;
    }

    /**
     * Get the key cached in bounds by invoker
     *
     * @param originInvoker
     * @return
     */
    private String getProviderUrlKey(final Invoker<?> originInvoker) {
        URL providerUrl = getProviderUrl(originInvoker);
        return providerUrl.removeParameters(DYNAMIC_KEY, ENABLED_KEY).toFullString();
    }

    private String getRegistryUrlKey(final Invoker<?> originInvoker) {
        URL registryUrl = getRegistryUrl(originInvoker);
        return registryUrl.removeParameters(DYNAMIC_KEY, ENABLED_KEY).toFullString();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Invoker<T> refer(Class<T> type, URL url) throws RpcException {
        url = getRegistryUrl(url);
        Registry registry = getRegistry(url);
        if (RegistryService.class.equals(type)) {
            return proxyFactory.getInvoker((T) registry, type, url);
        }

        // group="a,b" or group="*"
        Map<String, String> qs = (Map<String, String>) url.getAttribute(REFER_KEY);
        String group = qs.get(GROUP_KEY);
        if (StringUtils.isNotEmpty(group)) {
            if ((COMMA_SPLIT_PATTERN.split(group)).length > 1 || "*".equals(group)) {
                return doRefer(
                        Cluster.getCluster(url.getScopeModel(), MERGEABLE_CLUSTER_NAME), registry, type, url, qs);
            }
        }

        Cluster cluster = Cluster.getCluster(url.getScopeModel(), qs.get(CLUSTER_KEY));
        return doRefer(cluster, registry, type, url, qs);
    }

    protected <T> Invoker<T> doRefer(
            Cluster cluster, Registry registry, Class<T> type, URL url, Map<String, String> parameters) {
        Map<String, Object> consumerAttribute = new HashMap<>(url.getAttributes());
        consumerAttribute.remove(REFER_KEY);
        String p = isEmpty(parameters.get(PROTOCOL_KEY)) ? CONSUMER : parameters.get(PROTOCOL_KEY);
        URL consumerUrl = new ServiceConfigURL(
                p,
                null,
                null,
                parameters.get(REGISTER_IP_KEY),
                0,
                getPath(parameters, type),
                parameters,
                consumerAttribute);
        url = url.putAttribute(CONSUMER_URL_KEY, consumerUrl);
        ClusterInvoker<T> migrationInvoker = getMigrationInvoker(this, cluster, registry, type, url, consumerUrl);
        return interceptInvoker(migrationInvoker, url, consumerUrl);
    }

    private String getPath(Map<String, String> parameters, Class<?> type) {
        return !ProtocolUtils.isGeneric(parameters.get(GENERIC_KEY)) ? type.getName() : parameters.get(INTERFACE_KEY);
    }

    protected <T> ClusterInvoker<T> getMigrationInvoker(
            RegistryProtocol registryProtocol,
            Cluster cluster,
            Registry registry,
            Class<T> type,
            URL url,
            URL consumerUrl) {
        return new ServiceDiscoveryMigrationInvoker<>(registryProtocol, cluster, registry, type, url, consumerUrl);
    }

    /**
     * This method tries to load all RegistryProtocolListener definitions, which are used to control the behaviour of invoker by interacting with defined, then uses those listeners to
     * change the status and behaviour of the MigrationInvoker.
     * <p>
     * Currently available Listener is MigrationRuleListener, one used to control the Migration behaviour with dynamically changing rules.
     *
     * @param invoker     MigrationInvoker that determines which type of invoker list to use
     * @param url         The original url generated during refer, more like a registry:// style url
     * @param consumerUrl Consumer url representing current interface and its config
     * @param <T>         The service definition
     * @return The @param MigrationInvoker passed in
     */
    protected <T> Invoker<T> interceptInvoker(ClusterInvoker<T> invoker, URL url, URL consumerUrl) {
        List<RegistryProtocolListener> listeners = findRegistryProtocolListeners(url);
        if (CollectionUtils.isEmpty(listeners)) {
            return invoker;
        }

        for (RegistryProtocolListener listener : listeners) {
            listener.onRefer(this, invoker, consumerUrl, url);
        }
        return invoker;
    }

    public <T> ClusterInvoker<T> getServiceDiscoveryInvoker(
            Cluster cluster, Registry registry, Class<T> type, URL url) {
        DynamicDirectory<T> directory = new ServiceDiscoveryRegistryDirectory<>(type, url);
        return doCreateInvoker(directory, cluster, registry, type);
    }

    public <T> ClusterInvoker<T> getInvoker(Cluster cluster, Registry registry, Class<T> type, URL url) {
        // FIXME, this method is currently not used, create the right registry before enable.
        DynamicDirectory<T> directory = new RegistryDirectory<>(type, url);
        return doCreateInvoker(directory, cluster, registry, type);
    }

    protected <T> ClusterInvoker<T> doCreateInvoker(
            DynamicDirectory<T> directory, Cluster cluster, Registry registry, Class<T> type) {
        directory.setRegistry(registry);
        directory.setProtocol(protocol);
        // all attributes of REFER_KEY
        Map<String, String> parameters =
                new HashMap<>(directory.getConsumerUrl().getParameters());
        URL urlToRegistry = new ServiceConfigURL(
                parameters.get(PROTOCOL_KEY) == null ? CONSUMER : parameters.get(PROTOCOL_KEY),
                parameters.remove(REGISTER_IP_KEY),
                0,
                getPath(parameters, type),
                parameters);
        urlToRegistry = urlToRegistry.setScopeModel(directory.getConsumerUrl().getScopeModel());
        urlToRegistry = urlToRegistry.setServiceModel(directory.getConsumerUrl().getServiceModel());
        if (directory.isShouldRegister()) {
            directory.setRegisteredConsumerUrl(urlToRegistry);
            registry.register(directory.getRegisteredConsumerUrl());
        }
        directory.buildRouterChain(urlToRegistry);
        directory.subscribe(toSubscribeUrl(urlToRegistry));

        return (ClusterInvoker<T>) cluster.join(directory, true);
    }

    public <T> void reRefer(ClusterInvoker<?> invoker, URL newSubscribeUrl) {
        if (!(invoker instanceof MigrationClusterInvoker)) {
            logger.error(
                    REGISTRY_UNSUPPORTED_CATEGORY,
                    "",
                    "",
                    "Only invoker type of MigrationClusterInvoker supports reRefer, current invoker is "
                            + invoker.getClass());
            return;
        }

        MigrationClusterInvoker<?> migrationClusterInvoker = (MigrationClusterInvoker<?>) invoker;
        migrationClusterInvoker.reRefer(newSubscribeUrl);
    }

    public static URL toSubscribeUrl(URL url) {
        return url.addParameter(CATEGORY_KEY, ALL_CATEGORIES);
    }

    protected List<RegistryProtocolListener> findRegistryProtocolListeners(URL url) {
        return ScopeModelUtil.getExtensionLoader(RegistryProtocolListener.class, url.getScopeModel())
                .getActivateExtension(url, REGISTRY_PROTOCOL_LISTENER_KEY);
    }

    @Override
    public void destroy() {
        // FIXME all application models in framework are removed at this moment
        for (ApplicationModel applicationModel : frameworkModel.getApplicationModels()) {
            for (ModuleModel moduleModel : applicationModel.getModuleModels()) {
                List<RegistryProtocolListener> listeners = moduleModel
                        .getExtensionLoader(RegistryProtocolListener.class)
                        .getLoadedExtensionInstances();
                if (CollectionUtils.isNotEmpty(listeners)) {
                    for (RegistryProtocolListener listener : listeners) {
                        listener.onDestroy();
                    }
                }
            }
        }

        for (ApplicationModel applicationModel : frameworkModel.getApplicationModels()) {
            if (applicationModel
                    .modelEnvironment()
                    .getConfiguration()
                    .convert(Boolean.class, org.apache.dubbo.registry.Constants.ENABLE_CONFIGURATION_LISTEN, true)) {
                for (ModuleModel moduleModel : applicationModel.getPubModuleModels()) {
                    String applicationName = applicationModel.tryGetApplicationName();
                    if (applicationName == null) {
                        // already removed
                        continue;
                    }
                    if (!moduleModel
                            .getServiceRepository()
                            .getExportedServices()
                            .isEmpty()) {
                        moduleModel
                                .getExtensionLoader(GovernanceRuleRepository.class)
                                .getDefaultExtension()
                                .removeListener(
                                        applicationName + CONFIGURATORS_SUFFIX,
                                        getProviderConfigurationListener(moduleModel));
                    }
                }
            }
        }

        List<Exporter<?>> exporters =
                bounds.values().stream().flatMap(e -> e.values().stream()).collect(Collectors.toList());
        for (Exporter<?> exporter : exporters) {
            exporter.unexport();
        }
        bounds.clear();
    }

    @Override
    public List<ProtocolServer> getServers() {
        return protocol.getServers();
    }

    // Merge the urls of configurators
    private static URL getConfiguredInvokerUrl(List<Configurator> configurators, URL url) {
        if (CollectionUtils.isNotEmpty(configurators)) {
            for (Configurator configurator : configurators) {
                url = configurator.configure(url);
            }
        }
        return url;
    }

    public static class InvokerDelegate<T> extends InvokerWrapper<T> {

        /**
         * @param invoker
         * @param url     invoker.getUrl return this value
         */
        public InvokerDelegate(Invoker<T> invoker, URL url) {
            super(invoker, url);
        }

        public Invoker<T> getInvoker() {
            if (invoker instanceof InvokerDelegate) {
                return ((InvokerDelegate<T>) invoker).getInvoker();
            } else {
                return invoker;
            }
        }
    }

    private static class DestroyableExporter<T> implements Exporter<T> {

        private Exporter<T> exporter;

        public DestroyableExporter(Exporter<T> exporter) {
            this.exporter = exporter;
        }

        @Override
        public Invoker<T> getInvoker() {
            return exporter.getInvoker();
        }

        @Override
        public void unexport() {
            exporter.unexport();
        }

        @Override
        public void register() {
            exporter.register();
        }

        @Override
        public void unregister() {
            exporter.unregister();
        }
    }

    /**
     * Reexport: the exporter destroy problem in protocol
     * 1.Ensure that the exporter returned by registry protocol can be normal destroyed
     * 2.No need to re-register to the registry after notify
     * 3.The invoker passed by the export method , would better to be the invoker of exporter
     */
    private class OverrideListener implements NotifyListener {
        private final URL subscribeUrl;
        private final Invoker originInvoker;

        private List<Configurator> configurators;

        public OverrideListener(URL subscribeUrl, Invoker originalInvoker) {
            this.subscribeUrl = subscribeUrl;
            this.originInvoker = originalInvoker;
        }

        /**
         * @param urls The list of registered information, is always not empty, The meaning is the same as the
         *             return value of {@link org.apache.dubbo.registry.RegistryService#lookup(URL)}.
         */
        @Override
        public synchronized void notify(List<URL> urls) {
            if (logger.isDebugEnabled()) {
                logger.debug("original override urls: " + urls);
            }

            List<URL> matchedUrls = getMatchedUrls(urls, subscribeUrl);
            if (logger.isDebugEnabled()) {
                logger.debug("subscribe url: " + subscribeUrl + ", override urls: " + matchedUrls);
            }

            // No matching results
            if (matchedUrls.isEmpty()) {
                return;
            }

            this.configurators = Configurator.toConfigurators(classifyUrls(matchedUrls, UrlUtils::isConfigurator))
                    .orElse(configurators);

            ApplicationDeployer deployer =
                    subscribeUrl.getOrDefaultApplicationModel().getDeployer();

            try {
                deployer.increaseServiceRefreshCount();
                doOverrideIfNecessary();
            } finally {
                deployer.decreaseServiceRefreshCount();
            }
        }

        public synchronized void doOverrideIfNecessary() {
            final Invoker<?> invoker;
            if (originInvoker instanceof InvokerDelegate) {
                invoker = ((InvokerDelegate<?>) originInvoker).getInvoker();
            } else {
                invoker = originInvoker;
            }
            // The origin invoker
            URL originUrl = RegistryProtocol.this.getProviderUrl(invoker);
            String providerUrlKey = getProviderUrlKey(originInvoker);
            String registryUrlKey = getRegistryUrlKey(originInvoker);
            Map<String, ExporterChangeableWrapper<?>> exporterMap = bounds.get(providerUrlKey);
            if (exporterMap == null) {
                logger.warn(
                        INTERNAL_ERROR,
                        "error state, exporterMap can not be null",
                        "",
                        "error state, exporterMap can not be null",
                        new IllegalStateException("error state, exporterMap can not be null"));
                return;
            }
            ExporterChangeableWrapper<?> exporter = exporterMap.get(registryUrlKey);
            if (exporter == null) {
                logger.warn(
                        INTERNAL_ERROR,
                        "unknown error in registry module",
                        "",
                        "error state, exporter should not be null",
                        new IllegalStateException("error state, exporter should not be null"));
                return;
            }
            // The current, may have been merged many times
            Invoker<?> exporterInvoker = exporter.getInvoker();
            URL currentUrl = exporterInvoker == null ? null : exporterInvoker.getUrl();
            // Merged with this configuration
            URL newUrl = getConfiguredInvokerUrl(configurators, originUrl);
            newUrl = getConfiguredInvokerUrl(
                    getProviderConfigurationListener(originUrl).getConfigurators(), newUrl);
            newUrl = getConfiguredInvokerUrl(
                    serviceConfigurationListeners.get(originUrl.getServiceKey()).getConfigurators(), newUrl);
            if (!newUrl.equals(currentUrl)) {
                if (newUrl.getParameter(Constants.NEED_REEXPORT, true)) {
                    RegistryProtocol.this.reExport(originInvoker, newUrl);
                }
                logger.info("exported provider url changed, origin url: " + originUrl + ", old export url: "
                        + currentUrl + ", new export url: " + newUrl);
            }
        }

        private List<URL> getMatchedUrls(List<URL> configuratorUrls, URL currentSubscribe) {
            List<URL> result = new ArrayList<>();
            for (URL url : configuratorUrls) {
                URL overrideUrl = url;
                // Compatible with the old version
                if (url.getCategory() == null && OVERRIDE_PROTOCOL.equals(url.getProtocol())) {
                    overrideUrl = url.addParameter(CATEGORY_KEY, CONFIGURATORS_CATEGORY);
                }

                // Check whether url is to be applied to the current service
                if (UrlUtils.isMatch(currentSubscribe, overrideUrl)) {
                    result.add(url);
                }
            }
            return result;
        }
    }

    private ProviderConfigurationListener getProviderConfigurationListener(URL url) {
        return getProviderConfigurationListener(url.getOrDefaultModuleModel());
    }

    private ProviderConfigurationListener getProviderConfigurationListener(ModuleModel moduleModel) {
        return moduleModel
                .getBeanFactory()
                .getOrRegisterBean(
                        ProviderConfigurationListener.class, type -> new ProviderConfigurationListener(moduleModel));
    }

    private class ServiceConfigurationListener extends AbstractConfiguratorListener {
        private URL providerUrl;
        private OverrideListener notifyListener;

        private final ModuleModel moduleModel;

        public ServiceConfigurationListener(ModuleModel moduleModel, URL providerUrl, OverrideListener notifyListener) {
            super(moduleModel);
            this.providerUrl = providerUrl;
            this.notifyListener = notifyListener;
            this.moduleModel = moduleModel;
            if (moduleModel
                    .modelEnvironment()
                    .getConfiguration()
                    .convert(Boolean.class, ENABLE_CONFIGURATION_LISTEN, true)) {
                this.initWith(DynamicConfiguration.getRuleKey(providerUrl) + CONFIGURATORS_SUFFIX);
            }
        }

        private <T> URL overrideUrl(URL providerUrl) {
            return RegistryProtocol.getConfiguredInvokerUrl(configurators, providerUrl);
        }

        @Override
        protected void notifyOverrides() {
            ApplicationDeployer deployer =
                    this.moduleModel.getApplicationModel().getDeployer();
            try {
                deployer.increaseServiceRefreshCount();
                notifyListener.doOverrideIfNecessary();
            } finally {
                deployer.decreaseServiceRefreshCount();
            }
        }
    }

    private class ProviderConfigurationListener extends AbstractConfiguratorListener {

        private final ConcurrentHashMap<URL, Set<NotifyListener>> overrideListeners = new ConcurrentHashMap<>();

        private final ModuleModel moduleModel;

        public ProviderConfigurationListener(ModuleModel moduleModel) {
            super(moduleModel);
            this.moduleModel = moduleModel;
            if (moduleModel.modelEnvironment().getConfiguration().getBoolean(ENABLE_CONFIGURATION_LISTEN, true)) {
                this.initWith(moduleModel.getApplicationModel().getApplicationName() + CONFIGURATORS_SUFFIX);
            }
        }

        /**
         * Get existing configuration rule and override provider url before exporting.
         *
         * @param providerUrl
         * @param <T>
         * @return
         */
        private <T> URL overrideUrl(URL providerUrl) {
            return RegistryProtocol.getConfiguredInvokerUrl(configurators, providerUrl);
        }

        @Override
        protected void notifyOverrides() {
            ApplicationDeployer deployer =
                    this.moduleModel.getApplicationModel().getDeployer();
            try {
                deployer.increaseServiceRefreshCount();
                overrideListeners.values().forEach(listeners -> {
                    for (NotifyListener listener : listeners) {
                        ((OverrideListener) listener).doOverrideIfNecessary();
                    }
                });
            } finally {
                deployer.decreaseServiceRefreshCount();
            }
        }

        public ConcurrentHashMap<URL, Set<NotifyListener>> getOverrideListeners() {
            return overrideListeners;
        }
    }

    /**
     * exporter proxy, establish the corresponding relationship between the returned exporter and the exporter
     * exported by the protocol, and can modify the relationship at the time of override.
     *
     * @param <T>
     */
    private class ExporterChangeableWrapper<T> implements Exporter<T> {

        private final ScheduledExecutorService executor;

        private final Invoker<T> originInvoker;
        private Exporter<T> exporter;
        private URL subscribeUrl;
        private URL registerUrl;

        private NotifyListener notifyListener;
        private final AtomicBoolean registered = new AtomicBoolean(false);

        public ExporterChangeableWrapper(ReferenceCountExporter<T> exporter, Invoker<T> originInvoker) {
            this.exporter = exporter;
            exporter.increaseCount();
            this.originInvoker = originInvoker;
            FrameworkExecutorRepository frameworkExecutorRepository = originInvoker
                    .getUrl()
                    .getOrDefaultFrameworkModel()
                    .getBeanFactory()
                    .getBean(FrameworkExecutorRepository.class);
            this.executor = frameworkExecutorRepository.getSharedScheduledExecutor();
        }

        public Invoker<T> getOriginInvoker() {
            return originInvoker;
        }

        @Override
        public Invoker<T> getInvoker() {
            return exporter.getInvoker();
        }

        public void setExporter(Exporter<T> exporter) {
            this.exporter = exporter;
        }

        @Override
        public void register() {
            if (registered.compareAndSet(false, true)) {
                URL registryUrl = getRegistryUrl(originInvoker);
                Registry registry = getRegistry(registryUrl);
                RegistryProtocol.register(registry, getRegisterUrl());

                ProviderModel providerModel = frameworkModel
                        .getServiceRepository()
                        .lookupExportedService(getRegisterUrl().getServiceKey());

                List<ProviderModel.RegisterStatedURL> statedUrls = providerModel.getStatedUrl();
                statedUrls.stream()
                        .filter(u -> u.getRegistryUrl().equals(registryUrl)
                                && u.getProviderUrl()
                                        .getProtocol()
                                        .equals(getRegisterUrl().getProtocol()))
                        .forEach(u -> u.setRegistered(true));
                logger.info("[INSTANCE_REGISTER] Registered dubbo service "
                        + getRegisterUrl().getServiceKey() + " url " + getRegisterUrl() + " to registry "
                        + registryUrl);
            }
        }

        @Override
        public synchronized void unregister() {
            if (registered.compareAndSet(true, false)) {
                URL registryUrl = getRegistryUrl(originInvoker);
                Registry registry = RegistryProtocol.this.getRegistry(registryUrl);

                ProviderModel providerModel = frameworkModel
                        .getServiceRepository()
                        .lookupExportedService(getRegisterUrl().getServiceKey());

                List<ProviderModel.RegisterStatedURL> statedURLs = providerModel.getStatedUrl().stream()
                        .filter(u -> u.getRegistryUrl().equals(registryUrl)
                                && u.getProviderUrl()
                                        .getProtocol()
                                        .equals(getRegisterUrl().getProtocol()))
                        .collect(Collectors.toList());
                if (statedURLs.isEmpty()
                        || statedURLs.stream().anyMatch(ProviderModel.RegisterStatedURL::isRegistered)) {
                    try {
                        registry.unregister(registerUrl);
                    } catch (Throwable t) {
                        logger.warn(INTERNAL_ERROR, "unknown error in registry module", "", t.getMessage(), t);
                    }
                }

                try {
                    if (subscribeUrl != null) {
                        Map<URL, Set<NotifyListener>> overrideListeners =
                                getProviderConfigurationListener(subscribeUrl).getOverrideListeners();
                        Set<NotifyListener> listeners = overrideListeners.get(subscribeUrl);
                        if (listeners != null) {
                            if (listeners.remove(notifyListener)) {
                                ApplicationModel applicationModel = getApplicationModel(registerUrl.getScopeModel());
                                if (applicationModel
                                        .modelEnvironment()
                                        .getConfiguration()
                                        .convert(Boolean.class, ENABLE_26X_CONFIGURATION_LISTEN, true)) {
                                    if (!registry.isServiceDiscovery()) {
                                        registry.unsubscribe(subscribeUrl, notifyListener);
                                    }
                                }
                                if (applicationModel
                                        .modelEnvironment()
                                        .getConfiguration()
                                        .convert(Boolean.class, ENABLE_CONFIGURATION_LISTEN, true)) {
                                    for (ModuleModel moduleModel : applicationModel.getPubModuleModels()) {
                                        if (null != moduleModel.getServiceRepository()
                                                && !moduleModel
                                                        .getServiceRepository()
                                                        .getExportedServices()
                                                        .isEmpty()) {
                                            moduleModel
                                                    .getExtensionLoader(GovernanceRuleRepository.class)
                                                    .getDefaultExtension()
                                                    .removeListener(
                                                            subscribeUrl.getServiceKey() + CONFIGURATORS_SUFFIX,
                                                            serviceConfigurationListeners.remove(
                                                                    subscribeUrl.getServiceKey()));
                                        }
                                    }
                                }
                            }
                            if (listeners.isEmpty()) {
                                overrideListeners.remove(subscribeUrl);
                            }
                        }
                    }
                } catch (Throwable t) {
                    logger.warn(INTERNAL_ERROR, "unknown error in registry module", "", t.getMessage(), t);
                }
            }
        }

        @Override
        public synchronized void unexport() {
            String providerUrlKey = getProviderUrlKey(this.originInvoker);
            String registryUrlKey = getRegistryUrlKey(this.originInvoker);
            Map<String, ExporterChangeableWrapper<?>> exporterMap = bounds.remove(providerUrlKey);
            if (exporterMap != null) {
                exporterMap.remove(registryUrlKey);
            }

            unregister();
            doUnExport();
        }

        public void setRegistered(boolean registered) {
            this.registered.set(registered);
        }

        public boolean isRegistered() {
            return registered.get();
        }

        private void doUnExport() {
            try {
                exporter.unexport();
            } catch (Throwable t) {
                logger.warn(INTERNAL_ERROR, "unknown error in registry module", "", t.getMessage(), t);
            }
        }

        public void setSubscribeUrl(URL subscribeUrl) {
            this.subscribeUrl = subscribeUrl;
        }

        public void setRegisterUrl(URL registerUrl) {
            this.registerUrl = registerUrl;
        }

        public void setNotifyListener(NotifyListener notifyListener) {
            this.notifyListener = notifyListener;
        }

        public URL getRegisterUrl() {
            return registerUrl;
        }
    }
}
