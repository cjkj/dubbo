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
package org.apache.dubbo.rpc.filter;

import org.apache.dubbo.common.beanutil.JavaBeanAccessor;
import org.apache.dubbo.common.beanutil.JavaBeanDescriptor;
import org.apache.dubbo.common.beanutil.JavaBeanSerializeUtil;
import org.apache.dubbo.common.compact.Dubbo2CompactUtils;
import org.apache.dubbo.common.compact.Dubbo2GenericExceptionUtils;
import org.apache.dubbo.common.config.Configuration;
import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.common.constants.LoggerCodeConstants;
import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.common.io.UnsafeByteArrayInputStream;
import org.apache.dubbo.common.io.UnsafeByteArrayOutputStream;
import org.apache.dubbo.common.json.GsonUtils;
import org.apache.dubbo.common.logger.ErrorTypeAwareLogger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.serialize.Serialization;
import org.apache.dubbo.common.utils.ClassUtils;
import org.apache.dubbo.common.utils.PojoUtils;
import org.apache.dubbo.common.utils.ReflectUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.RpcInvocation;
import org.apache.dubbo.rpc.model.ApplicationModel;
import org.apache.dubbo.rpc.model.MethodDescriptor;
import org.apache.dubbo.rpc.model.ScopeModelAware;
import org.apache.dubbo.rpc.model.ServiceModel;
import org.apache.dubbo.rpc.service.GenericException;
import org.apache.dubbo.rpc.service.GenericService;
import org.apache.dubbo.rpc.support.ProtocolUtils;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;

import static org.apache.dubbo.common.constants.CommonConstants.$INVOKE;
import static org.apache.dubbo.common.constants.CommonConstants.$INVOKE_ASYNC;
import static org.apache.dubbo.common.constants.CommonConstants.GENERIC_SERIALIZATION_BEAN;
import static org.apache.dubbo.common.constants.CommonConstants.GENERIC_SERIALIZATION_NATIVE_JAVA;
import static org.apache.dubbo.common.constants.CommonConstants.GENERIC_SERIALIZATION_PROTOBUF;
import static org.apache.dubbo.common.constants.LoggerCodeConstants.CONFIG_FILTER_VALIDATION_EXCEPTION;
import static org.apache.dubbo.rpc.Constants.GENERIC_KEY;

/**
 * GenericInvokerFilter.
 */
@Activate(group = CommonConstants.PROVIDER, order = -20000)
public class GenericFilter implements Filter, Filter.Listener, ScopeModelAware {
    private final ErrorTypeAwareLogger logger = LoggerFactory.getErrorTypeAwareLogger(GenericFilter.class);

    private ApplicationModel applicationModel;

    private final Map<ClassLoader, Map<String, Class<?>>> classCache = new ConcurrentHashMap<>();

    @Override
    public void setApplicationModel(ApplicationModel applicationModel) {
        this.applicationModel = applicationModel;
    }

