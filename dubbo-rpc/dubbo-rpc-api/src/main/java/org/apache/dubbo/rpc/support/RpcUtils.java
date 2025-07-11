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
package org.apache.dubbo.rpc.support;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.logger.ErrorTypeAwareLogger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.ReflectUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.InvokeMode;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcInvocation;
import org.apache.dubbo.rpc.TimeoutCountDown;
import org.apache.dubbo.rpc.service.GenericService;

import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.apache.dubbo.common.constants.CommonConstants.$INVOKE;
import static org.apache.dubbo.common.constants.CommonConstants.$INVOKE_ASYNC;
import static org.apache.dubbo.common.constants.CommonConstants.ENABLE_TIMEOUT_COUNTDOWN_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.GENERIC_PARAMETER_DESC;
import static org.apache.dubbo.common.constants.CommonConstants.TIMEOUT_ATTACHMENT_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.TIMEOUT_ATTACHMENT_KEY_LOWER;
import static org.apache.dubbo.common.constants.CommonConstants.TIMEOUT_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.TIME_COUNTDOWN_KEY;
import static org.apache.dubbo.common.constants.LoggerCodeConstants.COMMON_REFLECTIVE_OPERATION_FAILED;
import static org.apache.dubbo.rpc.Constants.$ECHO;
import static org.apache.dubbo.rpc.Constants.$ECHO_PARAMETER_DESC;
import static org.apache.dubbo.rpc.Constants.ASYNC_KEY;
import static org.apache.dubbo.rpc.Constants.AUTO_ATTACH_INVOCATIONID_KEY;
import static org.apache.dubbo.rpc.Constants.ID_KEY;
import static org.apache.dubbo.rpc.Constants.RETURN_KEY;

/**
 * RpcUtils
 */
public class RpcUtils {

    private static final ErrorTypeAwareLogger logger = LoggerFactory.getErrorTypeAwareLogger(RpcUtils.class);
    private static final AtomicLong INVOKE_ID = new AtomicLong(0);

    public static Class<?> getReturnType(Invocation invocation) {
        try {
            if (invocation != null
                    && invocation.getInvoker() != null
                    && invocation.getInvoker().getUrl() != null
                    && invocation.getInvoker().getInterface() != GenericService.class
                    && !invocation.getMethodName().startsWith("$")) {
                String service = invocation.getInvoker().getUrl().getServiceInterface();
                if (StringUtils.isNotEmpty(service)) {
                    Method method = getMethodByService(invocation, service);
                    return method == null ? null : method.getReturnType();
                }
            }
        } catch (Throwable t) {
            logger.warn(COMMON_REFLECTIVE_OPERATION_FAILED, "", "", t.getMessage(), t);
        }
        return null;
    }

    public static Type[] getReturnTypes(Invocation invocation) {
        try {
            if (invocation != null
                    && invocation.getInvoker() != null
                    && invocation.getInvoker().getUrl() != null
                    && invocation.getInvoker().getInterface() != GenericService.class
                    && !invocation.getMethodName().startsWith("$")) {
                Type[] returnTypes = null;
                if (invocation instanceof RpcInvocation) {
                    returnTypes = ((RpcInvocation) invocation).getReturnTypes();
                    if (returnTypes != null) {
                        return returnTypes;
                    }
                }
                String service = invocation.getInvoker().getUrl().getServiceInterface();
                if (StringUtils.isNotEmpty(service)) {
                    Method method = getMethodByService(invocation, service);
                    if (method != null) {
                        returnTypes = ReflectUtils.getReturnTypes(method);
                    }
                }
                if (returnTypes != null) {
                    return returnTypes;
                }
            }
        } catch (Throwable t) {
            logger.warn(COMMON_REFLECTIVE_OPERATION_FAILED, "", "", t.getMessage(), t);
        }
        return null;
    }

    public static Long getInvocationId(Invocation inv) {
        String id = inv.getAttachment(ID_KEY);
        return id == null ? null : new Long(id);
    }

