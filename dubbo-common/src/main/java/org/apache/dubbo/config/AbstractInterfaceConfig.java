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
package org.apache.dubbo.config;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.Version;
import org.apache.dubbo.common.aot.NativeDetector;
import org.apache.dubbo.common.compiler.support.AdaptiveCompiler;
import org.apache.dubbo.common.config.ConfigurationUtils;
import org.apache.dubbo.common.config.Environment;
import org.apache.dubbo.common.config.InmemoryConfiguration;
import org.apache.dubbo.common.utils.ClassUtils;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.ConfigUtils;
import org.apache.dubbo.common.utils.JsonUtils;
import org.apache.dubbo.common.utils.ReflectUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.config.support.Parameter;
import org.apache.dubbo.rpc.model.ApplicationModel;
import org.apache.dubbo.rpc.model.ModuleModel;
import org.apache.dubbo.rpc.model.ScopeModel;
import org.apache.dubbo.rpc.model.ScopeModelUtil;
import org.apache.dubbo.rpc.model.ServiceMetadata;

import java.beans.Transient;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.apache.dubbo.common.constants.CommonConstants.COMMA_SPLIT_PATTERN;
import static org.apache.dubbo.common.constants.CommonConstants.DUBBO_VERSION_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.INVOKER_LISTENER_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PID_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.REFERENCE_FILTER_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.RELEASE_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.TAG_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.TIMESTAMP_KEY;
import static org.apache.dubbo.common.constants.LoggerCodeConstants.CONFIG_NO_METHOD_FOUND;
import static org.apache.dubbo.config.Constants.DEFAULT_NATIVE_PROXY;

/**
 * Abstract configuration for the interface.
 *
 * @export
 */
public abstract class AbstractInterfaceConfig extends AbstractMethodConfig {

    private static final long serialVersionUID = -1559314110797223229L;

    /**
     * Interface name of the exported service.
     */
    protected String interfaceName;

    /**
     * ClassLoader associated with the interface.
     */
    protected transient ClassLoader interfaceClassLoader;

    /**
     * Version of the remote service referenced by the consumer/provider.
     */
    protected String version;

    /**
     * Group of the remote service referenced by the consumer/provider.
     */
    protected String group;

    /**
     * Service metadata configuration.
     */
    protected ServiceMetadata serviceMetadata;

    /**
     * Local implementation class name for the service interface.
     */
    protected String local;

    /**
     * Local stub class name for the service interface.
     */
    protected String stub;

    /**
     * Service monitoring configuration.
     */
    protected MonitorConfig monitor;

    /**
     * Strategy for generating dynamic agents (options: "jdk" or "javassist").
     */
    protected String proxy;

    /**
     * Cluster type for service.
     */
    protected String cluster;

    /**
     * Filters for service exposure or reference (multiple filters can be separated by commas).
     */
    protected String filter;

    /**
     * Listeners for service exposure or reference (multiple listeners can be separated by commas).
     */
    protected String listener;

    /**
     * Owner of the service providers.
     */
    protected String owner;

    /**
     * Connection limits: 0 for shared connection, otherwise specifying connections for the service.
     */
    protected Integer connections;

    /**
     * Layer of service providers.
     */
    protected String layer;

    /**
     * Application configuration for the service.
     */
    protected ApplicationConfig application;

    /**
     * Module configuration for the service.
     */
    protected ModuleConfig module;

    /**
     * Registries where the service will be registered (use this or registryIds, not both).
     */
    protected List<RegistryConfig> registries;

    /**
     * Method-specific configuration.
     */
    private List<MethodConfig> methods;

    /**
     * Registry IDs for service registration (use this or registries, not both).
     */
    protected String registryIds;

    /**
     * Event handler for connection establishment.
     */
    protected String onconnect;

    /**
     * Event handler for disconnection.
     */
    protected String ondisconnect;

    /**
     * Metadata report configuration.
     */
    protected MetadataReportConfig metadataReportConfig;

