/*
 * Copyright 2011 - 2015 Metamarkets Group Inc.
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

package com.metamx.http.client;

import com.google.common.base.Charsets;
import com.google.common.util.concurrent.ListenableFuture;
import com.metamx.common.lifecycle.Lifecycle;
import com.metamx.http.client.response.StatusResponseHandler;
import com.metamx.http.client.response.StatusResponseHolder;
import io.netty.channel.ChannelException;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;

/**
 * Tests with servers that are at least moderately well-behaving.
 */
public class FriendlyServersTest
{
  @Test
  public void testFriendlyHttpServer() throws Exception
  {
    final ExecutorService exec = Executors.newSingleThreadExecutor();
    final ServerSocket serverSocket = new ServerSocket(0);
    exec.submit(
        new Runnable()
        {
          @Override
          public void run()
          {
            while (!Thread.currentThread().isInterrupted()) {
              try (
                  Socket clientSocket = serverSocket.accept();
                  BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
                  OutputStream out = clientSocket.getOutputStream()
              ) {
                while (!in.readLine().equals("")) {
                  ; // skip lines
                }
                out.write("HTTP/1.1 200 OK\r\nContent-Length: 6\r\n\r\nhello!".getBytes(Charsets.UTF_8));
              }
              catch (Exception e) {
                // Suppress
              }
            }
          }
        }
    );

    final Lifecycle lifecycle = new Lifecycle();
    try {
      final HttpClientConfig config = HttpClientConfig.builder().build();
      final HttpClient client = HttpClientInit.createClient(config, lifecycle);
      final StatusResponseHolder response = client
          .go(
              new Request(HttpMethod.GET, new URL(String.format("http://localhost:%d/", serverSocket.getLocalPort()))),
              new StatusResponseHandler(Charsets.UTF_8)
          ).get();

      Assert.assertEquals(200, response.getStatus().code());
      Assert.assertEquals("hello!", response.getContent());
    }
    finally {
      exec.shutdownNow();
      serverSocket.close();
      lifecycle.stop();
    }
  }

  @Test
  public void testCompressionCodecConfig() throws Exception
  {
    final ExecutorService exec = Executors.newSingleThreadExecutor();
    final ServerSocket serverSocket = new ServerSocket(0);
    final AtomicBoolean foundAcceptEncoding = new AtomicBoolean();
    exec.submit(
        new Runnable()
        {
          @Override
          public void run()
          {
            while (!Thread.currentThread().isInterrupted()) {
              try (
                  Socket clientSocket = serverSocket.accept();
                  BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
                  OutputStream out = clientSocket.getOutputStream()
              ) {
                // Read headers
                String header;
                while (!(header = in.readLine()).equals("")) {
                  if (header.toLowerCase().equals("Accept-Encoding: identity".toLowerCase())) {
                    foundAcceptEncoding.set(true);
                  }
                }
                out.write("HTTP/1.1 200 OK\r\nContent-Length: 6\r\n\r\nhello!".getBytes(Charsets.UTF_8));
              }
              catch (Exception e) {
                // Suppress
              }
            }
          }
        }
    );

    final Lifecycle lifecycle = new Lifecycle();
    try {
      final HttpClientConfig config = HttpClientConfig.builder()
                                                      .withCompressionCodec(HttpClientConfig.CompressionCodec.IDENTITY)
                                                      .build();
      final HttpClient client = HttpClientInit.createClient(config, lifecycle);
      final StatusResponseHolder response = client
          .go(
              new Request(HttpMethod.GET, new URL(String.format("http://localhost:%d/", serverSocket.getLocalPort()))),
              new StatusResponseHandler(Charsets.UTF_8)
          ).get();

      Assert.assertEquals(200, response.getStatus().code());
      Assert.assertEquals("hello!", response.getContent());
      Assert.assertTrue(foundAcceptEncoding.get());
    }
    finally {
      exec.shutdownNow();
      serverSocket.close();
      lifecycle.stop();
    }
  }

