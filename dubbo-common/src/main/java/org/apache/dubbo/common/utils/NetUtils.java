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
package org.apache.dubbo.common.utils;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.config.ConfigurationUtils;
import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.logger.support.FailsafeLogger;
import org.apache.dubbo.rpc.model.ScopeModel;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.BitSet;
import java.util.Enumeration;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import static java.util.Collections.emptyList;
import static org.apache.dubbo.common.constants.CommonConstants.ANYHOST_VALUE;
import static org.apache.dubbo.common.constants.CommonConstants.DUBBO_IP_TO_BIND;
import static org.apache.dubbo.common.constants.CommonConstants.LOCALHOST_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.LOCALHOST_VALUE;
import static org.apache.dubbo.common.utils.CollectionUtils.first;

/**
 * IP and Port Helper for RPC
 */
public final class NetUtils {

    /**
     * Forbids instantiation.
     */
    private NetUtils() {
        throw new UnsupportedOperationException("No instance of 'NetUtils' for you! ");
    }

    private static Logger logger;

    static {
        /*
            DO NOT replace this logger to error type aware logger (or fail-safe logger), since its
            logging method calls NetUtils.getLocalHost().

            According to issue #4992, getLocalHost() method will be endless recursively invoked when network disconnected.
        */

        logger = LoggerFactory.getLogger(NetUtils.class);
        if (logger instanceof FailsafeLogger) {
            logger = ((FailsafeLogger) logger).getLogger();
        }
    }

    // returned port range is [30000, 39999]
    private static final int RND_PORT_START = 30000;
    private static final int RND_PORT_RANGE = 10000;

    // valid port range is (0, 65535]
    private static final int MIN_PORT = 1;
    private static final int MAX_PORT = 65535;

