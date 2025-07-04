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
package org.apache.dubbo.remoting.transport.netty4;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.io.Bytes;
import org.apache.dubbo.common.logger.ErrorTypeAwareLogger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.ssl.AuthPolicy;
import org.apache.dubbo.common.ssl.CertManager;
import org.apache.dubbo.common.ssl.ProviderCert;
import org.apache.dubbo.remoting.ChannelHandler;
import org.apache.dubbo.remoting.Constants;
import org.apache.dubbo.remoting.api.ProtocolDetector;
import org.apache.dubbo.remoting.api.WireProtocol;
import org.apache.dubbo.remoting.buffer.ChannelBuffer;
import org.apache.dubbo.remoting.transport.netty4.ssl.SslContexts;

import javax.net.ssl.SSLSession;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.ApplicationProtocolNegotiationHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.SslHandshakeCompletionEvent;
import io.netty.util.AttributeKey;

import static org.apache.dubbo.common.constants.LoggerCodeConstants.CONFIG_SSL_CONNECT_INSECURE;
import static org.apache.dubbo.common.constants.LoggerCodeConstants.INTERNAL_ERROR;

public class NettyPortUnificationServerHandler extends ByteToMessageDecoder {

    private static final ErrorTypeAwareLogger LOGGER =
            LoggerFactory.getErrorTypeAwareLogger(NettyPortUnificationServerHandler.class);
    private final URL url;
    private final ChannelHandler handler;
    private final boolean detectSsl;
    private final Map<String, WireProtocol> protocols;
    private final Map<String, URL> urlMapper;
    private final Map<String, ChannelHandler> handlerMapper;
    private static final AttributeKey<SSLSession> SSL_SESSION_KEY = AttributeKey.valueOf(Constants.SSL_SESSION_KEY);

    public NettyPortUnificationServerHandler(
            URL url,
            boolean detectSsl,
            Map<String, WireProtocol> protocols,
            ChannelHandler handler,
            Map<String, URL> urlMapper,
            Map<String, ChannelHandler> handlerMapper) {
        this.url = url;
        this.protocols = protocols;
        this.detectSsl = detectSsl;
        this.handler = handler;
        this.urlMapper = urlMapper;
        this.handlerMapper = handlerMapper;
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        LOGGER.error(
                INTERNAL_ERROR,
                "unknown error in remoting module",
                "",
                "Unexpected exception from downstream before protocol detected.",
                cause);
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof SslHandshakeCompletionEvent) {
            SslHandshakeCompletionEvent handshakeEvent = (SslHandshakeCompletionEvent) evt;
            if (handshakeEvent.isSuccess()) {
                SSLSession session =
                        ctx.pipeline().get(SslHandler.class).engine().getSession();
                LOGGER.info("TLS negotiation succeed with session: " + session);
                ctx.channel().attr(SSL_SESSION_KEY).set(session);
            } else {
                LOGGER.error(
                        INTERNAL_ERROR,
                        "",
                        "",
                        "TLS negotiation failed when trying to accept new connection.",
                        handshakeEvent.cause());
                ctx.close();
            }
        }
        super.userEventTriggered(ctx, evt);
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        NettyChannel channel = NettyChannel.getOrAddChannel(ctx.channel(), url, handler);
        // Will use the first five bytes to detect a protocol.
        // size of telnet command ls is 2 bytes
        if (in.readableBytes() < 2) {
            return;
        }

        CertManager certManager =
                url.getOrDefaultFrameworkModel().getBeanFactory().getBean(CertManager.class);
        ProviderCert providerConnectionConfig =
                certManager.getProviderConnectionConfig(url, ctx.channel().remoteAddress());