    @Override
    public Result invoke(Invoker<?> invoker, Invocation inv) throws RpcException {
        if ((inv.getMethodName().equals($INVOKE) || inv.getMethodName().equals($INVOKE_ASYNC))
                && inv.getArguments() != null
                && inv.getArguments().length == 3
                && !GenericService.class.isAssignableFrom(invoker.getInterface())) {
            String name = ((String) inv.getArguments()[0]).trim();
            String[] types = (String[]) inv.getArguments()[1];
            Object[] args = (Object[]) inv.getArguments()[2];
            try {
                Method method = findMethodByMethodSignature(invoker.getInterface(), name, types, inv.getServiceModel());
                Class<?>[] params = method.getParameterTypes();
                if (args == null) {
                    args = new Object[params.length];
                }

                if (types == null) {
                    types = new String[params.length];
                }

                if (args.length != types.length) {
                    throw new RpcException(
                            "GenericFilter#invoke args.length != types.length, please check your " + "params");
                }
                String generic = inv.getAttachment(GENERIC_KEY);

                if (StringUtils.isBlank(generic)) {
                    generic = getGenericValueFromRpcContext();
                }

                if (StringUtils.isEmpty(generic)
                        || ProtocolUtils.isDefaultGenericSerialization(generic)
                        || ProtocolUtils.isGenericReturnRawResult(generic)) {
                    try {
                        args = PojoUtils.realize(args, params, method.getGenericParameterTypes());
                    } catch (Exception e) {
                        logger.error(
                                LoggerCodeConstants.PROTOCOL_ERROR_DESERIALIZE,
                                "",
                                "",
                                "Deserialize generic invocation failed. ServiceKey: "
                                        + inv.getTargetServiceUniqueName(),
                                e);
                        throw new RpcException(e);
                    }
                } else if (ProtocolUtils.isGsonGenericSerialization(generic)) {
                    args = getGsonGenericArgs(args, method.getGenericParameterTypes());
                } else if (ProtocolUtils.isJavaGenericSerialization(generic)) {
                    Configuration configuration = ApplicationModel.ofNullable(applicationModel)
                            .modelEnvironment()
                            .getConfiguration();
                    if (!configuration.getBoolean(CommonConstants.ENABLE_NATIVE_JAVA_GENERIC_SERIALIZE, false)) {
                        String notice = "Trigger the safety barrier! "
                                + "Native Java Serializer is not allowed by default."
                                + "This means currently maybe being attacking by others. "
                                + "If you are sure this is a mistake, "
                                + "please set `"
                                + CommonConstants.ENABLE_NATIVE_JAVA_GENERIC_SERIALIZE + "` enable in configuration! "
                                + "Before doing so, please make sure you have configure JEP290 to prevent serialization attack.";
                        logger.error(CONFIG_FILTER_VALIDATION_EXCEPTION, "", "", notice);
                        throw new RpcException(new IllegalStateException(notice));
                    }

                    for (int i = 0; i < args.length; i++) {
                        if (byte[].class == args[i].getClass()) {
                            try (UnsafeByteArrayInputStream is = new UnsafeByteArrayInputStream((byte[]) args[i])) {
                                args[i] = applicationModel
                                        .getExtensionLoader(Serialization.class)
                                        .getExtension(GENERIC_SERIALIZATION_NATIVE_JAVA)
                                        .deserialize(null, is)
                                        .readObject();
                            } catch (Exception e) {
                                throw new RpcException("Deserialize argument [" + (i + 1) + "] failed.", e);
                            }
                        } else {
                            throw new RpcException("Generic serialization [" + GENERIC_SERIALIZATION_NATIVE_JAVA
                                    + "] only support message type "
                                    + byte[].class
                                    + " and your message type is "
                                    + args[i].getClass());
                        }
                    }
                } else if (ProtocolUtils.isBeanGenericSerialization(generic)) {
                    for (int i = 0; i < args.length; i++) {
                        if (args[i] != null) {
                            if (args[i] instanceof JavaBeanDescriptor) {
                                args[i] = JavaBeanSerializeUtil.deserialize((JavaBeanDescriptor) args[i]);
                            } else {
                                throw new RpcException("Generic serialization [" + GENERIC_SERIALIZATION_BEAN
                                        + "] only support message type "
                                        + JavaBeanDescriptor.class.getName()
                                        + " and your message type is "
                                        + args[i].getClass().getName());
                            }
                        }
                    }
                } else if (ProtocolUtils.isProtobufGenericSerialization(generic)) {
                    // as proto3 only accept one protobuf parameter
                    if (args.length == 1 && args[0] instanceof String) {
                        try (UnsafeByteArrayInputStream is =
                                new UnsafeByteArrayInputStream(((String) args[0]).getBytes(StandardCharsets.UTF_8))) {
                            args[0] = applicationModel
                                    .getExtensionLoader(Serialization.class)
                                    .getExtension(GENERIC_SERIALIZATION_PROTOBUF)
                                    .deserialize(null, is)
                                    .readObject(method.getParameterTypes()[0]);
                        } catch (Exception e) {
                            throw new RpcException("Deserialize argument failed.", e);
                        }
                    } else {
                        throw new RpcException("Generic serialization [" + GENERIC_SERIALIZATION_PROTOBUF
                                + "] only support one "
                                + String.class.getName() + " argument and your message size is "
                                + args.length
                                + " and type is" + args[0].getClass().getName());
                    }
                }

                RpcInvocation rpcInvocation = new RpcInvocation(
                        inv.getTargetServiceUniqueName(),
                        invoker.getUrl().getServiceModel(),
                        method.getName(),
                        invoker.getInterface().getName(),
                        invoker.getUrl().getProtocolServiceKey(),
                        method.getParameterTypes(),
                        args,
                        inv.getObjectAttachments(),
                        inv.getInvoker(),
                        inv.getAttributes(),
                        inv instanceof RpcInvocation ? ((RpcInvocation) inv).getInvokeMode() : null);

                return invoker.invoke(rpcInvocation);
            } catch (NoSuchMethodException | ClassNotFoundException e) {
                throw new RpcException(e.getMessage(), e);
            }
        }
        return invoker.invoke(inv);
    }

