/*
 * Copyright 2021 TiKV Project Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.tikv.common.util;

import com.google.common.annotations.VisibleForTesting;
import io.grpc.ManagedChannel;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.NettyChannelBuilder;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import java.io.File;
import java.io.FileInputStream;
import java.net.URI;
import java.security.KeyStore;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLException;
import javax.net.ssl.TrustManagerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tikv.common.HostMapping;
import org.tikv.common.pd.PDUtils;

public class ChannelFactory implements AutoCloseable {
  private static final Logger logger = LoggerFactory.getLogger(ChannelFactory.class);

  private final int maxFrameSize;
  private final int keepaliveTime;
  private final int keepaliveTimeout;
  private final int idleTimeout;
  private final ConcurrentHashMap<String, ManagedChannel> connPool = new ConcurrentHashMap<>();
  private final CertContext certContext;
  private final AtomicReference<SslContextBuilder> sslContextBuilder = new AtomicReference<>();
  private static final String PUB_KEY_INFRA = "PKIX";

  private abstract static class CertContext {
    protected abstract boolean isModified();

    protected abstract SslContextBuilder createSslContextBuilder();

    public SslContextBuilder reload() {
      final String threadName = Thread.currentThread().getName();
      logger.info("check and reload ssl context in thread {}", threadName);
      if (isModified()) {
        logger.info("reload ssl context in thread {}", threadName);
        return createSslContextBuilder();
      }
      logger.info("certificate is not modified in thread {}", threadName);
      return null;
    }
  }

  private static class JksContext extends CertContext {
    private long keyLastModified;
    private long trustLastModified;

    private final String keyPath;
    private final String keyPassword;
    private final String trustPath;
    private final String trustPassword;

    public JksContext(String keyPath, String keyPassword, String trustPath, String trustPassword) {
      this.keyLastModified = 0;
      this.trustLastModified = 0;

      this.keyPath = keyPath;
      this.keyPassword = keyPassword;
      this.trustPath = trustPath;
      this.trustPassword = trustPassword;
    }

    @Override
    protected synchronized boolean isModified() {
      long a = new File(keyPath).lastModified();
      long b = new File(trustPath).lastModified();

      boolean changed = this.keyLastModified != a || this.trustLastModified != b;
      logger.info("key modified at {}, trust modified at {}", a, b);
      logger.info(
          "key last modified at {}, trust last modified at {}",
          this.keyLastModified,
          this.trustLastModified);
      logger.info("key or trust changed: {}", changed);

      if (changed) {
        this.keyLastModified = a;
        this.trustLastModified = b;
      }

      return changed;
    }

    @Override
    protected SslContextBuilder createSslContextBuilder() {
      SslContextBuilder builder = GrpcSslContexts.forClient();
      try {
        if (keyPath != null && keyPassword != null) {
          KeyStore keyStore = KeyStore.getInstance("JKS");
          try (FileInputStream fis = new FileInputStream(keyPath)) {
            keyStore.load(fis, keyPassword.toCharArray());
          }
          KeyManagerFactory keyManagerFactory =
              KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
          keyManagerFactory.init(keyStore, keyPassword.toCharArray());
          builder.keyManager(keyManagerFactory);
        }
        if (trustPath != null && trustPassword != null) {
          KeyStore trustStore = KeyStore.getInstance("JKS");
          try (FileInputStream fis = new FileInputStream(trustPath)) {
            trustStore.load(fis, trustPassword.toCharArray());
          }
          TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(PUB_KEY_INFRA);
          trustManagerFactory.init(trustStore);
          builder.trustManager(trustManagerFactory);
        }
      } catch (Exception e) {
        logger.error("JKS SSL context builder failed!", e);
      }
      return builder;
    }
  }

  private static class OpenSslContext extends CertContext {
    private long trustLastModified;
    private long chainLastModified;
    private long keyLastModified;

    private final String trustPath;
    private final String chainPath;
    private final String keyPath;

    public OpenSslContext(String trustPath, String chainPath, String keyPath) {
      this.trustLastModified = 0;
      this.chainLastModified = 0;
      this.keyLastModified = 0;

      this.trustPath = trustPath;
      this.chainPath = chainPath;
      this.keyPath = keyPath;
    }

    @Override
    protected synchronized boolean isModified() {
      long a = new File(trustPath).lastModified();
      long b = new File(chainPath).lastModified();
      long c = new File(keyPath).lastModified();

      boolean changed =
          this.trustLastModified != a || this.chainLastModified != b || this.keyLastModified != c;

      logger.info("trust modified at {}, chain modified at {}, key modified ", a, b, c);
      logger.info(
          "trust last modified at {}, chain last modified at {}, key last modified at {}",
          this.trustLastModified,
          this.chainLastModified,
          this.keyLastModified);
      logger.info("trust or chain or key changed: {}", changed);

      if (changed) {
        this.trustLastModified = a;
        this.chainLastModified = b;
        this.keyLastModified = c;
      }

      return changed;
    }

    @Override
    protected SslContextBuilder createSslContextBuilder() {
      SslContextBuilder builder = GrpcSslContexts.forClient();
      if (trustPath != null) {
        builder.trustManager(new File(trustPath));
      }
      if (chainPath != null && keyPath != null) {
        builder.keyManager(new File(chainPath), new File(keyPath));
      }
      return builder;
    }
  }

  public ChannelFactory(
      int maxFrameSize, int keepaliveTime, int keepaliveTimeout, int idleTimeout) {
    this.maxFrameSize = maxFrameSize;
    this.keepaliveTime = keepaliveTime;
    this.keepaliveTimeout = keepaliveTimeout;
    this.idleTimeout = idleTimeout;
    this.certContext = null;
    logger.info("Created channel factory without tls");
  }

  public ChannelFactory(
      int maxFrameSize,
      int keepaliveTime,
      int keepaliveTimeout,
      int idleTimeout,
      String trustCertCollectionFilePath,
      String keyCertChainFilePath,
      String keyFilePath) {
    this.maxFrameSize = maxFrameSize;
    this.keepaliveTime = keepaliveTime;
    this.keepaliveTimeout = keepaliveTimeout;
    this.idleTimeout = idleTimeout;
    this.certContext =
        new OpenSslContext(trustCertCollectionFilePath, keyCertChainFilePath, keyFilePath);
    logger.info("Created channel factory without tls using openssl");
  }

  public ChannelFactory(
      int maxFrameSize,
      int keepaliveTime,
      int keepaliveTimeout,
      int idleTimeout,
      String jksKeyPath,
      String jksKeyPassword,
      String jksTrustPath,
      String jksTrustPassword) {
    this.maxFrameSize = maxFrameSize;
    this.keepaliveTime = keepaliveTime;
    this.keepaliveTimeout = keepaliveTimeout;
    this.idleTimeout = idleTimeout;
    this.certContext = new JksContext(jksKeyPath, jksKeyPassword, jksTrustPath, jksTrustPassword);
    logger.info("Created channel factory without tls using jks");
  }

  @VisibleForTesting
  public boolean reloadSslContext() {
    if (certContext != null) {
      SslContextBuilder newBuilder = certContext.reload();
      if (newBuilder != null) {
        sslContextBuilder.set(newBuilder);
        return true;
      }
    }
    logger.info("Channel factory was created without tls");
    return false;
  }

  public ManagedChannel getChannel(String addressStr, HostMapping hostMapping) {
    if (reloadSslContext()) {
      logger.info("invalidate connection pool");
      connPool.clear();
    }

    return connPool.computeIfAbsent(
        addressStr,
        key -> {
          URI address;
          URI mappedAddr;
          try {
            address = PDUtils.addrToUri(key);
          } catch (Exception e) {
            throw new IllegalArgumentException("failed to form address " + key, e);
          }
          try {
            mappedAddr = hostMapping.getMappedURI(address);
          } catch (Exception e) {
            throw new IllegalArgumentException("failed to get mapped address " + address, e);
          }

          // Channel should be lazy without actual connection until first call
          // So a coarse grain lock is ok here
          NettyChannelBuilder builder =
              NettyChannelBuilder.forAddress(mappedAddr.getHost(), mappedAddr.getPort())
                  .maxInboundMessageSize(maxFrameSize)
                  .keepAliveTime(keepaliveTime, TimeUnit.SECONDS)
                  .keepAliveTimeout(keepaliveTimeout, TimeUnit.SECONDS)
                  .keepAliveWithoutCalls(true)
                  .idleTimeout(idleTimeout, TimeUnit.SECONDS);

          if (certContext == null) {
            logger.info("establish connection without tls");
            return builder.usePlaintext().build();
          } else {
            SslContext sslContext;
            try {
              SslContextBuilder sslBuilder = null;
              final String threadName = Thread.currentThread().getName();
              logger.info("establish connection with tls in thread {}", threadName);
              while ((sslBuilder = sslContextBuilder.get()) == null) {
                logger.info("ssl builder is null in thread {}, keep retrying", threadName);
                try {
                  Thread.sleep(100);
                } catch (InterruptedException e) {
                  logger.error("interrupted while waiting for ssl builder", e);
                  throw new RuntimeException(e);
                }
              }
              logger.info("establish connection with tls in thread {}", threadName);
              sslContext = sslContextBuilder.get().build();
            } catch (SSLException e) {
              logger.error("create ssl context failed!", e);
              return null;
            }
            return builder.sslContext(sslContext).build();
          }
        });
  }

  public void close() {
    for (ManagedChannel ch : connPool.values()) {
      ch.shutdown();
    }
    connPool.clear();
  }
}
