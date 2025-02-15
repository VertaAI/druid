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

import com.google.common.util.concurrent.ListenableFuture;
import io.netty.channel.ChannelException;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.timeout.ReadTimeoutException;
import org.apache.druid.java.util.common.StringUtils;
import org.apache.druid.java.util.common.lifecycle.Lifecycle;
import org.apache.druid.java.util.http.client.response.StatusResponseHandler;
import org.apache.druid.java.util.http.client.response.StatusResponseHolder;
import org.joda.time.Duration;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URL;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Tests with a bunch of goofy not-actually-http servers.
 */
public class JankyServersTest
{
  static ExecutorService exec;
  static ServerSocket silentServerSocket;
  static ServerSocket echoServerSocket;
  static ServerSocket closingServerSocket;

  @Rule
  public ExpectedException expectedException = ExpectedException.none();

  @BeforeClass
  public static void setUp() throws Exception
  {
    exec = Executors.newCachedThreadPool();

    silentServerSocket = new ServerSocket(0);
    echoServerSocket = new ServerSocket(0);
    closingServerSocket = new ServerSocket(0);

    exec.submit(
        () -> {
          while (!Thread.currentThread().isInterrupted()) {
            try (
                Socket clientSocket = silentServerSocket.accept();
                InputStream in = clientSocket.getInputStream()
            ) {
              while (in.read() != -1) {
                /* Do nothing. Read bytes till the end of the stream. */
              }
            }
            catch (Exception e) {
              // Suppress
            }
          }
        }
    );

    exec.submit(
        () -> {
          while (!Thread.currentThread().isInterrupted()) {
            try (
                Socket clientSocket = closingServerSocket.accept();
                InputStream in = clientSocket.getInputStream()
            ) {
              in.read();
              clientSocket.close();
            }
            catch (Exception e) {
              // Suppress
            }
          }
        }
    );

    exec.submit(
        () -> {
          while (!Thread.currentThread().isInterrupted()) {
            try (
                Socket clientSocket = echoServerSocket.accept();
                OutputStream out = clientSocket.getOutputStream();
                InputStream in = clientSocket.getInputStream()
            ) {
              int b;
              while ((b = in.read()) != -1) {
                out.write(b);
              }
            }
            catch (Exception e) {
              // Suppress
            }
          }
        }
    );
  }

  @AfterClass
  public static void tearDown() throws Exception
  {
    exec.shutdownNow();
    silentServerSocket.close();
    echoServerSocket.close();
    closingServerSocket.close();
  }

  @Test
  public void testHttpSilentServerWithGlobalTimeout() throws Throwable
  {
    final Lifecycle lifecycle = new Lifecycle();
    try {
      final HttpClientConfig config = HttpClientConfig.builder().withReadTimeout(new Duration(100)).build();
      final HttpClient client = HttpClientInit.createClient(config, lifecycle);
      final ListenableFuture<StatusResponseHolder> future = client
          .go(
              new Request(HttpMethod.GET, new URL(StringUtils.format("http://localhost:%d/", silentServerSocket.getLocalPort()))),
              StatusResponseHandler.getInstance()
          );

      final ExecutionException e = Assert.assertThrows(ExecutionException.class, future::get);
      Assert.assertTrue("ReadTimeoutException thrown by 'get'", e.getCause() instanceof ReadTimeoutException);
    }
    finally {
      lifecycle.stop();
    }
  }

  @Test
  public void testHttpSilentServerWithRequestTimeout() throws Throwable
  {
    final Lifecycle lifecycle = new Lifecycle();
    try {
      final HttpClientConfig config = HttpClientConfig.builder().withReadTimeout(new Duration(86400L * 365)).build();
      final HttpClient client = HttpClientInit.createClient(config, lifecycle);
      final ListenableFuture<StatusResponseHolder> future = client
          .go(
              new Request(HttpMethod.GET, new URL(StringUtils.format("http://localhost:%d/", silentServerSocket.getLocalPort()))),
              StatusResponseHandler.getInstance(),
              new Duration(100L)
          );

      final ExecutionException e = Assert.assertThrows(ExecutionException.class, future::get);
      Assert.assertTrue("ReadTimeoutException thrown by 'get'", e.getCause() instanceof ReadTimeoutException);
    }
    finally {
      lifecycle.stop();
    }
  }