    /**
     * Idempotent operation: invocation id will be added in async operation by default
     *
     * @param url
     * @param inv
     */
    public static void attachInvocationIdIfAsync(URL url, Invocation inv) {
        if (isAttachInvocationId(url, inv) && getInvocationId(inv) == null && inv instanceof RpcInvocation) {
            inv.setAttachment(ID_KEY, String.valueOf(INVOKE_ID.getAndIncrement()));
        }
    }

    private static boolean isAttachInvocationId(URL url, Invocation invocation) {
        String value = url.getMethodParameter(invocation.getMethodName(), AUTO_ATTACH_INVOCATIONID_KEY);
        if (value == null) {
            // add invocationid in async operation by default
            return isAsync(url, invocation);
        }
        return Boolean.TRUE.toString().equalsIgnoreCase(value);
    }

    public static String getMethodName(Invocation invocation) {
        if (($INVOKE.equals(invocation.getMethodName()) || $INVOKE_ASYNC.equals(invocation.getMethodName()))
                && invocation.getArguments() != null
                && invocation.getArguments().length > 0
                && invocation.getArguments()[0] instanceof String) {
            return (String) invocation.getArguments()[0];
        }
        return invocation.getMethodName();
    }

    public static Object[] getArguments(Invocation invocation) {
        if (($INVOKE.equals(invocation.getMethodName()) || $INVOKE_ASYNC.equals(invocation.getMethodName()))
                && invocation.getArguments() != null
                && invocation.getArguments().length > 2
                && invocation.getArguments()[2] instanceof Object[]) {
            return (Object[]) invocation.getArguments()[2];
        }
        return invocation.getArguments();
    }

    public static Class<?>[] getParameterTypes(Invocation invocation) {
        if (($INVOKE.equals(invocation.getMethodName()) || $INVOKE_ASYNC.equals(invocation.getMethodName()))
                && invocation.getArguments() != null
                && invocation.getArguments().length > 1
                && invocation.getArguments()[1] instanceof String[]) {
            String[] types = (String[]) invocation.getArguments()[1];
            if (types == null) {
                return new Class<?>[0];
            }
            Class<?>[] parameterTypes = new Class<?>[types.length];
            for (int i = 0; i < types.length; i++) {
                parameterTypes[i] = ReflectUtils.forName(types[i]);
            }
            return parameterTypes;
        }
        return invocation.getParameterTypes();
    }

    public static boolean isAsync(URL url, Invocation inv) {
        boolean isAsync;

        if (inv instanceof RpcInvocation) {
            RpcInvocation rpcInvocation = (RpcInvocation) inv;
            if (rpcInvocation.getInvokeMode() != null) {
                return rpcInvocation.getInvokeMode() == InvokeMode.ASYNC;
            }
        }

        if (Boolean.TRUE.toString().equals(inv.getAttachment(ASYNC_KEY))) {
            isAsync = true;
        } else {
            isAsync = url.getMethodParameter(getMethodName(inv), ASYNC_KEY, false);
        }
        return isAsync;
    }

    public static boolean isReturnTypeFuture(Invocation inv) {
        Class<?> clazz;
        if (inv instanceof RpcInvocation) {
            clazz = ((RpcInvocation) inv).getReturnType();
        } else {
            clazz = getReturnType(inv);
        }
        return (clazz != null && CompletableFuture.class.isAssignableFrom(clazz)) || isGenericAsync(inv);
    }

    public static boolean isGenericAsync(Invocation inv) {
        return $INVOKE_ASYNC.equals(inv.getMethodName());
    }

    // check parameterTypesDesc to fix CVE-2020-1948
    public static boolean isGenericCall(String parameterTypesDesc, String method) {
        return ($INVOKE.equals(method) || $INVOKE_ASYNC.equals(method))
                && GENERIC_PARAMETER_DESC.equals(parameterTypesDesc);
    }

    // check parameterTypesDesc to fix CVE-2020-1948
    public static boolean isEcho(String parameterTypesDesc, String method) {
        return $ECHO.equals(method) && $ECHO_PARAMETER_DESC.equals(parameterTypesDesc);
    }

    public static InvokeMode getInvokeMode(URL url, Invocation inv) {
        if (inv instanceof RpcInvocation) {
            RpcInvocation rpcInvocation = (RpcInvocation) inv;
            if (rpcInvocation.getInvokeMode() != null) {
                return rpcInvocation.getInvokeMode();
            }
        }

        if (isReturnTypeFuture(inv)) {
            return InvokeMode.FUTURE;
        } else if (isAsync(url, inv)) {
            return InvokeMode.ASYNC;
        } else {
            return InvokeMode.SYNC;
        }
    }