        if (providerConnectionConfig != null && canDetectSsl(in)) {
            if (isSsl(in)) {
                enableSsl(ctx, providerConnectionConfig);
            } else {
                // check server should load TLS or not
                if (providerConnectionConfig.getAuthPolicy() != AuthPolicy.NONE) {
                    byte[] preface = new byte[in.readableBytes()];
                    in.readBytes(preface);
                    LOGGER.error(
                            CONFIG_SSL_CONNECT_INSECURE,
                            "client request server without TLS",
                            "",
                            String.format(
                                    "Downstream=%s request without TLS preface, but server require it. " + "preface=%s",
                                    ctx.channel().remoteAddress(), Bytes.bytes2hex(preface)));

                    // Untrusted connection; discard everything and close the connection.
                    in.clear();
                    ctx.close();
                }
            }
        } else {
            detectProtocol(ctx, url, channel, in);
        }
    }

    private void enableSsl(ChannelHandlerContext ctx, ProviderCert providerConnectionConfig) {
        ChannelPipeline p = ctx.pipeline();
        SslContext sslContext = SslContexts.buildServerSslContext(providerConnectionConfig);
        p.addLast("ssl", sslContext.newHandler(ctx.alloc()));
        p.addLast(
                "unificationA",
                new NettyPortUnificationServerHandler(url, false, protocols, handler, urlMapper, handlerMapper));
        p.addLast("ALPN", new ApplicationProtocolNegotiationHandler(ApplicationProtocolNames.HTTP_1_1) {
            @Override
            protected void configurePipeline(ChannelHandlerContext ctx, String protocol) throws Exception {
                if (!ApplicationProtocolNames.HTTP_2.equals(protocol)) {
                    return;
                }
                NettyChannel channel = NettyChannel.getOrAddChannel(ctx.channel(), url, handler);
                ByteBuf in = ctx.alloc().buffer();
                detectProtocol(ctx, url, channel, in);
            }
        });
        p.remove(this);
    }

    private boolean canDetectSsl(ByteBuf buf) {
        // at least 5 bytes to determine if data is encrypted
        return detectSsl && buf.readableBytes() >= 5;
    }

    private boolean isSsl(ByteBuf buf) {
        // at least 5 bytes to determine if data is encrypted
        if (detectSsl && buf.readableBytes() >= 5) {
            return SslHandler.isEncrypted(buf);
        }
        return false;
    }

    private void detectProtocol(ChannelHandlerContext ctx, URL url, NettyChannel channel, ByteBuf in) {
        Set<String> supportedProtocolNames = new HashSet<>(protocols.keySet());
        supportedProtocolNames.retainAll(urlMapper.keySet());

        for (final String name : supportedProtocolNames) {
            WireProtocol protocol = protocols.get(name);
            in.markReaderIndex();
            ChannelBuffer buf = new NettyBackedChannelBuffer(in);
            final ProtocolDetector.Result result = protocol.detector().detect(buf);
            in.resetReaderIndex();
            switch (result.flag()) {
                case UNRECOGNIZED:
                    continue;
                case RECOGNIZED:
                    ChannelHandler localHandler = this.handlerMapper.getOrDefault(name, handler);
                    URL localURL = this.urlMapper.getOrDefault(name, url);
                    channel.setUrl(localURL);
                    NettyConfigOperator operator = new NettyConfigOperator(channel, localHandler);
                    operator.setDetectResult(result);
                    protocol.configServerProtocolHandler(url, operator);
                    ctx.pipeline().remove(this);
                case NEED_MORE_DATA:
                    return;
                default:
                    return;
            }
        }
        byte[] preface = new byte[in.readableBytes()];
        in.readBytes(preface);
        Set<String> supported = url.getApplicationModel().getSupportedExtensions(WireProtocol.class);
        LOGGER.error(
                INTERNAL_ERROR,
                "unknown error in remoting module",
                "",
                String.format(
                        "Can not recognize protocol from downstream=%s . " + "preface=%s protocols=%s",
                        ctx.channel().remoteAddress(), Bytes.bytes2hex(preface), supported));

        // Unknown protocol; discard everything and close the connection.
        in.clear();
        ctx.close();
    }
}