    /**
     * Configuration center settings.
     */
    protected ConfigCenterConfig configCenter;

    /**
     * Callback limits for the service.
     */
    private Integer callbacks;

    /**
     * Service scope ("local" implies searching in the current JVM only).
     */
    private String scope;

    /**
     * Custom tag for the service configuration.
     */
    protected String tag;

    /**
     * Enable service authentication.
     */
    private Boolean auth;

    /**
     * Authenticator for authentication
     */
    private String authenticator;

    /**
     * Username for basic authenticator
     */
    private String username;

    /**
     * Password for basic authenticator
     */
    private String password;

    /**
     * Use separate instances for services with the same serviceKey (applies when using ReferenceConfig and SimpleReferenceCache together).
     * Directly calling ReferenceConfig.get() will not check this attribute.
     */
    private Boolean singleton;

    public AbstractInterfaceConfig() {}

    public AbstractInterfaceConfig(ModuleModel moduleModel) {
        super(moduleModel);
    }

    /**
     * The url of the reference service
     */
    protected final transient List<URL> urls = new ArrayList<>();

    @Transient
    public List<URL> getExportedUrls() {
        return urls;
    }

    public URL toUrl() {
        return urls.isEmpty() ? null : urls.iterator().next();
    }

    public List<URL> toUrls() {
        return urls;
    }

    @Override
    protected void postProcessAfterScopeModelChanged(ScopeModel oldScopeModel, ScopeModel newScopeModel) {
        super.postProcessAfterScopeModelChanged(oldScopeModel, newScopeModel);
        // change referenced config's scope model
        ApplicationModel applicationModel = ScopeModelUtil.getApplicationModel(getScopeModel());
        if (this.configCenter != null && this.configCenter.getScopeModel() != applicationModel) {
            this.configCenter.setScopeModel(applicationModel);
        }
        if (this.metadataReportConfig != null && this.metadataReportConfig.getScopeModel() != applicationModel) {
            this.metadataReportConfig.setScopeModel(applicationModel);
        }
        if (this.monitor != null && this.monitor.getScopeModel() != applicationModel) {
            this.monitor.setScopeModel(applicationModel);
        }
        if (CollectionUtils.isNotEmpty(this.registries)) {
            this.registries.forEach(registryConfig -> {
                if (registryConfig.getScopeModel() != applicationModel) {
                    registryConfig.setScopeModel(applicationModel);
                }
            });
        }
    }

    /**
     * Check whether the registry config is exists, and then conversion it to {@link RegistryConfig}
     */
    protected void checkRegistry() {
        convertRegistryIdsToRegistries();

        for (RegistryConfig registryConfig : registries) {
            if (!registryConfig.isValid()) {
                throw new IllegalStateException("No registry config found or it's not a valid config! "
                        + "The registry config is: " + registryConfig);
            }
        }
    }

    public static void appendRuntimeParameters(Map<String, String> map) {
        map.put(DUBBO_VERSION_KEY, Version.getProtocolVersion());
        map.put(RELEASE_KEY, Version.getVersion());
        map.put(TIMESTAMP_KEY, String.valueOf(System.currentTimeMillis()));
        if (ConfigUtils.getPid() > 0) {
            map.put(PID_KEY, String.valueOf(ConfigUtils.getPid()));
        }
    }

    /**
     * To obtain the method list in the port, use reflection when in native mode and javassist otherwise.
     *
     * @param interfaceClass
     * @return
     */
    protected String[] methods(Class<?> interfaceClass) {
        if (NativeDetector.inNativeImage()) {
            return Arrays.stream(interfaceClass.getMethods())
                    .map(Method::getName)
                    .toArray(String[]::new);
        } else {
            return ClassUtils.getMethodNames(interfaceClass);
        }
    }

    protected Environment getEnvironment() {
        return getScopeModel().modelEnvironment();
    }