    public static boolean isOneway(URL url, Invocation inv) {
        boolean isOneway;
        if (Boolean.FALSE.toString().equals(inv.getAttachment(RETURN_KEY))) {
            isOneway = true;
        } else {
            isOneway = !url.getMethodParameter(getMethodName(inv), RETURN_KEY, true);
        }
        return isOneway;
    }

    private static Method getMethodByService(Invocation invocation, String service) throws NoSuchMethodException {
        Class<?> invokerInterface = invocation.getInvoker().getInterface();
        Class<?> cls = invokerInterface != null ? invokerInterface : ReflectUtils.forName(service);
        Method method = cls.getMethod(invocation.getMethodName(), invocation.getParameterTypes());
        if (method.getReturnType() == void.class) {
            return null;
        }
        return method;
    }

    public static long getTimeout(Invocation invocation, long defaultTimeout) {
        long timeout = defaultTimeout;
        Object genericTimeout = invocation.getObjectAttachmentWithoutConvert(TIMEOUT_ATTACHMENT_KEY);
        if (genericTimeout == null) {
            genericTimeout = invocation.getObjectAttachmentWithoutConvert(TIMEOUT_ATTACHMENT_KEY_LOWER);
        }
        if (genericTimeout != null) {
            timeout = convertToNumber(genericTimeout, defaultTimeout);
        }
        return timeout;
    }

    public static long getTimeout(
            URL url, String methodName, RpcContext context, Invocation invocation, long defaultTimeout) {
        long timeout = defaultTimeout;
        Object timeoutFromContext = context.getObjectAttachment(TIMEOUT_KEY);
        Object timeoutFromInvocation = invocation.getObjectAttachment(TIMEOUT_KEY);

        if (timeoutFromContext != null) {
            timeout = convertToNumber(timeoutFromContext, defaultTimeout);
        } else if (timeoutFromInvocation != null) {
            timeout = convertToNumber(timeoutFromInvocation, defaultTimeout);
        } else if (url != null) {
            timeout = url.getMethodPositiveParameter(methodName, TIMEOUT_KEY, defaultTimeout);
        }
        return timeout;
    }

    public static int calculateTimeout(URL url, Invocation invocation, String methodName, long defaultTimeout) {
        Object countdown = RpcContext.getClientAttachment().getObjectAttachment(TIME_COUNTDOWN_KEY);
        int timeout = (int) defaultTimeout;
        if (countdown == null) {
            if (url != null) {
                timeout = (int) RpcUtils.getTimeout(
                        url, methodName, RpcContext.getClientAttachment(), invocation, defaultTimeout);
                if (url.getMethodParameter(methodName, ENABLE_TIMEOUT_COUNTDOWN_KEY, false)) {
                    // pass timeout to remote server
                    invocation.setObjectAttachment(TIMEOUT_ATTACHMENT_KEY, timeout);
                }
            }
        } else {
            TimeoutCountDown timeoutCountDown = (TimeoutCountDown) countdown;
            timeout = (int) timeoutCountDown.timeRemaining(TimeUnit.MILLISECONDS);
            // pass timeout to remote server
            invocation.setObjectAttachment(TIMEOUT_ATTACHMENT_KEY, timeout);
        }

        invocation.getObjectAttachments().remove(TIME_COUNTDOWN_KEY);
        return timeout;
    }

    public static Long convertToNumber(Object obj, long defaultTimeout) {
        Long timeout = convertToNumber(obj);
        return timeout == null ? defaultTimeout : timeout;
    }

    public static Long convertToNumber(Object obj) {
        Long timeout = null;
        try {
            if (obj instanceof String) {
                timeout = Long.parseLong((String) obj);
            } else if (obj instanceof Number) {
                timeout = ((Number) obj).longValue();
            } else if (obj != null) {
                timeout = Long.parseLong(obj.toString());
            }
        } catch (Exception e) {
            // ignore
        }
        return timeout;
    }
}