    private static final Pattern ADDRESS_PATTERN = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}\\:\\d{1,5}$");
    private static final Pattern LOCAL_IP_PATTERN = Pattern.compile("127(\\.\\d{1,3}){3}$");
    private static final Pattern IP_PATTERN = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3,5}$");

    private static final Map<String, String> HOST_NAME_CACHE = new LRUCache<>(1000);
    private static volatile InetAddress LOCAL_ADDRESS = null;
    private static volatile Inet6Address LOCAL_ADDRESS_V6 = null;

    private static final String SPLIT_IPV4_CHARACTER = "\\.";
    private static final String SPLIT_IPV6_CHARACTER = ":";

    /**
     * store the used port.
     * the set used only on the synchronized method.
     */
    private static BitSet USED_PORT = new BitSet(65536);

    private static boolean reuseAddressSupported;

    static {
        try (ServerSocket serverSocket = new ServerSocket()) {
            serverSocket.setReuseAddress(true);
            reuseAddressSupported = true;
        } catch (Throwable ignored) {
            // ignore.
        }
    }

    public static boolean isReuseAddressSupported() {
        return reuseAddressSupported;
    }

    public static int getRandomPort() {
        return RND_PORT_START + ThreadLocalRandom.current().nextInt(RND_PORT_RANGE);
    }

    public static synchronized int getAvailablePort() {
        int randomPort = getRandomPort();
        return getAvailablePort(randomPort);
    }

    public static synchronized int getAvailablePort(int port) {
        if (port < MIN_PORT) {
            return MIN_PORT;
        }

        for (int i = port; i < MAX_PORT; i++) {
            if (USED_PORT.get(i)) {
                continue;
            }
            try (ServerSocket serverSocket = new ServerSocket()) {
                if (reuseAddressSupported) {
                    // SO_REUSEADDR should be enabled before bind.
                    serverSocket.setReuseAddress(true);
                }
                serverSocket.bind(new InetSocketAddress(i));
                USED_PORT.set(i);
                port = i;
                break;
            } catch (IOException e) {
                // continue
            }
        }
        return port;
    }

    /**
     * Check the port whether is in use in os
     *
     * @param port port to check
     * @return true if it's occupied
     */
    public static boolean isPortInUsed(int port) {
        try (ServerSocket serverSocket = new ServerSocket()) {
            if (reuseAddressSupported) {
                // SO_REUSEADDR should be enabled before bind.
                serverSocket.setReuseAddress(true);
            }
            serverSocket.bind(new InetSocketAddress(port));
            return false;
        } catch (IOException e) {
            // continue
        }
        return true;
    }

    /**
     * Tells whether the port to test is an invalid port.
     *
     * @param port port to test
     * @return true if invalid
     * @implNote Numeric comparison only.
     */
    public static boolean isInvalidPort(int port) {
        return port < MIN_PORT || port > MAX_PORT;
    }

    /**
     * Tells whether the address to test is an invalid address.
     *
     * @param address address to test
     * @return true if invalid
     * @implNote Pattern matching only.
     */
    public static boolean isValidAddress(String address) {
        return ADDRESS_PATTERN.matcher(address).matches();
    }

    public static boolean isLocalHost(String host) {
        return host != null && (LOCAL_IP_PATTERN.matcher(host).matches() || host.equalsIgnoreCase(LOCALHOST_KEY));
    }

    public static boolean isAnyHost(String host) {
        return ANYHOST_VALUE.equals(host);
    }

    public static boolean isInvalidLocalHost(String host) {
        return host == null
                || host.length() == 0
                || host.equalsIgnoreCase(LOCALHOST_KEY)
                || host.equals(ANYHOST_VALUE)
                || host.startsWith("127.");
    }

    public static boolean isValidLocalHost(String host) {
        return !isInvalidLocalHost(host);
    }

    public static InetSocketAddress getLocalSocketAddress(String host, int port) {
        return isInvalidLocalHost(host) ? new InetSocketAddress(port) : new InetSocketAddress(host, port);
    }

    static boolean isValidV4Address(InetAddress address) {
        if (address == null || address.isLoopbackAddress() || address.isLinkLocalAddress()) {
            return false;
        }

        String name = address.getHostAddress();
        return (name != null
                && IP_PATTERN.matcher(name).matches()
                && !ANYHOST_VALUE.equals(name)
                && !LOCALHOST_VALUE.equals(name));
    }

    /**
     * Check if an ipv6 address
     *
     * @return true if it is reachable
     */
    static boolean isPreferIPV6Address() {
        return Boolean.getBoolean("java.net.preferIPv6Addresses");
    }

    /**
     * normalize the ipv6 Address, convert scope name to scope id.
     * e.g.
     * convert
     * fe80:0:0:0:894:aeec:f37d:23e1%en0
     * to
     * fe80:0:0:0:894:aeec:f37d:23e1%5
     * <p>
     * The %5 after ipv6 address is called scope id.
     * see java doc of {@link Inet6Address} for more details.
     *
     * @param address the input address
     * @return the normalized address, with scope id converted to int
     */
    static InetAddress normalizeV6Address(Inet6Address address) {
        String addr = address.getHostAddress();
        int i = addr.lastIndexOf('%');
        if (i > 0) {
            try {
                return InetAddress.getByName(addr.substring(0, i) + '%' + address.getScopeId());
            } catch (UnknownHostException e) {
                // ignore
                logger.debug("Unknown IPV6 address: ", e);
            }
        }
        return address;
    }

    private static volatile String HOST_ADDRESS;

    private static volatile String HOST_NAME;

    private static volatile String HOST_ADDRESS_V6;

    public static String getLocalHost() {
        if (HOST_ADDRESS != null) {
            return HOST_ADDRESS;
        }

        InetAddress address = getLocalAddress();
        if (address != null) {
            if (address instanceof Inet6Address) {
                String ipv6AddressString = address.getHostAddress();
                if (ipv6AddressString.contains("%")) {
                    ipv6AddressString = ipv6AddressString.substring(0, ipv6AddressString.indexOf("%"));
                }
                HOST_ADDRESS = ipv6AddressString;
                return HOST_ADDRESS;
            }

            HOST_ADDRESS = address.getHostAddress();
            return HOST_ADDRESS;
        }

        return LOCALHOST_VALUE;
    }

    public static String getLocalHostV6() {
        if (StringUtils.isNotEmpty(HOST_ADDRESS_V6)) {
            return HOST_ADDRESS_V6;
        }
        // avoid to search network interface card many times
        if ("".equals(HOST_ADDRESS_V6)) {
            return null;
        }

        Inet6Address address = getLocalAddressV6();
        if (address != null) {
            String ipv6AddressString = address.getHostAddress();
            if (ipv6AddressString.contains("%")) {
                ipv6AddressString = ipv6AddressString.substring(0, ipv6AddressString.indexOf("%"));
            }

            HOST_ADDRESS_V6 = ipv6AddressString;
            return HOST_ADDRESS_V6;
        }
        HOST_ADDRESS_V6 = "";
        return null;
    }

    public static String filterLocalHost(String host) {
        if (host == null || host.length() == 0) {
            return host;
        }
        if (host.contains("://")) {
            URL u = URL.valueOf(host);
            if (NetUtils.isInvalidLocalHost(u.getHost())) {
                return u.setHost(NetUtils.getLocalHost()).toFullString();
            }
        } else if (host.contains(":")) {
            int i = host.lastIndexOf(':');
            if (NetUtils.isInvalidLocalHost(host.substring(0, i))) {
                return NetUtils.getLocalHost() + host.substring(i);
            }
        } else {
            if (NetUtils.isInvalidLocalHost(host)) {
                return NetUtils.getLocalHost();
            }
        }
        return host;
    }

    public static String getIpByConfig(ScopeModel scopeModel) {
        String configIp = ConfigurationUtils.getProperty(scopeModel, DUBBO_IP_TO_BIND);
        if (configIp != null) {
            return configIp;
        }

        return getLocalHost();
    }

    /**
     * Find first valid IP from local network card
     *
     * @return first valid local IP
     */
    public static InetAddress getLocalAddress() {
        if (LOCAL_ADDRESS != null) {
            return LOCAL_ADDRESS;
        }
        InetAddress localAddress = getLocalAddress0();
        LOCAL_ADDRESS = localAddress;
        return localAddress;
    }

    public static Inet6Address getLocalAddressV6() {
        if (LOCAL_ADDRESS_V6 != null) {
            return LOCAL_ADDRESS_V6;
        }
        Inet6Address localAddress = getLocalAddress0V6();
        LOCAL_ADDRESS_V6 = localAddress;
        return localAddress;
    }

    private static Optional<InetAddress> toValidAddress(InetAddress address) {
        if (address instanceof Inet6Address) {
            Inet6Address v6Address = (Inet6Address) address;
            if (isPreferIPV6Address()) {
                return Optional.ofNullable(normalizeV6Address(v6Address));
            }
        }
        if (isValidV4Address(address)) {
            return Optional.of(address);
        }
        return Optional.empty();
    }

    private static InetAddress getLocalAddress0() {
        InetAddress localAddress = null;

        // @since 2.7.6, choose the {@link NetworkInterface} first
        try {
            NetworkInterface networkInterface = findNetworkInterface();
            Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
            while (addresses.hasMoreElements()) {
                Optional<InetAddress> addressOp = toValidAddress(addresses.nextElement());
                if (addressOp.isPresent()) {
                    try {
                        if (addressOp.get().isReachable(100)) {
                            return addressOp.get();
                        }
                    } catch (IOException e) {
                        // ignore
                    }
                }
            }
        } catch (Throwable e) {
            logger.warn(e);
        }

        try {
            localAddress = InetAddress.getLocalHost();
            Optional<InetAddress> addressOp = toValidAddress(localAddress);
            if (addressOp.isPresent()) {
                return addressOp.get();
            }
        } catch (Throwable e) {
            logger.warn(e);
        }

        localAddress = getLocalAddressV6();

        return localAddress;
    }

    private static Inet6Address getLocalAddress0V6() {
        // @since 2.7.6, choose the {@link NetworkInterface} first
        try {
            NetworkInterface networkInterface = findNetworkInterface();
            Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
            while (addresses.hasMoreElements()) {
                InetAddress address = addresses.nextElement();
                if (address instanceof Inet6Address) {
                    if (!address.isLoopbackAddress() // filter ::1
                            && !address.isAnyLocalAddress() // filter ::/128
                            && !address.isLinkLocalAddress() // filter fe80::/10
                            && !address.isSiteLocalAddress() // filter fec0::/10
                            && !isUniqueLocalAddress(address) // filter fd00::/8
                            && address.getHostAddress().contains(":")) { // filter IPv6
                        return (Inet6Address) address;
                    }
                }
            }
        } catch (Throwable e) {
            logger.warn(e);
        }

        return null;
    }

    /**
     * If the address is Unique Local Address.
     *
     * @param address {@link InetAddress}
     * @return {@code true} if the address is Unique Local Address,otherwise {@code false}
     */
    private static boolean isUniqueLocalAddress(InetAddress address) {
        byte[] ip = address.getAddress();
        return (ip[0] & 0xff) == 0xfd;
    }

    /**
     * Returns {@code true} if the specified {@link NetworkInterface} should be ignored with the given conditions.
     *
     * @param networkInterface the {@link NetworkInterface} to check
     * @return {@code true} if the specified {@link NetworkInterface} should be ignored, otherwise {@code false}
     * @throws SocketException SocketException if an I/O error occurs.
     */
    private static boolean ignoreNetworkInterface(NetworkInterface networkInterface) throws SocketException {
        if (networkInterface == null
                || networkInterface.isLoopback()
                || networkInterface.isVirtual()
                || !networkInterface.isUp()) {
            return true;
        }
        if (Boolean.parseBoolean(SystemPropertyConfigUtils.getSystemProperty(
                        CommonConstants.DubboProperty.DUBBO_NETWORK_INTERFACE_POINT_TO_POINT_IGNORED, "false"))
                && networkInterface.isPointToPoint()) {
            return true;
        }
        String ignoredInterfaces = SystemPropertyConfigUtils.getSystemProperty(
                CommonConstants.DubboProperty.DUBBO_NETWORK_IGNORED_INTERFACE);
        String networkInterfaceDisplayName;
        if (StringUtils.isNotEmpty(ignoredInterfaces)
                && StringUtils.isNotEmpty(networkInterfaceDisplayName = networkInterface.getDisplayName())) {
            for (String ignoredInterface : ignoredInterfaces.split(",")) {
                String trimIgnoredInterface = ignoredInterface.trim();
                boolean matched = false;
                try {
                    matched = networkInterfaceDisplayName.matches(trimIgnoredInterface);
                } catch (PatternSyntaxException e) {
                    // if trimIgnoredInterface is an invalid regular expression, a PatternSyntaxException will be thrown
                    // out
                    logger.warn(
                            "exception occurred: " + networkInterfaceDisplayName + " matches " + trimIgnoredInterface,
                            e);
                } finally {
                    if (matched) {
                        return true;
                    }
                    if (networkInterfaceDisplayName.equals(trimIgnoredInterface)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Get the valid {@link NetworkInterface network interfaces}
     *
     * @return the valid {@link NetworkInterface}s
     * @throws SocketException SocketException if an I/O error occurs.
     * @since 2.7.6
     */
    private static List<NetworkInterface> getValidNetworkInterfaces() throws SocketException {
        List<NetworkInterface> validNetworkInterfaces = new LinkedList<>();
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        while (interfaces.hasMoreElements()) {
            NetworkInterface networkInterface = interfaces.nextElement();
            if (ignoreNetworkInterface(networkInterface)) { // ignore
                continue;
            }
            validNetworkInterfaces.add(networkInterface);
        }
        return validNetworkInterfaces;
    }

    /**
     * Is preferred {@link NetworkInterface} or not
     *
     * @param networkInterface {@link NetworkInterface}
     * @return if the name of the specified {@link NetworkInterface} matches
     * the property value from {@link CommonConstants.DubboProperty#DUBBO_PREFERRED_NETWORK_INTERFACE}, return <code>true</code>,
     * or <code>false</code>
     */
    public static boolean isPreferredNetworkInterface(NetworkInterface networkInterface) {
        String preferredNetworkInterface = SystemPropertyConfigUtils.getSystemProperty(
                CommonConstants.DubboProperty.DUBBO_PREFERRED_NETWORK_INTERFACE);
        return Objects.equals(networkInterface.getDisplayName(), preferredNetworkInterface);
    }

    /**
     * Get the suitable {@link NetworkInterface}
     *
     * @return If no {@link NetworkInterface} is available , return <code>null</code>
     * @since 2.7.6
     */
    public static NetworkInterface findNetworkInterface() {

        List<NetworkInterface> validNetworkInterfaces = emptyList();
        try {
            validNetworkInterfaces = getValidNetworkInterfaces();
        } catch (Throwable e) {
            logger.warn(e);
        }

        NetworkInterface result = null;

        // Try to find the preferred one
        for (NetworkInterface networkInterface : validNetworkInterfaces) {
            if (isPreferredNetworkInterface(networkInterface)) {
                result = networkInterface;
                break;
            }
        }

        if (result == null) { // If not found, try to get the first one
            for (NetworkInterface networkInterface : validNetworkInterfaces) {
                Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    Optional<InetAddress> addressOp = toValidAddress(addresses.nextElement());
                    if (addressOp.isPresent()) {
                        try {
                            if (addressOp.get().isReachable(100)) {
                                if (addressOp.get().isSiteLocalAddress()) {
                                    return networkInterface;
                                } else {
                                    result = networkInterface;
                                }
                            }
                        } catch (IOException e) {
                            // ignore
                        }
                    }
                }
            }
        }

        if (result == null) {
            result = first(validNetworkInterfaces);
        }

        return result;
    }

    public static String getHostName(String address) {
        try {
            int i = address.indexOf(':');
            if (i > -1) {
                address = address.substring(0, i);
            }
            String hostname = HOST_NAME_CACHE.get(address);
            if (hostname != null && hostname.length() > 0) {
                return hostname;
            }
            InetAddress inetAddress = InetAddress.getByName(address);
            if (inetAddress != null) {
                hostname = inetAddress.getHostName();
                HOST_NAME_CACHE.put(address, hostname);
                return hostname;
            }
        } catch (Throwable e) {
            // ignore
        }
        return address;
    }

    public static String getLocalHostName() {
        if (HOST_NAME != null) {
            return HOST_NAME;
        }
        try {
            HOST_NAME = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            HOST_NAME = Optional.ofNullable(getLocalAddress())
                    .map(k -> k.getHostName())
                    .orElse(null);
        }
        return HOST_NAME;
    }

    /**
     * @param hostName
     * @return ip address or hostName if UnknownHostException
     */
    public static String getIpByHost(String hostName) {
        try {
            return InetAddress.getByName(hostName).getHostAddress();
        } catch (UnknownHostException e) {
            return hostName;
        }
    }

    public static String toAddressString(InetSocketAddress address) {
        return address.getAddress().getHostAddress() + ":" + address.getPort();
    }

    public static InetSocketAddress toAddress(String address) {
        int i = address.indexOf(':');
        String host;
        int port;
        if (i > -1) {
            host = address.substring(0, i);
            port = Integer.parseInt(address.substring(i + 1));
        } else {
            host = address;
            port = 0;
        }
        return new InetSocketAddress(host, port);
    }

    public static String toURL(String protocol, String host, int port, String path) {
        StringBuilder sb = new StringBuilder();
        sb.append(protocol).append("://");
        sb.append(host).append(':').append(port);
        if (path.charAt(0) != '/') {
            sb.append('/');
        }
        sb.append(path);
        return sb.toString();
    }

    @SuppressWarnings("deprecation")
    public static void joinMulticastGroup(MulticastSocket multicastSocket, InetAddress multicastAddress)
            throws IOException {
        setInterface(multicastSocket, multicastAddress instanceof Inet6Address);

        // For the deprecation notice: the equivalent only appears in JDK 9+.
        multicastSocket.setLoopbackMode(false);
        multicastSocket.joinGroup(multicastAddress);
    }

    @SuppressWarnings("deprecation")
    public static void setInterface(MulticastSocket multicastSocket, boolean preferIpv6) throws IOException {
        boolean interfaceSet = false;
        for (NetworkInterface networkInterface : getValidNetworkInterfaces()) {
            Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();

            while (addresses.hasMoreElements()) {
                InetAddress address = addresses.nextElement();
                if (preferIpv6 && address instanceof Inet6Address) {
                    try {
                        if (address.isReachable(100)) {
                            multicastSocket.setInterface(address);
                            interfaceSet = true;
                            break;
                        }
                    } catch (IOException e) {
                        // ignore
                    }
                } else if (!preferIpv6 && address instanceof Inet4Address) {
                    try {
                        if (address.isReachable(100)) {
                            multicastSocket.setInterface(address);
                            interfaceSet = true;
                            break;
                        }
                    } catch (IOException e) {
                        // ignore
                    }
                }
            }
            if (interfaceSet) {
                break;
            }
        }
    }

    /**
     * Check if address matches with specified pattern, currently only supports ipv4, use {@link this#matchIpExpression(String, String, int)} for ipv6 addresses.
     *
     * @param pattern cird pattern
     * @param address 'ip:port'
     * @return true if address matches with the pattern
     */
    public static boolean matchIpExpression(String pattern, String address) throws UnknownHostException {
        if (address == null) {
            return false;
        }

        String host = address;
        int port = 0;
        // only works for ipv4 address with 'ip:port' format
        if (address.endsWith(":")) {
            String[] hostPort = address.split(":");
            host = hostPort[0];
            port = StringUtils.parseInteger(hostPort[1]);
        }

        // if the pattern is subnet format, it will not be allowed to config port param in pattern.
        if (pattern.contains("/")) {
            CIDRUtils utils = new CIDRUtils(pattern);
            return utils.isInRange(host);
        }

        return matchIpRange(pattern, host, port);
    }

    public static boolean matchIpExpression(String pattern, String host, int port) throws UnknownHostException {

        // if the pattern is subnet format, it will not be allowed to config port param in pattern.
        if (pattern.contains("/")) {
            CIDRUtils utils = new CIDRUtils(pattern);
            return utils.isInRange(host);
        }

        return matchIpRange(pattern, host, port);
    }

    /**
     * @param pattern
     * @param host
     * @param port
     * @return
     * @throws UnknownHostException
     */
    public static boolean matchIpRange(String pattern, String host, int port) throws UnknownHostException {
        if (pattern == null || host == null) {
            throw new IllegalArgumentException(
                    "Illegal Argument pattern or hostName. Pattern:" + pattern + ", Host:" + host);
        }
        pattern = pattern.trim();
        if ("*.*.*.*".equals(pattern) || "*".equals(pattern)) {
            return true;
        }

        InetAddress inetAddress = InetAddress.getByName(host);
        boolean isIpv4 = isValidV4Address(inetAddress);
        String[] hostAndPort = getPatternHostAndPort(pattern, isIpv4);
        if (hostAndPort[1] != null && !hostAndPort[1].equals(String.valueOf(port))) {
            return false;
        }
        pattern = hostAndPort[0];

        String splitCharacter = SPLIT_IPV4_CHARACTER;
        if (!isIpv4) {
            splitCharacter = SPLIT_IPV6_CHARACTER;
        }
        String[] mask = pattern.split(splitCharacter);
        // check format of pattern
        checkHostPattern(pattern, mask, isIpv4);

        host = inetAddress.getHostAddress();
        if (pattern.equals(host)) {
            return true;
        }

        // short name condition
        if (!ipPatternContainExpression(pattern)) {
            InetAddress patternAddress = InetAddress.getByName(pattern);
            return patternAddress.getHostAddress().equals(host);
        }

        String[] ipAddress = host.split(splitCharacter);

        for (int i = 0; i < mask.length; i++) {
            if ("*".equals(mask[i]) || mask[i].equals(ipAddress[i])) {
                continue;
            } else if (mask[i].contains("-")) {
                String[] rangeNumStrs = StringUtils.split(mask[i], '-');
                if (rangeNumStrs.length != 2) {
                    throw new IllegalArgumentException("There is wrong format of ip Address: " + mask[i]);
                }
                Integer min = getNumOfIpSegment(rangeNumStrs[0], isIpv4);
                Integer max = getNumOfIpSegment(rangeNumStrs[1], isIpv4);
                Integer ip = getNumOfIpSegment(ipAddress[i], isIpv4);
                if (ip < min || ip > max) {
                    return false;
                }
            } else if ("0".equals(ipAddress[i])
                    && ("0".equals(mask[i])
                            || "00".equals(mask[i])
                            || "000".equals(mask[i])
                            || "0000".equals(mask[i]))) {
                continue;
            } else if (!mask[i].equals(ipAddress[i])) {
                return false;
            }
        }
        return true;
    }

    /**
     * is multicast address or not
     *
     * @param host ipv4 address
     * @return {@code true} if is multicast address
     */
    public static boolean isMulticastAddress(String host) {
        int i = host.indexOf('.');
        if (i > 0) {
            String prefix = host.substring(0, i);
            if (StringUtils.isNumber(prefix)) {
                int p = Integer.parseInt(prefix);
                return p >= 224 && p <= 239;
            }
        }
        return false;
    }

    private static boolean ipPatternContainExpression(String pattern) {
        return pattern.contains("*") || pattern.contains("-");
    }

    private static void checkHostPattern(String pattern, String[] mask, boolean isIpv4) {
        if (!isIpv4) {
            if (mask.length != 8 && ipPatternContainExpression(pattern)) {
                throw new IllegalArgumentException(
                        "If you config ip expression that contains '*' or '-', please fill qualified ip pattern like 234e:0:4567:0:0:0:3d:*. ");
            }
            if (mask.length != 8 && !pattern.contains("::")) {
                throw new IllegalArgumentException(
                        "The host is ipv6, but the pattern is not ipv6 pattern : " + pattern);
            }
        } else {
            if (mask.length != 4) {
                throw new IllegalArgumentException(
                        "The host is ipv4, but the pattern is not ipv4 pattern : " + pattern);
            }
        }
    }

    private static String[] getPatternHostAndPort(String pattern, boolean isIpv4) {
        String[] result = new String[2];
        if (pattern.startsWith("[") && pattern.contains("]:")) {
            int end = pattern.indexOf("]:");
            result[0] = pattern.substring(1, end);
            result[1] = pattern.substring(end + 2);
            return result;
        } else if (pattern.startsWith("[") && pattern.endsWith("]")) {
            result[0] = pattern.substring(1, pattern.length() - 1);
            result[1] = null;
            return result;
        } else if (isIpv4 && pattern.contains(":")) {
            int end = pattern.indexOf(":");
            result[0] = pattern.substring(0, end);
            result[1] = pattern.substring(end + 1);
            return result;
        } else {
            result[0] = pattern;
            return result;
        }
    }

    private static Integer getNumOfIpSegment(String ipSegment, boolean isIpv4) {
        if (isIpv4) {
            return Integer.parseInt(ipSegment);
        }
        return Integer.parseInt(ipSegment, 16);
    }

    public static boolean isIPV6URLStdFormat(String ip) {
        if ((ip.charAt(0) == '[' && ip.indexOf(']') > 2)) {
            return true;
        } else if (ip.indexOf(":") != ip.lastIndexOf(":")) {
            return true;
        } else {
            return false;
        }
    }

    public static String getLegalIP(String ip) {
        // ipv6 [::FFFF:129.144.52.38]:80
        int ind;
        if ((ip.charAt(0) == '[' && (ind = ip.indexOf(']')) > 2)) {
            String nhost = ip;
            ip = nhost.substring(0, ind + 1);
            ip = ip.substring(1, ind);
            return ip;
        } else {
            return ip;
        }
    }
}