    @Override
    protected void processExtraRefresh(String preferredPrefix, InmemoryConfiguration subPropsConfiguration) {
        if (StringUtils.hasText(interfaceName)) {
            Class<?> interfaceClass;
            try {
                interfaceClass = ClassUtils.forName(interfaceName);
            } catch (ClassNotFoundException e) {
                // There may be no interface class when generic call
                return;
            }

            checkInterface();

            // Auto create MethodConfig/ArgumentConfig according to config props
            Map<String, String> configProperties = subPropsConfiguration.getProperties();
            Method[] methods;
            try {
                methods = interfaceClass.getMethods();
            } catch (Throwable e) {
                // NoClassDefFoundError may be thrown if interface class's dependency jar is missing
                return;
            }

            for (Method method : methods) {
                if (ConfigurationUtils.hasSubProperties(configProperties, method.getName())) {
                    MethodConfig methodConfig = getMethodByName(method.getName());
                    // Add method config if not found
                    if (methodConfig == null) {
                        methodConfig = new MethodConfig();
                        methodConfig.setName(method.getName());
                        this.addMethod(methodConfig);
                    }
                    // Add argument config
                    // dubbo.service.{interfaceName}.{methodName}.{arg-index}.xxx=xxx
                    java.lang.reflect.Parameter[] arguments = method.getParameters();
                    for (int i = 0; i < arguments.length; i++) {
                        if (getArgumentByIndex(methodConfig, i) == null
                                && hasArgumentConfigProps(configProperties, methodConfig.getName(), i)) {

                            ArgumentConfig argumentConfig = new ArgumentConfig();
                            argumentConfig.setIndex(i);
                            methodConfig.addArgument(argumentConfig);
                        }
                    }
                }
            }

            // refresh MethodConfigs
            List<MethodConfig> methodConfigs = this.getMethods();
            if (methodConfigs != null && methodConfigs.size() > 0) {
                // whether ignore invalid method config
                Object ignoreInvalidMethodConfigVal = getEnvironment()
                        .getConfiguration()
                        .getProperty(ConfigKeys.DUBBO_CONFIG_IGNORE_INVALID_METHOD_CONFIG, "false");
                boolean ignoreInvalidMethodConfig = Boolean.parseBoolean(ignoreInvalidMethodConfigVal.toString());

                Class<?> finalInterfaceClass = interfaceClass;
                List<MethodConfig> validMethodConfigs = methodConfigs.stream()
                        .filter(methodConfig -> {
                            methodConfig.setParentPrefix(preferredPrefix);
                            methodConfig.setScopeModel(getScopeModel());
                            methodConfig.refresh();
                            // verify method config
                            return verifyMethodConfig(methodConfig, finalInterfaceClass, ignoreInvalidMethodConfig);
                        })
                        .collect(Collectors.toList());
                this.setMethods(validMethodConfigs);
            }
        }
    }

    /**
     * it is used for skipping the check of interface since dubbo 3.2
     * rest and triple protocol allow the service is implement class
     */
    protected void checkInterface() {}

    protected boolean verifyMethodConfig(
            MethodConfig methodConfig, Class<?> interfaceClass, boolean ignoreInvalidMethodConfig) {
        String methodName = methodConfig.getName();
        if (StringUtils.isEmpty(methodName)) {
            String msg = "<dubbo:method> name attribute is required! Please check: " + "<dubbo:service interface=\""
                    + interfaceName + "\" ... >" + "<dubbo:method name=\"\" ... /></<dubbo:reference>";
            if (ignoreInvalidMethodConfig) {
                logger.warn(CONFIG_NO_METHOD_FOUND, "", "", msg);
                return false;
            } else {
                throw new IllegalStateException(msg);
            }
        }

        boolean hasMethod = Arrays.stream(interfaceClass.getMethods())
                .anyMatch(method -> method.getName().equals(methodName));
        if (!hasMethod) {
            String msg = "Found invalid method config, the interface " + interfaceClass.getName()
                    + " not found method \"" + methodName + "\" : [" + methodConfig + "]";
            if (ignoreInvalidMethodConfig) {
                logger.warn(CONFIG_NO_METHOD_FOUND, "", "", msg);
                return false;
            } else {
                if (!isNeedCheckMethod()) {
                    msg = "Generic call: " + msg;
                    logger.warn(CONFIG_NO_METHOD_FOUND, "", "", msg);
                } else {
                    throw new IllegalStateException(msg);
                }
            }
        }
        return true;
    }

