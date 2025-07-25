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
package org.apache.dubbo.rpc.protocol.tri.h12.http2;

import org.apache.dubbo.remoting.http12.HttpConstants;
import org.apache.dubbo.remoting.http12.HttpHeaderNames;
import org.apache.dubbo.remoting.http12.HttpMetadata;
import org.apache.dubbo.remoting.http12.HttpOutputMessage;
import org.apache.dubbo.remoting.http12.HttpResult;
import org.apache.dubbo.remoting.http12.h2.H2StreamChannel;
import org.apache.dubbo.remoting.http12.message.HttpMessageEncoder;
import org.apache.dubbo.remoting.http12.message.ServerSentEventEncoder;
import org.apache.dubbo.rpc.model.FrameworkModel;

public final class Http2SseServerChannelObserver extends Http2StreamServerChannelObserver {

    private HttpMessageEncoder originalResponseEncoder;

    public Http2SseServerChannelObserver(FrameworkModel frameworkModel, H2StreamChannel h2StreamChannel) {
        super(frameworkModel, h2StreamChannel);
    }

    @Override
    public void setResponseEncoder(HttpMessageEncoder responseEncoder) {
        super.setResponseEncoder(new ServerSentEventEncoder(responseEncoder));
        this.originalResponseEncoder = responseEncoder;
    }

    @Override
    protected HttpMetadata encodeHttpMetadata(boolean endStream) {
        return super.encodeHttpMetadata(endStream)
                .header(HttpHeaderNames.CACHE_CONTROL.getKey(), HttpConstants.NO_CACHE);
    }

    @Override
    protected HttpOutputMessage buildMessage(int statusCode, Object data) throws Throwable {
        if (data instanceof HttpResult) {
            data = ((HttpResult<?>) data).getBody();

            if (data == null && statusCode != 200) {
                return null;
            }

            HttpOutputMessage message = encodeHttpOutputMessage(data);
            try {
                originalResponseEncoder.encode(message.getBody(), data);
            } catch (Throwable t) {
                message.close();
                throw t;
            }
            return message;
        }
        return super.buildMessage(statusCode, data);
    }

    @Override
    protected void doOnCompleted(Throwable throwable) {
        // if throwable is not null, the header will be flushed by super.doOnCompleted(throwable)
        if (!isHeaderSent() && throwable == null) {
            sendMetadata(encodeHttpMetadata(true));
        }
        super.doOnCompleted(throwable);
    }
}