  @Test
  public void testHttpsSilentServer() throws Throwable
  {
    final Lifecycle lifecycle = new Lifecycle();
    try {
      final HttpClientConfig config = HttpClientConfig.builder()
                                                      .withSslContext(SSLContext.getDefault())
                                                      .withSslHandshakeTimeout(new Duration(100))
                                                      .build();
      final HttpClient client = HttpClientInit.createClient(config, lifecycle);

      final ListenableFuture<StatusResponseHolder> response = client
          .go(
              new Request(HttpMethod.GET, new URL(StringUtils.format("https://localhost:%d/", silentServerSocket.getLocalPort()))),
              StatusResponseHandler.getInstance()
          );

      final ExecutionException e = Assert.assertThrows(ExecutionException.class, response::get);
      Assert.assertTrue("ChannelException thrown by 'get'", e.getCause() instanceof ChannelException);
    }
    finally {
      lifecycle.stop();
    }
  }

  @Test
  public void testHttpConnectionClosingServer() throws Throwable
  {
    final Lifecycle lifecycle = new Lifecycle();
    try {
      final HttpClientConfig config = HttpClientConfig.builder().build();
      final HttpClient client = HttpClientInit.createClient(config, lifecycle);
      final ListenableFuture<StatusResponseHolder> response = client
          .go(
              new Request(HttpMethod.GET, new URL(StringUtils.format("http://localhost:%d/", closingServerSocket.getLocalPort()))),
              StatusResponseHandler.getInstance()
          );

      final ExecutionException e = Assert.assertThrows(ExecutionException.class, response::get);
      Assert.assertTrue("ChannelException thrown by 'get'", isChannelClosedException(e.getCause()));
    }
    finally {
      lifecycle.stop();
    }
  }

  @Test
  public void testHttpsConnectionClosingServer() throws Throwable
  {
    final Lifecycle lifecycle = new Lifecycle();
    try {
      final HttpClientConfig config = HttpClientConfig.builder().withSslContext(SSLContext.getDefault()).build();
      final HttpClient client = HttpClientInit.createClient(config, lifecycle);

      final ListenableFuture<StatusResponseHolder> response = client
          .go(
              new Request(HttpMethod.GET, new URL(StringUtils.format("https://localhost:%d/", closingServerSocket.getLocalPort()))),
              StatusResponseHandler.getInstance()
          );

      final ExecutionException e = Assert.assertThrows(ExecutionException.class, response::get);
      Assert.assertTrue("ChannelException thrown by 'get'", isChannelClosedException(e.getCause()));
    }
    finally {
      lifecycle.stop();
    }
  }

  public boolean isChannelClosedException(Throwable e)
  {
    return e instanceof ChannelException ||
           (e instanceof SocketException && e.getMessage().contains("Connection reset")) ||
           (e instanceof IOException && e.getMessage().contains("Connection reset by peer"));
  }

  @Test
  public void testHttpEchoServer()
  {
    ExecutionException exception = Assert.assertThrows(ExecutionException.class, () -> {
      final Lifecycle lifecycle = new Lifecycle();
      try {
        final HttpClientConfig config = HttpClientConfig.builder().build();
        HttpClientInit.createClient(config, lifecycle)
                      .go(
                          new Request(
                              HttpMethod.GET,
                              new URL(StringUtils.format("http://localhost:%d/", echoServerSocket.getLocalPort()))
                          ),
                          StatusResponseHandler.getInstance()
                      )
                      .get();
      }
      finally {
        lifecycle.stop();
      }
    });

    Assert.assertEquals("java.lang.IllegalArgumentException: invalid version format: GET", exception.getMessage());
  }

  @Test
  public void testHttpsEchoServer()
  {
    final ExecutionException exception = Assert.assertThrows(ExecutionException.class, () -> {
      final Lifecycle lifecycle = new Lifecycle();
      try {
        final HttpClientConfig config = HttpClientConfig.builder().withSslContext(SSLContext.getDefault()).build();
        HttpClientInit.createClient(config, lifecycle)
                      .go(
                          new Request(
                              HttpMethod.GET,
                              new URL(StringUtils.format("https://localhost:%d/", echoServerSocket.getLocalPort()))
                          ),
                          StatusResponseHandler.getInstance()
                      )
                      .get();
      }
      finally {
        lifecycle.stop();
      }
    });

    Assert.assertEquals("io.netty.channel.ChannelException: Faulty channel in resource pool", exception.getMessage());
  }
}