    private ArgumentConfig getArgumentByIndex(MethodConfig methodConfig, int argIndex) {
        if (methodConfig.getArguments() != null && methodConfig.getArguments().size() > 0) {
            for (ArgumentConfig argument : methodConfig.getArguments()) {
                if (argument.getIndex() != null && argument.getIndex() == argIndex) {
                    return argument;
                }
            }
        }
        return null;
    }

    @Transient
    protected boolean isNeedCheckMethod() {
        return true;
    }

    private boolean hasArgumentConfigProps(Map<String, String> configProperties, String methodName, int argIndex) {
        String argPrefix = methodName + "." + argIndex + ".";
        return ConfigurationUtils.hasSubProperties(configProperties, argPrefix);
    }

    protected MethodConfig getMethodByName(String name) {
        if (methods != null && methods.size() > 0) {
            for (MethodConfig methodConfig : methods) {
                if (StringUtils.isEquals(methodConfig.getName(), name)) {
                    return methodConfig;
                }
            }
        }
        return null;
    }

    /**
     * Legitimacy check of stub, note that: the local will deprecated, and replace with <code>stub</code>
     *
     * @param interfaceClass for provider side, it is the {@link Class} of the service that will be exported; for consumer
     *                       side, it is the {@link Class} of the remote service interface
     */
    protected void checkStubAndLocal(Class<?> interfaceClass) {
        verifyStubAndLocal(local, "Local", interfaceClass);
        verifyStubAndLocal(stub, "Stub", interfaceClass);
    }

    private void verifyStubAndLocal(String className, String label, Class<?> interfaceClass) {
        if (ConfigUtils.isNotEmpty(className)) {
            Class<?> localClass = ConfigUtils.isDefault(className)
                    ? ReflectUtils.forName(interfaceClass.getName() + label)
                    : ReflectUtils.forName(className);
            verify(interfaceClass, localClass);
        }
    }

    private void verify(Class<?> interfaceClass, Class<?> localClass) {
        if (!interfaceClass.isAssignableFrom(localClass)) {
            throw new IllegalStateException("The local implementation class " + localClass.getName()
                    + " not implement interface " + interfaceClass.getName());
        }

        try {
            // Check if the localClass a constructor with parameter whose type is interfaceClass
            ReflectUtils.findConstructor(localClass, interfaceClass);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("No such constructor \"public " + localClass.getSimpleName() + "("
                    + interfaceClass.getName() + ")\" in local implementation class " + localClass.getName());
        }
    }

    private void convertRegistryIdsToRegistries() {
        computeValidRegistryIds();
        if (StringUtils.isEmpty(registryIds)) {
            if (CollectionUtils.isEmpty(registries)) {
                List<RegistryConfig> registryConfigs = getConfigManager().getDefaultRegistries();
                registryConfigs = new ArrayList<>(registryConfigs);
                setRegistries(registryConfigs);
            }
        } else {
            String[] ids = COMMA_SPLIT_PATTERN.split(registryIds);
            List<RegistryConfig> tmpRegistries = new ArrayList<>();
            Arrays.stream(ids).forEach(id -> {
                if (tmpRegistries.stream().noneMatch(reg -> reg.getId().equals(id))) {
                    Optional<RegistryConfig> globalRegistry = getConfigManager().getRegistry(id);
                    if (globalRegistry.isPresent()) {
                        tmpRegistries.add(globalRegistry.get());
                    } else {
                        throw new IllegalStateException("Registry not found: " + id);
                    }
                }
            });
            setRegistries(tmpRegistries);
        }
    }