    private Object[] getGsonGenericArgs(final Object[] args, Type[] types) {
        return IntStream.range(0, args.length)
                .mapToObj(i -> {
                    if (args[i] == null) {
                        return null;
                    }
                    if (!(args[i] instanceof String)) {
                        throw new RpcException(
                                "When using GSON to deserialize generic dubbo request arguments, the arguments must be of type String");
                    }
                    String str = args[i].toString();
                    try {
                        return GsonUtils.fromJson(str, types[i]);
                    } catch (RuntimeException ex) {
                        throw new RpcException(ex.getMessage());
                    }
                })
                .toArray();
    }

    private String getGenericValueFromRpcContext() {
        String generic = RpcContext.getServerAttachment().getAttachment(GENERIC_KEY);
        if (StringUtils.isBlank(generic)) {
            generic = RpcContext.getClientAttachment().getAttachment(GENERIC_KEY);
        }
        return generic;
    }

    public Method findMethodByMethodSignature(
            Class<?> clazz, String methodName, String[] parameterTypes, ServiceModel serviceModel)
            throws NoSuchMethodException, ClassNotFoundException {
        Method method;
        if (parameterTypes == null) {
            List<Method> finded = new ArrayList<>();
            for (Method m : clazz.getMethods()) {
                if (m.getName().equals(methodName)) {
                    finded.add(m);
                }
            }
            if (finded.isEmpty()) {
                throw new NoSuchMethodException("No such method " + methodName + " in class " + clazz);
            }
            if (finded.size() > 1) {
                String msg = String.format(
                        "Not unique method for method name(%s) in class(%s), find %d methods.",
                        methodName, clazz.getName(), finded.size());
                throw new IllegalStateException(msg);
            }
            method = finded.get(0);
        } else {
            Class<?>[] types = new Class<?>[parameterTypes.length];
            for (int i = 0; i < parameterTypes.length; i++) {
                ClassLoader classLoader = ClassUtils.getClassLoader();
                Map<String, Class<?>> cacheMap = classCache.get(classLoader);
                if (cacheMap == null) {
                    cacheMap = new ConcurrentHashMap<>();
                    classCache.putIfAbsent(classLoader, cacheMap);
                    cacheMap = classCache.get(classLoader);
                }
                types[i] = cacheMap.get(parameterTypes[i]);
                if (types[i] == null) {
                    types[i] = ReflectUtils.name2class(parameterTypes[i]);
                    cacheMap.put(parameterTypes[i], types[i]);
                }
            }
            if (serviceModel != null) {
                MethodDescriptor methodDescriptor =
                        serviceModel.getServiceModel().getMethod(methodName, types);
                if (methodDescriptor == null) {
                    throw new NoSuchMethodException("No such method " + methodName + " in class " + clazz);
                }
                method = methodDescriptor.getMethod();
            } else {
                method = clazz.getMethod(methodName, types);
            }
        }
        return method;
    }

