/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.java.util.http.client;

import io.netty.channel.ChannelException;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.apache.druid.java.util.common.StringUtils;
import org.apache.druid.java.util.common.lifecycle.Lifecycle;
import org.apache.druid.java.util.http.client.response.StatusResponseHandler;
import org.apache.druid.java.util.http.client.response.StatusResponseHolder;
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

import javax.annotation.Nonnull;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

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
        () -> {
          while (!Thread.currentThread().isInterrupted()) {
            try (
                Socket clientSocket = serverSocket.accept();
                BufferedReader in = new BufferedReader(
                    new InputStreamReader(clientSocket.getInputStream(), StandardCharsets.UTF_8)
                );
                OutputStream out = clientSocket.getOutputStream()
            ) {
              while (!in.readLine().equals("")) {
                // skip lines
              }
              out.write("HTTP/1.1 200 OK\r\nContent-Length: 6\r\n\r\nhello!".getBytes(StandardCharsets.UTF_8));
            }
            catch (Exception e) {
              // Suppress
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
              new Request(
                  HttpMethod.GET,
                  new URL(StringUtils.format("http://localhost:%d/", serverSocket.getLocalPort()))
              ),
              StatusResponseHandler.getInstance()
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
  public void testFriendlyProxyHttpServer() throws Exception
  {
    final AtomicReference<String> requestContent = new AtomicReference<>();

    final ExecutorService exec = Executors.newSingleThreadExecutor();
    final ServerSocket serverSocket = new ServerSocket(0);
    exec.submit(
        () -> {
          while (!Thread.currentThread().isInterrupted()) {
            try (
                Socket clientSocket = serverSocket.accept();
                BufferedReader in = new BufferedReader(
                    new InputStreamReader(clientSocket.getInputStream(), StandardCharsets.UTF_8)
                );
                OutputStream out = clientSocket.getOutputStream()
            ) {
              StringBuilder request = new StringBuilder();
              String line;
              while (!"".equals((line = in.readLine()))) {
                request.append(line).append("\r\n");
              }
              requestContent.set(request.toString());
              out.write("HTTP/1.1 200 OK\r\n\r\n".getBytes(StandardCharsets.UTF_8));

              while (!in.readLine().equals("")) {
                // skip lines
              }
              out.write("HTTP/1.1 200 OK\r\nContent-Length: 6\r\n\r\nhello!".getBytes(StandardCharsets.UTF_8));
            }
            catch (Exception e) {
              Assert.fail(e.toString());
            }
          }
        }
    );

    final Lifecycle lifecycle = new Lifecycle();
    try {
      final HttpClientConfig config = HttpClientConfig
          .builder()
          .withHttpProxyConfig(
              new HttpClientProxyConfig("localhost", serverSocket.getLocalPort(), "bob", "sally")
          )
          .build();
      final HttpClient client = HttpClientInit.createClient(config, lifecycle);
      final StatusResponseHolder response = client
          .go(
              new Request(
                  HttpMethod.GET,
                  new URL("http://anotherHost:8080/")
              ),
              StatusResponseHandler.getInstance()
          ).get();

      Assert.assertEquals(200, response.getStatus().code());
      Assert.assertEquals("hello!", response.getContent());

      Assert.assertEquals(
          "CONNECT anotherHost:8080 HTTP/1.1\r\nproxy-authorization: Basic Ym9iOnNhbGx5\r\n",
          requestContent.get()
      );
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
    final AtomicReference<String> foundAcceptEncoding = acceptEncodingServer(exec, serverSocket);

    final Lifecycle lifecycle = new Lifecycle();
    try {
      final HttpClientConfig config = HttpClientConfig.builder()
                                                      .withCompressionCodec(HttpClientConfig.CompressionCodec.IDENTITY)
                                                      .build();
      final HttpClient client = HttpClientInit.createClient(config, lifecycle);
      final StatusResponseHolder response = client
          .go(
              new Request(
                  HttpMethod.GET,
                  new URL(StringUtils.format("http://localhost:%d/", serverSocket.getLocalPort()))
              ),
              StatusResponseHandler.getInstance()
          ).get();

      Assert.assertEquals(200, response.getStatus().code());
      Assert.assertEquals("hello!", response.getContent());
      Assert.assertEquals("accept-encoding: identity", foundAcceptEncoding.get());
    }
    finally {
      exec.shutdownNow();
      serverSocket.close();
      lifecycle.stop();
    }
  }

  @Test
  public void testCompressionCodecHeader() throws Exception
  {
    final ExecutorService exec = Executors.newSingleThreadExecutor();
    final ServerSocket serverSocket = new ServerSocket(0);
    final AtomicReference<String> foundAcceptEncoding = acceptEncodingServer(exec, serverSocket);

    final Lifecycle lifecycle = new Lifecycle();
    try {
      final HttpClientConfig config = HttpClientConfig.builder()
                                                      .withCompressionCodec(HttpClientConfig.CompressionCodec.IDENTITY)
                                                      .build();
      final HttpClient client = HttpClientInit.createClient(config, lifecycle);
      final StatusResponseHolder response = client
          .go(
              new Request(
                  HttpMethod.GET,
                  new URL(StringUtils.format("http://localhost:%d/", serverSocket.getLocalPort()))
              ).addHeader(HttpHeaderNames.ACCEPT_ENCODING.toString(), "gzip"),
              StatusResponseHandler.getInstance()
          ).get();

      Assert.assertEquals(200, response.getStatus().code());
      Assert.assertEquals("hello!", response.getContent());
      Assert.assertEquals("accept-encoding: gzip", foundAcceptEncoding.get());
    }
    finally {
      exec.shutdownNow();
      serverSocket.close();
      lifecycle.stop();
    }
  }

  @Nonnull
  private AtomicReference<String> acceptEncodingServer(ExecutorService exec, ServerSocket serverSocket)
  {
    final AtomicReference<String> foundAcceptEncoding = new AtomicReference<>(null);

    exec.submit(
        () -> {
          while (!Thread.currentThread().isInterrupted()) {
            try (
                Socket clientSocket = serverSocket.accept();
                BufferedReader in = new BufferedReader(
                    new InputStreamReader(clientSocket.getInputStream(), StandardCharsets.UTF_8)
                );
                OutputStream out = clientSocket.getOutputStream()
            ) {
              // Read headers
              String header;
              while (!(header = in.readLine()).equals("")) {
                if (header.startsWith("accept-encoding:")) {
                  foundAcceptEncoding.set(header);
                }
              }
              out.write("HTTP/1.1 200 OK\r\nContent-Length: 6\r\n\r\nhello!".getBytes(StandardCharsets.UTF_8));
            }
            catch (Exception ignored) {
              // Suppress
            }
          }
        }
    );
    return foundAcceptEncoding;
  }

  @Test
  public void testFriendlySelfSignedHttpsServer() throws Exception
  {
    final Lifecycle lifecycle = new Lifecycle();
    final String keyStorePath = getClass().getClassLoader().getResource("keystore.jks").getFile();
    Server server = new Server();

    HttpConfiguration https = new HttpConfiguration();
    https.addCustomizer(new SecureRequestCustomizer());

    SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
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
                    new URL(StringUtils.format("https://localhost:%d/", sslConnector.getLocalPort()))
                ),
                StatusResponseHandler.getInstance()
            ).get().getStatus();
        Assert.assertEquals(404, status.code());
      }

      // Incorrect name ("127.0.0.1")
      final Throwable cause = Assert.assertThrows(ExecutionException.class, () -> {
        trustingClient
            .go(
                new Request(
                    HttpMethod.GET,
                    new URL(StringUtils.format("https://127.0.0.1:%d/", sslConnector.getLocalPort()))
                ),
                StatusResponseHandler.getInstance()
            ).get();


      }).getCause();
      Assert.assertTrue("ChannelException thrown by 'get'", cause instanceof ChannelException);
      Assert.assertTrue("Expected error message", cause.getCause().getMessage().contains("Failed to handshake"));

      final Throwable untrustedCause = Assert.assertThrows(ExecutionException.class, () -> {
        // Untrusting client
        skepticalClient
            .go(
                new Request(
                    HttpMethod.GET,
                    new URL(StringUtils.format("https://localhost:%d/", sslConnector.getLocalPort()))
                ),
                StatusResponseHandler.getInstance()
            )
            .get();
      }).getCause();
      Assert.assertTrue("ChannelException thrown by 'get'", untrustedCause instanceof ChannelException);
      Assert.assertTrue(
          "Root cause is SSLHandshakeException",
          untrustedCause.getCause().getCause() instanceof SSLHandshakeException
      );
    }
    finally {
      lifecycle.stop();
      server.stop();
    }
  }

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
                StatusResponseHandler.getInstance()
            ).get().getStatus();

        Assert.assertEquals(200, status.code());
      }

      {
        final HttpResponseStatus status = client
            .go(
                new Request(HttpMethod.POST, new URL("https://httpbin.org/post"))
                    .setContent(new byte[]{'a', 'b', 'c', 1, 2, 3}),
                StatusResponseHandler.getInstance()
            ).get().getStatus();

        Assert.assertEquals(200, status.code());
      }
    }
    finally {
      lifecycle.stop();
    }
  }
}