    protected boolean notHasSelfRegistryProperty() {
        return CollectionUtils.isEmpty(registries) && StringUtils.isEmpty(registryIds);
    }

    protected void completeCompoundConfigs(AbstractInterfaceConfig interfaceConfig) {
        if (interfaceConfig != null) {
            if (application == null) {
                setApplication(interfaceConfig.getApplication());
            }
            if (module == null) {
                setModule(interfaceConfig.getModule());
            }
            if (notHasSelfRegistryProperty()) {
                setRegistries(interfaceConfig.getRegistries());
                setRegistryIds(interfaceConfig.getRegistryIds());
            }
            if (monitor == null) {
                setMonitor(interfaceConfig.getMonitor());
            }
        }
        if (module != null) {
            if (notHasSelfRegistryProperty()) {
                setRegistries(module.getRegistries());
            }
            if (monitor == null) {
                setMonitor(module.getMonitor());
            }
        }
        if (application != null) {
            if (notHasSelfRegistryProperty()) {
                setRegistries(application.getRegistries());
                setRegistryIds(application.getRegistryIds());
            }
            if (monitor == null) {
                setMonitor(application.getMonitor());
            }
        }
    }

    protected void computeValidRegistryIds() {
        if (application != null && notHasSelfRegistryProperty()) {
            setRegistries(application.getRegistries());
            setRegistryIds(application.getRegistryIds());
        }
    }

    /**
     * @return local
     * @deprecated Replace to <code>getStub()</code>
     */
    @Deprecated
    public String getLocal() {
        return local;
    }

    /**
     * @param local
     * @deprecated Replace to <code>setStub(Boolean)</code>
     */
    @Deprecated
    public void setLocal(Boolean local) {
        if (local == null) {
            setLocal((String) null);
        } else {
            setLocal(local.toString());
        }
    }

    /**
     * @param local
     * @deprecated Replace to <code>setStub(String)</code>
     */
    @Deprecated
    public void setLocal(String local) {
        this.local = local;
    }

    public String getStub() {
        return stub;
    }

    public void setStub(Boolean stub) {
        if (stub == null) {
            setStub((String) null);
        } else {
            setStub(stub.toString());
        }
    }

    public void setStub(String stub) {
        this.stub = stub;
    }

    public String getCluster() {
        return cluster;
    }

    public void setCluster(String cluster) {
        this.cluster = cluster;
    }

    public String getProxy() {
        if (NativeDetector.inNativeImage()) {
            return DEFAULT_NATIVE_PROXY;
        } else {
            return this.proxy;
        }
    }

    public void setProxy(String proxy) {
        if (NativeDetector.inNativeImage()) {
            this.proxy = DEFAULT_NATIVE_PROXY;
            AdaptiveCompiler.setDefaultCompiler(DEFAULT_NATIVE_PROXY);
        } else {
            this.proxy = proxy;
        }
    }

    public Integer getConnections() {
        return connections;
    }

    public void setConnections(Integer connections) {
        this.connections = connections;
    }

    @Parameter(key = REFERENCE_FILTER_KEY, append = true)
    public String getFilter() {
        return filter;
    }

    public void setFilter(String filter) {
        this.filter = filter;
    }

    @Parameter(key = INVOKER_LISTENER_KEY, append = true)
    public String getListener() {
        return listener;
    }

    public void setListener(String listener) {
        this.listener = listener;
    }

    public String getLayer() {
        return layer;
    }

    public void setLayer(String layer) {
        this.layer = layer;
    }

    /**
     * Always use the global ApplicationConfig
     */
    public ApplicationConfig getApplication() {
        if (application != null) {
            return application;
        }
        return getConfigManager().getApplicationOrElseThrow();
    }

    /**
     * @param application
     * @deprecated Use {@link org.apache.dubbo.config.AbstractConfig#setScopeModel(ScopeModel)}
     */
    @Deprecated
    public void setApplication(ApplicationConfig application) {
        this.application = application;
        if (application != null) {
            getConfigManager().setApplication(application);
        }
    }