    @Override
    public void onResponse(Result appResponse, Invoker<?> invoker, Invocation inv) {
        if ((inv.getMethodName().equals($INVOKE) || inv.getMethodName().equals($INVOKE_ASYNC))
                && inv.getArguments() != null
                && inv.getArguments().length == 3
                && !GenericService.class.isAssignableFrom(invoker.getInterface())) {

            String generic = inv.getAttachment(GENERIC_KEY);
            if (StringUtils.isBlank(generic)) {
                generic = getGenericValueFromRpcContext();
            }

            if (appResponse.hasException()
                    && Dubbo2CompactUtils.isEnabled()
                    && Dubbo2GenericExceptionUtils.isGenericExceptionClassLoaded()) {
                Throwable appException = appResponse.getException();
                if (appException instanceof GenericException) {
                    GenericException tmp = (GenericException) appException;
                    GenericException recreated = Dubbo2GenericExceptionUtils.newGenericException(
                            tmp.getMessage(), tmp.getCause(), tmp.getExceptionClass(), tmp.getExceptionMessage());
                    if (recreated != null) {
                        appException = recreated;
                    }
                    appException.setStackTrace(tmp.getStackTrace());
                }
                if (!(Dubbo2GenericExceptionUtils.getGenericExceptionClass()
                        .isAssignableFrom(appException.getClass()))) {
                    GenericException recreated = Dubbo2GenericExceptionUtils.newGenericException(appException);
                    if (recreated != null) {
                        appException = recreated;
                    }
                }
                appResponse.setException(appException);
            }
            if (ProtocolUtils.isGenericReturnRawResult(generic)) {
                return;
            }
            if (ProtocolUtils.isJavaGenericSerialization(generic)) {
                try {
                    UnsafeByteArrayOutputStream os = new UnsafeByteArrayOutputStream(512);
                    applicationModel
                            .getExtensionLoader(Serialization.class)
                            .getExtension(GENERIC_SERIALIZATION_NATIVE_JAVA)
                            .serialize(null, os)
                            .writeObject(appResponse.getValue());
                    appResponse.setValue(os.toByteArray());
                } catch (IOException e) {
                    throw new RpcException(
                            "Generic serialization [" + GENERIC_SERIALIZATION_NATIVE_JAVA
                                    + "] serialize result failed.",
                            e);
                }
            } else if (ProtocolUtils.isBeanGenericSerialization(generic)) {
                appResponse.setValue(JavaBeanSerializeUtil.serialize(appResponse.getValue(), JavaBeanAccessor.METHOD));
            } else if (ProtocolUtils.isProtobufGenericSerialization(generic)) {
                try {
                    UnsafeByteArrayOutputStream os = new UnsafeByteArrayOutputStream(512);
                    applicationModel
                            .getExtensionLoader(Serialization.class)
                            .getExtension(GENERIC_SERIALIZATION_PROTOBUF)
                            .serialize(null, os)
                            .writeObject(appResponse.getValue());
                    appResponse.setValue(os.toString());
                } catch (IOException e) {
                    throw new RpcException(
                            "Generic serialization [" + GENERIC_SERIALIZATION_PROTOBUF + "] serialize result failed.",
                            e);
                }
            } else if (ProtocolUtils.isGsonGenericSerialization(generic)) {
                appResponse.setValue(GsonUtils.toJson(appResponse.getValue()));
            } else {
                appResponse.setValue(PojoUtils.generalize(appResponse.getValue()));
            }
        }
    }

    @Override
    public void onError(Throwable t, Invoker<?> invoker, Invocation invocation) {}
}