  @Test
  public void testFriendlySelfSignedHttpsServer() throws Exception
  {
    final Lifecycle lifecycle = new Lifecycle();
    final String keyStorePath = getClass().getClassLoader().getResource("keystore.jks").getFile();
    Server server = new Server();

    HttpConfiguration https = new HttpConfiguration();
    https.addCustomizer(new SecureRequestCustomizer());

    SslContextFactory sslContextFactory = new SslContextFactory();
    sslContextFactory.setKeyStorePath(keyStorePath);
    sslContextFactory.setKeyStorePassword("abc123");
    sslContextFactory.setKeyManagerPassword("abc123");

    ServerConnector sslConnector = new ServerConnector(
        server,
        new SslConnectionFactory(sslContextFactory, "http/1.1"),
        new HttpConnectionFactory(https)
    );

    sslConnector.setPort(0);
    server.setConnectors(new Connector[]{sslConnector});
    server.start();

    try {
      final SSLContext mySsl = HttpClientInit.sslContextWithTrustedKeyStore(keyStorePath, "abc123");
      final HttpClientConfig trustingConfig = HttpClientConfig.builder().withSslContext(mySsl).build();
      final HttpClient trustingClient = HttpClientInit.createClient(trustingConfig, lifecycle);

      final HttpClientConfig skepticalConfig = HttpClientConfig.builder()
                                                               .withSslContext(SSLContext.getDefault())
                                                               .build();
      final HttpClient skepticalClient = HttpClientInit.createClient(skepticalConfig, lifecycle);

      // Correct name ("localhost")
      {
        final HttpResponseStatus status = trustingClient
            .go(
                new Request(
                    HttpMethod.GET,
                    new URL(String.format("https://localhost:%d/", sslConnector.getLocalPort()))
                ),
                new StatusResponseHandler(Charsets.UTF_8)
            ).get().getStatus();
        Assert.assertEquals(404, status.code());
      }

      // Incorrect name ("127.0.0.1")
      {
        final ListenableFuture<StatusResponseHolder> response1 = trustingClient
            .go(
                new Request(
                    HttpMethod.GET,
                    new URL(String.format("https://127.0.0.1:%d/", sslConnector.getLocalPort()))
                ),
                new StatusResponseHandler(Charsets.UTF_8)
            );

        Throwable ea = null;
        try {
          response1.get();
        }
        catch (ExecutionException e) {
          ea = e.getCause();
        }

        Assert.assertTrue("ChannelException thrown by 'get'", ea instanceof ChannelException);
        Assert.assertTrue("Expected error message", ea.getCause().getMessage().matches(".*Failed to handshake.*"));
      }

      {
        // Untrusting client
        final ListenableFuture<StatusResponseHolder> response2 = skepticalClient
            .go(
                new Request(
                    HttpMethod.GET, new URL(String.format("https://localhost:%d/", sslConnector.getLocalPort()))
                ),
                new StatusResponseHandler(Charsets.UTF_8)
            );

        Throwable eb = null;
        try {
          response2.get();
        }
        catch (ExecutionException e) {
          eb = e.getCause();
        }
        Assert.assertNotNull("ChannelException thrown by 'get'", eb);
        Assert.assertTrue(
            "Root cause is SSLHandshakeException",
            eb.getCause().getCause() instanceof SSLHandshakeException
        );
      }
    }
    finally {
      lifecycle.stop();
      server.stop();
    }
  }

/*
  @Test
  public void testFriendlySelfSignedHttpsServerWithNetty() throws Exception
  {
    String keyStoreFilePath = getClass().getClassLoader().getResource("keystore.jks").getFile();
    String keyStoreFilePassword = "abc123";

    KeyStore ks = KeyStore.getInstance("JKS");
    FileInputStream fin = new FileInputStream(keyStoreFilePath);
    ks.load(fin, keyStoreFilePassword.toCharArray());

    KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    kmf.init(ks, keyStoreFilePassword.toCharArray());

    SSLContext serverContext = SSLContext.getInstance("TLS");
    serverContext.init(kmf.getKeyManagers(), null, null);

    SSLEngine sslEngine = serverContext.createSSLEngine();
    sslEngine.setUseClientMode(false);
    sslEngine.setEnabledProtocols(sslEngine.getSupportedProtocols());
    sslEngine.setEnabledCipherSuites(sslEngine.getSupportedCipherSuites());
    sslEngine.setEnableSessionCreation(true);

    final SslHandler sslHandler = new SslHandler(sslEngine);
    sslHandler.setIssueHandshake(true);
    sslHandler.setCloseOnSSLException(true);
//    sslHandler.setEnableRenegotiation(false);

    ServerBootstrap bootstrap = new ServerBootstrap(
        new NioServerSocketChannelFactory(
            Executors.newCachedThreadPool(),
            Executors.newCachedThreadPool()
        ));

    // Enable TCP_NODELAY to handle pipelined requests without latency.
    bootstrap.setOption("child.tcpNoDelay", true);


    bootstrap.setPipelineFactory(new ChannelPipelineFactory()
    {

      @Override
      public ChannelPipeline getPipeline() throws Exception
      {
        ChannelPipeline pipeline = new DefaultChannelPipeline();
        pipeline.addLast("first", new LoggingHandler());
        pipeline.addLast("ssl", sslHandler);
        pipeline.addLast("decoder", new HttpRequestDecoder());
        pipeline.addLast("encoder", new HttpResponseEncoder());
        pipeline.addLast("handler", new HttpServerHandler());
        return pipeline;
      }
    });

    Channel channel = bootstrap.bind(new InetSocketAddress(0));
    InetSocketAddress localAddress = (InetSocketAddress) channel.getLocalAddress();

    Lifecycle lifecycle = new Lifecycle();
    try {
      final SSLContext mySsl = HttpClientInit.sslContextWithTrustedKeyStore(keyStoreFilePath, keyStoreFilePassword);
      final HttpClientConfig trustingConfig = HttpClientConfig.builder().withSslContext(mySsl).build();
      final HttpClient trustingClient = HttpClientInit.createClient(trustingConfig, lifecycle);

      final HttpClientConfig skepticalConfig = HttpClientConfig.builder()
                                                               .withSslContext(SSLContext.getDefault())
                                                               .build();
      final HttpClient skepticalClient = HttpClientInit.createClient(skepticalConfig, lifecycle);

      // Correct name ("localhost")
*/
/*
      System.out.println("Correct name (localhost)");
      {
        final HttpResponseStatus status = trustingClient
            .go(
                new Request(HttpMethod.GET, new URL(String.format("https://localhost:%d/", localAddress.getPort()))),
                new StatusResponseHandler(Charsets.UTF_8)
            ).get().getStatus();
        Assert.assertEquals(200, status.getCode());
      }
*//*


      // Incorrect name ("127.0.0.1")
      System.out.println("Incorrect name (127.0.0.1)");
      {
        final ListenableFuture<StatusResponseHolder> response1 = trustingClient
            .go(
                new Request(HttpMethod.GET, new URL(String.format("https://127.0.0.1:%d/", localAddress.getPort()))),
                new StatusResponseHandler(Charsets.UTF_8)
            );

        Throwable ea = null;
        try {
          response1.get();
        }
        catch (ExecutionException e) {
          ea = e.getCause();
        }

        Assert.assertTrue("ChannelException thrown by 'get'", ea instanceof ChannelException);
        Assert.assertTrue("Expected error message", ea.getCause().getMessage().matches(".*Failed to handshake.*"));
      }

*/
/*
      {
        // Untrusting client
        System.out.println("Untrusting client");
        final ListenableFuture<StatusResponseHolder> response2 = skepticalClient
            .go(
                new Request(
                    HttpMethod.GET, new URL(String.format("https://localhost:%d/", localAddress.getPort()))
                ),
                new StatusResponseHandler(Charsets.UTF_8)
            );

        Throwable eb = null;
        try {
          response2.get();
        }
        catch (ExecutionException e) {
          eb = e.getCause();
        }
        Assert.assertNotNull("ChannelException thrown by 'get'", eb);
        Assert.assertTrue(
            "Root cause is SSLHandshakeException",
            eb.getCause().getCause() instanceof SSLHandshakeException
        );
      }
*//*

    }
    finally {
      lifecycle.stop();
    }
  }
*/