    public ModuleConfig getModule() {
        if (module != null) {
            return module;
        }
        return getModuleConfigManager().getModule().orElse(null);
    }

    /**
     * @param module
     * @deprecated Use {@link org.apache.dubbo.config.AbstractConfig#setScopeModel(ScopeModel)}
     */
    @Deprecated
    public void setModule(ModuleConfig module) {
        this.module = module;
        if (module != null) {
            getModuleConfigManager().setModule(module);
        }
    }

    public RegistryConfig getRegistry() {
        return CollectionUtils.isEmpty(registries) ? null : registries.get(0);
    }

    public void setRegistry(RegistryConfig registry) {
        if (registry != null) {
            List<RegistryConfig> registries = new ArrayList<>(1);
            registries.add(registry);
            setRegistries(registries);
        } else {
            this.registries = null;
        }
    }

    public List<RegistryConfig> getRegistries() {
        return registries;
    }

    @SuppressWarnings({"unchecked"})
    public void setRegistries(List<? extends RegistryConfig> registries) {
        this.registries = (List<RegistryConfig>) registries;
    }

    @Parameter(excluded = true)
    public String getRegistryIds() {
        return registryIds;
    }

    public void setRegistryIds(String registryIds) {
        this.registryIds = registryIds;
    }

    public List<MethodConfig> getMethods() {
        return methods;
    }

    public void setMethods(List<? extends MethodConfig> methods) {
        this.methods = (methods != null) ? new ArrayList<>(methods) : null;
    }

    /**
     * It is only used in native scenarios to get methodConfigs.
     * @param methodsJson
     */
    public void setMethodsJson(List<String> methodsJson) {
        if (methodsJson != null) {
            this.methods = new ArrayList<>();
            methodsJson.forEach(
                    (methodConfigJson) -> methods.add(JsonUtils.toJavaObject(methodConfigJson, MethodConfig.class)));
        } else {
            this.methods = null;
        }
    }

    public void addMethod(MethodConfig methodConfig) {
        if (this.methods == null) {
            this.methods = new ArrayList<>();
        }
        this.methods.add(methodConfig);
    }

    public MonitorConfig getMonitor() {
        if (monitor != null) {
            return monitor;
        }
        // FIXME: instead of return null, we should set default monitor when getMonitor() return null in ConfigManager
        return getConfigManager().getMonitor().orElse(null);
    }

    /**
     * @deprecated Use {@link org.apache.dubbo.config.context.ConfigManager#setMonitor(MonitorConfig)}
     */
    @Deprecated
    public void setMonitor(String monitor) {
        setMonitor(new MonitorConfig(monitor));
    }

    /**
     * @deprecated Use {@link org.apache.dubbo.config.context.ConfigManager#setMonitor(MonitorConfig)}
     */
    @Deprecated
    public void setMonitor(MonitorConfig monitor) {
        this.monitor = monitor;
        if (monitor != null) {
            getConfigManager().setMonitor(monitor);
        }
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        this.owner = owner;
    }

    /**
     * @deprecated Use {@link org.apache.dubbo.config.context.ConfigManager#getConfigCenter(String)}
     */
    @Deprecated
    public ConfigCenterConfig getConfigCenter() {
        if (configCenter != null) {
            return configCenter;
        }
        Collection<ConfigCenterConfig> configCenterConfigs = getConfigManager().getConfigCenters();
        if (CollectionUtils.isNotEmpty(configCenterConfigs)) {
            return configCenterConfigs.iterator().next();
        }
        return null;
    }

    /**
     * @deprecated Use {@link org.apache.dubbo.config.context.ConfigManager#addConfigCenter(ConfigCenterConfig)}
     */
    @Deprecated
    public void setConfigCenter(ConfigCenterConfig configCenter) {
        this.configCenter = configCenter;
        if (configCenter != null) {
            getConfigManager().addConfigCenter(configCenter);
        }
    }

