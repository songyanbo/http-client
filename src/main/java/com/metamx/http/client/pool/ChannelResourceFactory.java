/*
 * Copyright 2011 Metamarkets Group Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.metamx.http.client.pool;

import com.google.common.base.Preconditions;
import com.metamx.common.logger.Logger;
import org.jboss.netty.bootstrap.ClientBootstrap;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelException;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.handler.ssl.ImmediateExecutor;
import org.jboss.netty.handler.ssl.SslHandler;
import org.jboss.netty.util.Timer;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.URL;

/**
 */
public class ChannelResourceFactory implements ResourceFactory<String, ChannelFuture>
{
  private static final Logger log = new Logger(ChannelResourceFactory.class);

  private static final long DEFAULT_SSL_HANDSHAKE_TIMEOUT = 10000L; /* 10 seconds */

  private final ClientBootstrap bootstrap;
  private final SSLContext sslContext;
  private final Timer timer;
  private final long sslHandshakeTimeout;

  public ChannelResourceFactory(
      ClientBootstrap bootstrap,
      SSLContext sslContext,
      Timer timer,
      long sslHandshakeTimeout
  )
  {
    this.bootstrap = Preconditions.checkNotNull(bootstrap, "bootstrap");
    this.sslContext = sslContext;
    this.timer = timer;
    this.sslHandshakeTimeout = sslHandshakeTimeout >= 0 ? sslHandshakeTimeout : DEFAULT_SSL_HANDSHAKE_TIMEOUT;

    if (sslContext != null) {
      Preconditions.checkNotNull(timer, "timer is required when sslContext is present");
    }
  }

  @Override
  public ChannelFuture generate(final String hostname)
  {
    log.info(String.format("Generating: %s", hostname));
    URL url = null;
    try {
      url = new URL(hostname);
    }
    catch (MalformedURLException e) {
      throw new RuntimeException(e);
    }

    final ChannelFuture retVal;
    final ChannelFuture connectFuture = bootstrap.connect(
        new InetSocketAddress(
            url.getHost(), url.getPort() == -1 ? url.getDefaultPort() : url.getPort()
        )
    );

    if ("https".equals(url.getProtocol())) {
      if (sslContext == null) {
        throw new IllegalStateException("No sslContext set, cannot do https");
      }

      final SSLEngine sslEngine = sslContext.createSSLEngine();
      sslEngine.setUseClientMode(true);
      final SslHandler sslHandler = new SslHandler(
          sslEngine,
          SslHandler.getDefaultBufferPool(),
          false,
          ImmediateExecutor.INSTANCE,
          timer,
          sslHandshakeTimeout
      );

      // https://github.com/netty/netty/issues/160
      sslHandler.setCloseOnSSLException(true);

      final ChannelPipeline pipeline = connectFuture.getChannel().getPipeline();
      pipeline.addFirst("ssl", sslHandler);

      final ChannelFuture handshakeFuture = Channels.future(connectFuture.getChannel());
      connectFuture.addListener(
          new ChannelFutureListener()
          {
            @Override
            public void operationComplete(ChannelFuture f) throws Exception
            {
              if (f.isSuccess()) {
                sslHandler.handshake().addListener(
                    new ChannelFutureListener()
                    {
                      @Override
                      public void operationComplete(ChannelFuture f2) throws Exception
                      {
                        if (f2.isSuccess()) {
                          handshakeFuture.setSuccess();
                        } else {
                          handshakeFuture.setFailure(
                              new ChannelException(
                                  String.format("Failed to handshake with host[%s]", hostname),
                                  f2.getCause()
                              )
                          );
                        }
                      }
                    }
                );
              } else {
                handshakeFuture.setFailure(
                    new ChannelException(
                        String.format("Failed to connect to host[%s]", hostname),
                        f.getCause()
                    )
                );
              }
            }
          }
      );

      retVal = handshakeFuture;
    } else {
      retVal = connectFuture;
    }

    return retVal;
  }

  @Override
  public boolean isGood(ChannelFuture resource)
  {
    Channel channel = resource.awaitUninterruptibly().getChannel();

    boolean isSuccess = resource.isSuccess();
    boolean isConnected = channel.isConnected();
    boolean isOpen = channel.isOpen();

    if (log.isTraceEnabled()) {
      log.trace("isGood = isSucess[%s] && isConnected[%s] && isOpen[%s]", isSuccess, isConnected, isOpen);
    }

    return isSuccess && isConnected && isOpen;
  }

  @Override
  public void close(ChannelFuture resource)
  {
    log.trace("Closing");
    resource.awaitUninterruptibly().getChannel().close();
  }
}