  @Test
  @Ignore
  public void testHttpBin() throws Throwable
  {
    final Lifecycle lifecycle = new Lifecycle();
    try {
      final HttpClientConfig config = HttpClientConfig.builder().withSslContext(SSLContext.getDefault()).build();
      final HttpClient client = HttpClientInit.createClient(config, lifecycle);

      {
        final HttpResponseStatus status = client
            .go(
                new Request(HttpMethod.GET, new URL("https://httpbin.org/get")),
                new StatusResponseHandler(Charsets.UTF_8)
            ).get().getStatus();

        Assert.assertEquals(200, status.code());
      }

      {
        final HttpResponseStatus status = client
            .go(
                new Request(HttpMethod.POST, new URL("https://httpbin.org/post"))
                    .setContent(new byte[]{'a', 'b', 'c', 1, 2, 3}),
                new StatusResponseHandler(Charsets.UTF_8)
            ).get().getStatus();

        Assert.assertEquals(200, status.code());
      }
    }
    finally {
      lifecycle.stop();
    }
  }

/*
  public class HttpServerHandler extends SimpleChannelUpstreamHandler
  {
    @Override
    public void messageReceived(ChannelHandlerContext ctx, MessageEvent e)
    {
      HttpResponse response = new DefaultHttpResponse(HTTP_1_1, OK);
      ChannelFuture future = e.getChannel().write(response);
      future.addListener(ChannelFutureListener.CLOSE);
    }

*/
/*
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e)
    {
      e.getCause().printStackTrace();
      e.getChannel().close();
      ctx.sendDownstream(e);
    }
*//*

  }
*/

/*
  public class LoggingHandler implements ChannelUpstreamHandler, ChannelDownstreamHandler {
    @Override
    public void handleDownstream(ChannelHandlerContext ctx, ChannelEvent e) throws Exception
    {
      System.out.println("Outgoing event: " + e);
      ctx.sendDownstream(e);
    }

    @Override
    public void handleUpstream(ChannelHandlerContext ctx, ChannelEvent e) throws Exception
    {
      System.out.println("Incoming event: " + e);
      ctx.sendUpstream(e);
    }
  }
*/
}