    public Integer getCallbacks() {
        return callbacks;
    }

    public void setCallbacks(Integer callbacks) {
        this.callbacks = callbacks;
    }

    public String getOnconnect() {
        return onconnect;
    }

    public void setOnconnect(String onconnect) {
        this.onconnect = onconnect;
    }

    public String getOndisconnect() {
        return ondisconnect;
    }

    public void setOndisconnect(String ondisconnect) {
        this.ondisconnect = ondisconnect;
    }

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    /**
     * @deprecated Use {@link org.apache.dubbo.config.context.ConfigManager#getMetadataConfigs()}
     */
    @Deprecated
    public MetadataReportConfig getMetadataReportConfig() {
        if (metadataReportConfig != null) {
            return metadataReportConfig;
        }
        Collection<MetadataReportConfig> metadataReportConfigs =
                getConfigManager().getMetadataConfigs();
        if (CollectionUtils.isNotEmpty(metadataReportConfigs)) {
            return metadataReportConfigs.iterator().next();
        }
        return null;
    }

    /**
     * @deprecated Use {@link org.apache.dubbo.config.context.ConfigManager#addMetadataReport(MetadataReportConfig)}
     */
    @Deprecated
    public void setMetadataReportConfig(MetadataReportConfig metadataReportConfig) {
        this.metadataReportConfig = metadataReportConfig;
        if (metadataReportConfig != null) {
            getConfigManager().addMetadataReport(metadataReportConfig);
        }
    }

    @Parameter(key = TAG_KEY)
    public String getTag() {
        return tag;
    }

    public void setTag(String tag) {
        this.tag = tag;
    }

    public Boolean getAuth() {
        return auth;
    }

    public void setAuth(Boolean auth) {
        this.auth = auth;
    }

    public String getAuthenticator() {
        return authenticator;
    }

    public AbstractInterfaceConfig setAuthenticator(String authenticator) {
        this.authenticator = authenticator;
        return this;
    }

    public String getUsername() {
        return username;
    }

    public AbstractInterfaceConfig setUsername(String username) {
        this.username = username;
        return this;
    }

    public String getPassword() {
        return password;
    }

    public AbstractInterfaceConfig setPassword(String password) {
        this.password = password;
        return this;
    }

    public SslConfig getSslConfig() {
        return getConfigManager().getSsl().orElse(null);
    }

    public Boolean getSingleton() {
        return singleton;
    }

    public void setSingleton(Boolean singleton) {
        this.singleton = singleton;
    }

    protected void initServiceMetadata(AbstractInterfaceConfig interfaceConfig) {
        serviceMetadata.setVersion(getVersion(interfaceConfig));
        serviceMetadata.setGroup(getGroup(interfaceConfig));
        serviceMetadata.setDefaultGroup(getGroup(interfaceConfig));
        serviceMetadata.setServiceInterfaceName(getInterface());
    }

    public String getGroup(AbstractInterfaceConfig interfaceConfig) {
        return StringUtils.isEmpty(getGroup())
                ? (interfaceConfig != null ? interfaceConfig.getGroup() : getGroup())
                : getGroup();
    }

    public String getVersion(AbstractInterfaceConfig interfaceConfig) {
        return StringUtils.isEmpty(getVersion())
                ? (interfaceConfig != null ? interfaceConfig.getVersion() : getVersion())
                : getVersion();
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getGroup() {
        return group;
    }

    public void setGroup(String group) {
        this.group = group;
    }

    public String getInterface() {
        return interfaceName;
    }

    public void setInterface(String interfaceName) {
        this.interfaceName = interfaceName;
    }

    @Transient
    public ClassLoader getInterfaceClassLoader() {
        return interfaceClassLoader;
    }

    public void setInterfaceClassLoader(ClassLoader interfaceClassLoader) {
        this.interfaceClassLoader = interfaceClassLoader;
    }
}
