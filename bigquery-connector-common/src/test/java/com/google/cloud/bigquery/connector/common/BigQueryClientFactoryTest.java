/*
 * Copyright 2022 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.cloud.bigquery.connector.common;

import static com.google.common.truth.Truth.*;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import com.google.api.gax.retrying.RetrySettings;
import com.google.api.gax.rpc.FixedHeaderProvider;
import com.google.api.gax.rpc.HeaderProvider;
import com.google.auth.Credentials;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ImpersonatedCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.cloud.bigquery.QueryJobConfiguration.Priority;
import com.google.cloud.bigquery.storage.v1.BigQueryReadClient;
import com.google.cloud.bigquery.storage.v1.BigQueryWriteClient;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.URI;
import java.security.PrivateKey;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.Test;
import org.mockito.Answers;

public class BigQueryClientFactoryTest {
  private static final String CLIENT_EMAIL =
      "36680232662-vrd7ji19qe3nelgchd0ah2csanun6bnr@developer.gserviceaccount.com";
  private static final String PRIVATE_KEY_ID = "d84a4fefcf50791d4a90f2d7af17469d6282df9d";
  private static final Collection<String> SCOPES = Collections.singletonList("dummy.scope");
  private static final String USER = "user@example.com";
  private static final String PROJECT_ID = "project-id";

  private final PrivateKey privateKey = mock(PrivateKey.class);
  private final BigQueryCredentialsSupplier bigQueryCredentialsSupplier =
      mock(BigQueryCredentialsSupplier.class);
  private final BigQueryConfig bigQueryConfig = mock(BigQueryConfig.class);
  // initialized in the constructor due dependency on bigQueryConfig
  private final HeaderProvider headerProvider;
  private final BigQueryProxyConfig bigQueryProxyConfig =
      new BigQueryProxyConfig() {
        @Override
        public Optional<URI> getProxyUri() {
          return Optional.empty();
        }

        @Override
        public Optional<String> getProxyUsername() {
          return Optional.empty();
        }

        @Override
        public Optional<String> getProxyPassword() {
          return Optional.empty();
        }
      };

  public BigQueryClientFactoryTest() {
    when(bigQueryConfig.useParentProjectForMetadataOperations()).thenReturn(false);
    this.headerProvider = HttpUtil.createHeaderProvider(bigQueryConfig, "test-agent");
  }

  @Test
  public void testGetReadClientForSameClientFactory() {
    BigQueryClientFactory clientFactory =
        new BigQueryClientFactory(bigQueryCredentialsSupplier, headerProvider, bigQueryConfig);

    when(bigQueryConfig.getBigQueryProxyConfig()).thenReturn(bigQueryProxyConfig);
    when(bigQueryConfig.getChannelPoolSize()).thenReturn(1);

    BigQueryReadClient readClient = clientFactory.getBigQueryReadClient();
    assertNotNull(readClient);

    BigQueryReadClient readClient2 = clientFactory.getBigQueryReadClient();
    assertNotNull(readClient2);

    assertSame(readClient, readClient2);
  }

  @Test
  public void testGetReadClientWithUserAgent() {
    BigQueryClientFactory clientFactory =
        new BigQueryClientFactory(bigQueryCredentialsSupplier, headerProvider, bigQueryConfig);

    when(bigQueryConfig.getBigQueryProxyConfig()).thenReturn(bigQueryProxyConfig);
    when(bigQueryConfig.getClientCreationHashCode()).thenReturn(1234);
    when(bigQueryConfig.areClientCreationConfigsEqual(bigQueryConfig)).thenReturn(true);
    when(bigQueryConfig.getChannelPoolSize()).thenReturn(1);

    BigQueryReadClient readClient = clientFactory.getBigQueryReadClient();
    assertNotNull(readClient);

    BigQueryClientFactory clientFactory2 =
        new BigQueryClientFactory(bigQueryCredentialsSupplier, headerProvider, bigQueryConfig);

    when(bigQueryConfig.getBigQueryProxyConfig()).thenReturn(bigQueryProxyConfig);
    when(bigQueryConfig.getClientCreationHashCode()).thenReturn(1234);
    when(bigQueryConfig.areClientCreationConfigsEqual(bigQueryConfig)).thenReturn(true);
    when(bigQueryConfig.getChannelPoolSize()).thenReturn(1);

    BigQueryReadClient readClient2 = clientFactory2.getBigQueryReadClient();
    assertNotNull(readClient2);

    assertSame(readClient, readClient2);

    BigQueryClientFactory clientFactory3 =
        new BigQueryClientFactory(
            bigQueryCredentialsSupplier,
            HttpUtil.createHeaderProvider(bigQueryConfig, "test-agent-2"),
            bigQueryConfig);

    when(bigQueryConfig.getBigQueryProxyConfig()).thenReturn(bigQueryProxyConfig);
    when(bigQueryConfig.getClientCreationHashCode()).thenReturn(1234);
    when(bigQueryConfig.areClientCreationConfigsEqual(bigQueryConfig)).thenReturn(true);
    when(bigQueryConfig.getChannelPoolSize()).thenReturn(1);

    BigQueryReadClient readClient3 = clientFactory3.getBigQueryReadClient();
    assertNotNull(readClient3);

    assertNotSame(readClient, readClient3);
    assertNotSame(readClient2, readClient3);
  }

  @Test
  public void testGetReadClientWithBigQueryConfig() {
    BigQueryClientFactory clientFactory =
        new BigQueryClientFactory(
            bigQueryCredentialsSupplier,
            headerProvider,
            new TestBigQueryConfig(Optional.of("US:8080")));

    BigQueryReadClient readClient = clientFactory.getBigQueryReadClient();
    assertNotNull(readClient);

    BigQueryClientFactory clientFactory2 =
        new BigQueryClientFactory(
            bigQueryCredentialsSupplier,
            headerProvider,
            new TestBigQueryConfig(Optional.of("US:8080")));

    BigQueryReadClient readClient2 = clientFactory2.getBigQueryReadClient();
    assertNotNull(readClient2);

    assertSame(readClient, readClient2);

    BigQueryClientFactory clientFactory3 =
        new BigQueryClientFactory(
            bigQueryCredentialsSupplier,
            headerProvider,
            new TestBigQueryConfig(Optional.of("EU:8080")));

    BigQueryReadClient readClient3 = clientFactory3.getBigQueryReadClient();
    assertNotNull(readClient3);

    assertNotSame(readClient, readClient3);
    assertNotSame(readClient2, readClient3);
  }

  @Test
  public void testGetReadClientWithServiceAccountCredentials() {
    when(bigQueryCredentialsSupplier.getCredentials())
        .thenReturn(createServiceAccountCredentials("test-client-id"));
    BigQueryClientFactory clientFactory =
        new BigQueryClientFactory(bigQueryCredentialsSupplier, headerProvider, bigQueryConfig);

    when(bigQueryConfig.getBigQueryProxyConfig()).thenReturn(bigQueryProxyConfig);
    when(bigQueryConfig.getClientCreationHashCode()).thenReturn(1234);
    when(bigQueryConfig.areClientCreationConfigsEqual(bigQueryConfig)).thenReturn(true);
    when(bigQueryConfig.getChannelPoolSize()).thenReturn(1);

    BigQueryReadClient readClient = clientFactory.getBigQueryReadClient();
    assertNotNull(readClient);

    when(bigQueryCredentialsSupplier.getCredentials())
        .thenReturn(createServiceAccountCredentials("test-client-id"));
    BigQueryClientFactory clientFactory2 =
        new BigQueryClientFactory(bigQueryCredentialsSupplier, headerProvider, bigQueryConfig);

    when(bigQueryConfig.getBigQueryProxyConfig()).thenReturn(bigQueryProxyConfig);
    when(bigQueryConfig.getClientCreationHashCode()).thenReturn(1234);
    when(bigQueryConfig.areClientCreationConfigsEqual(bigQueryConfig)).thenReturn(true);
    when(bigQueryConfig.getChannelPoolSize()).thenReturn(1);

    BigQueryReadClient readClient2 = clientFactory2.getBigQueryReadClient();
    assertNotNull(readClient2);

    assertSame(readClient, readClient2);

    when(bigQueryCredentialsSupplier.getCredentials())
        .thenReturn(createServiceAccountCredentials("test-client-id-2"));
    BigQueryClientFactory clientFactory3 =
        new BigQueryClientFactory(bigQueryCredentialsSupplier, headerProvider, bigQueryConfig);

    when(bigQueryConfig.getBigQueryProxyConfig()).thenReturn(bigQueryProxyConfig);
    when(bigQueryConfig.getClientCreationHashCode()).thenReturn(1234);
    when(bigQueryConfig.areClientCreationConfigsEqual(bigQueryConfig)).thenReturn(true);
    when(bigQueryConfig.getChannelPoolSize()).thenReturn(1);

    BigQueryReadClient readClient3 = clientFactory3.getBigQueryReadClient();
    assertNotNull(readClient3);

    assertNotSame(readClient, readClient3);
    assertNotSame(readClient2, readClient3);
  }

  @Test
  public void testGetWriteClientForSameClientFactory() {
    BigQueryClientFactory clientFactory =
        new BigQueryClientFactory(bigQueryCredentialsSupplier, headerProvider, bigQueryConfig);

    when(bigQueryConfig.getBigQueryProxyConfig()).thenReturn(bigQueryProxyConfig);

    BigQueryWriteClient writeClient = clientFactory.getBigQueryWriteClient();
    assertNotNull(writeClient);

    BigQueryWriteClient writeClient2 = clientFactory.getBigQueryWriteClient();
    assertNotNull(writeClient2);

    assertSame(writeClient, writeClient2);
  }

  @Test
  public void testGetWriteClientWithUserAgent() {
    BigQueryClientFactory clientFactory =
        new BigQueryClientFactory(bigQueryCredentialsSupplier, headerProvider, bigQueryConfig);

    when(bigQueryConfig.getBigQueryProxyConfig()).thenReturn(bigQueryProxyConfig);
    when(bigQueryConfig.getClientCreationHashCode()).thenReturn(1234);
    when(bigQueryConfig.areClientCreationConfigsEqual(bigQueryConfig)).thenReturn(true);

    BigQueryWriteClient writeClient = clientFactory.getBigQueryWriteClient();
    assertNotNull(writeClient);

    BigQueryClientFactory clientFactory2 =
        new BigQueryClientFactory(bigQueryCredentialsSupplier, headerProvider, bigQueryConfig);

    when(bigQueryConfig.getBigQueryProxyConfig()).thenReturn(bigQueryProxyConfig);
    when(bigQueryConfig.getClientCreationHashCode()).thenReturn(1234);
    when(bigQueryConfig.areClientCreationConfigsEqual(bigQueryConfig)).thenReturn(true);

    BigQueryWriteClient writeClient2 = clientFactory2.getBigQueryWriteClient();
    assertNotNull(writeClient2);

    assertSame(writeClient, writeClient2);

    BigQueryClientFactory clientFactory3 =
        new BigQueryClientFactory(
            bigQueryCredentialsSupplier,
            HttpUtil.createHeaderProvider(bigQueryConfig, "test-agent-2"),
            bigQueryConfig);

    when(bigQueryConfig.getBigQueryProxyConfig()).thenReturn(bigQueryProxyConfig);
    when(bigQueryConfig.getClientCreationHashCode()).thenReturn(1234);
    when(bigQueryConfig.areClientCreationConfigsEqual(bigQueryConfig)).thenReturn(true);

    BigQueryWriteClient writeClient3 = clientFactory3.getBigQueryWriteClient();
    assertNotNull(writeClient3);

    assertNotSame(writeClient, writeClient3);
    assertNotSame(writeClient2, writeClient3);
  }

  @Test
  public void testGetWriteClientWithBigQueryConfig() {
    BigQueryClientFactory clientFactory =
        new BigQueryClientFactory(
            bigQueryCredentialsSupplier,
            headerProvider,
            new TestBigQueryConfig(Optional.of("US:8080")));

    BigQueryWriteClient writeClient = clientFactory.getBigQueryWriteClient();
    assertNotNull(writeClient);

    BigQueryClientFactory clientFactory2 =
        new BigQueryClientFactory(
            bigQueryCredentialsSupplier,
            headerProvider,
            new TestBigQueryConfig(Optional.of("US:8080")));

    BigQueryWriteClient writeClient2 = clientFactory2.getBigQueryWriteClient();
    assertNotNull(writeClient2);

    assertSame(writeClient, writeClient2);

    BigQueryClientFactory clientFactory3 =
        new BigQueryClientFactory(
            bigQueryCredentialsSupplier,
            headerProvider,
            new TestBigQueryConfig(Optional.of("EU:8080")));

    BigQueryWriteClient writeClient3 = clientFactory3.getBigQueryWriteClient();
    assertNotNull(writeClient3);

    assertNotSame(writeClient, writeClient3);
    assertNotSame(writeClient2, writeClient3);
  }

  @Test
  public void testGetWriteClientWithServiceAccountCredentials() throws Exception {
    when(bigQueryCredentialsSupplier.getCredentials())
        .thenReturn(createServiceAccountCredentials("test-client-id"));
    BigQueryClientFactory clientFactory =
        new BigQueryClientFactory(bigQueryCredentialsSupplier, headerProvider, bigQueryConfig);

    when(bigQueryConfig.getBigQueryProxyConfig()).thenReturn(bigQueryProxyConfig);
    when(bigQueryConfig.getClientCreationHashCode()).thenReturn(1234);
    when(bigQueryConfig.areClientCreationConfigsEqual(bigQueryConfig)).thenReturn(true);

    BigQueryWriteClient writeClient = clientFactory.getBigQueryWriteClient();
    assertNotNull(writeClient);

    when(bigQueryCredentialsSupplier.getCredentials())
        .thenReturn(createServiceAccountCredentials("test-client-id"));
    BigQueryClientFactory clientFactory2 =
        new BigQueryClientFactory(bigQueryCredentialsSupplier, headerProvider, bigQueryConfig);

    when(bigQueryConfig.getBigQueryProxyConfig()).thenReturn(bigQueryProxyConfig);
    when(bigQueryConfig.getClientCreationHashCode()).thenReturn(1234);
    when(bigQueryConfig.areClientCreationConfigsEqual(bigQueryConfig)).thenReturn(true);

    BigQueryWriteClient writeClient2 = clientFactory2.getBigQueryWriteClient();
    assertNotNull(writeClient2);

    assertSame(writeClient, writeClient2);

    when(bigQueryCredentialsSupplier.getCredentials())
        .thenReturn(createServiceAccountCredentials("test-client-id-2"));
    BigQueryClientFactory clientFactory3 =
        new BigQueryClientFactory(bigQueryCredentialsSupplier, headerProvider, bigQueryConfig);

    when(bigQueryConfig.getBigQueryProxyConfig()).thenReturn(bigQueryProxyConfig);
    when(bigQueryConfig.getClientCreationHashCode()).thenReturn(1234);
    when(bigQueryConfig.areClientCreationConfigsEqual(bigQueryConfig)).thenReturn(true);

    BigQueryWriteClient writeClient3 = clientFactory3.getBigQueryWriteClient();
    assertNotNull(writeClient3);

    assertNotSame(writeClient, writeClient3);
    assertNotSame(writeClient2, writeClient3);
  }

  @Test
  public void testGetReadClientWithSameAndDifferentBQConfig() {
    BigQueryClientFactory clientFactory =
        new BigQueryClientFactory(bigQueryCredentialsSupplier, headerProvider, bigQueryConfig);

    BigQueryClientFactory clientFactory2 =
        new BigQueryClientFactory(
            bigQueryCredentialsSupplier,
            headerProvider,
            new TestBigQueryConfig(Optional.of("EU:8080")));

    BigQueryClientFactory clientFactory3 =
        new BigQueryClientFactory(
            bigQueryCredentialsSupplier,
            headerProvider,
            new TestBigQueryConfig(Optional.of("EU:8080")));

    when(bigQueryConfig.getBigQueryProxyConfig()).thenReturn(bigQueryProxyConfig);
    when(bigQueryConfig.getChannelPoolSize()).thenReturn(1);

    BigQueryReadClient readClient = clientFactory.getBigQueryReadClient();
    assertNotNull(readClient);

    BigQueryReadClient readClient2 = clientFactory2.getBigQueryReadClient();
    assertNotNull(readClient2);

    BigQueryReadClient readClient3 = clientFactory3.getBigQueryReadClient();
    assertNotNull(readClient3);

    assertNotSame(readClient, readClient2);
    assertNotSame(readClient, readClient3);
    assertSame(readClient2, readClient3);
  }

  @Test
  public void testGetWriteClientWithSameAndDifferentBQConfig() {
    BigQueryClientFactory clientFactory =
        new BigQueryClientFactory(bigQueryCredentialsSupplier, headerProvider, bigQueryConfig);

    BigQueryClientFactory clientFactory2 =
        new BigQueryClientFactory(
            bigQueryCredentialsSupplier,
            headerProvider,
            new TestBigQueryConfig(Optional.of("EU:8080")));

    BigQueryClientFactory clientFactory3 =
        new BigQueryClientFactory(
            bigQueryCredentialsSupplier,
            headerProvider,
            new TestBigQueryConfig(Optional.of("EU:8080")));

    when(bigQueryConfig.getBigQueryProxyConfig()).thenReturn(bigQueryProxyConfig);

    BigQueryWriteClient writeClient = clientFactory.getBigQueryWriteClient();
    assertNotNull(writeClient);

    BigQueryWriteClient writeClient2 = clientFactory2.getBigQueryWriteClient();
    assertNotNull(writeClient2);

    BigQueryWriteClient writeClient3 = clientFactory3.getBigQueryWriteClient();
    assertNotNull(writeClient3);

    assertNotSame(writeClient, writeClient2);
    assertNotSame(writeClient, writeClient3);
    assertSame(writeClient2, writeClient3);
  }

  @Test
  public void testHashCodeWithExternalAccountCredentials() throws Exception {
    // Credentials taken from https://google.aip.dev/auth/4117:
    Credentials credentials =
        GoogleCredentials.fromStream(
            getClass().getResourceAsStream("/external-account-credentials.json"));

    when(bigQueryCredentialsSupplier.getCredentials()).thenReturn(credentials);

    BigQueryClientFactory factory =
        new BigQueryClientFactory(
            bigQueryCredentialsSupplier,
            FixedHeaderProvider.create("foo", "bar"),
            new TestBigQueryConfig(Optional.empty()));

    int hashCode1 = factory.hashCode();
    int hashCode2 = factory.hashCode();
    assertThat(hashCode2).isEqualTo(hashCode1);
  }

  @Test
  public void testGetCredentials_ImpersonatedCredentials_calendarNotNullAfterDeserialization()
      throws Exception {

    // 1. Mock dependencies and make them serializable
    HeaderProvider headerProvider = mock(HeaderProvider.class, withSettings().serializable());
    BigQueryConfig bqConfig =
        mock(
            BigQueryConfig.class,
            withSettings().serializable().defaultAnswer(Answers.RETURNS_DEEP_STUBS));
    BigQueryCredentialsSupplier credentialsSupplier =
        mock(BigQueryCredentialsSupplier.class, withSettings().serializable());

    // 2. Create source credentials for ImpersonatedCredentials (can be a simple serializable mock)
    GoogleCredentials sourceCredentials =
        mock(GoogleCredentials.class, withSettings().serializable());
    when(sourceCredentials.createScopedRequired()).thenReturn(false);

    // 3. Create original ImpersonatedCredentials
    ImpersonatedCredentials originalCredentials =
        ImpersonatedCredentials.newBuilder()
            .setSourceCredentials(sourceCredentials)
            .setTargetPrincipal("dummy-target@example.com") // Dummy principal is fine
            .setScopes(Collections.singletonList("https://www.googleapis.com/auth/cloud-platform"))
            .build();

    when(credentialsSupplier.getCredentials()).thenReturn(originalCredentials);

    // 4. Create and serialize BigQueryClientFactory
    BigQueryClientFactory factory =
        new BigQueryClientFactory(credentialsSupplier, headerProvider, bqConfig);

    byte[] serializedFactory;
    try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(baos)) {
      oos.writeObject(factory);
      serializedFactory = baos.toByteArray();
    }

    // 5. Deserialize BigQueryClientFactory
    BigQueryClientFactory deserializedFactory;
    try (ByteArrayInputStream bais = new ByteArrayInputStream(serializedFactory);
        ObjectInputStream ois = new ObjectInputStream(bais)) {
      deserializedFactory = (BigQueryClientFactory) ois.readObject();
    }

    // 6. Get credentials from deserialized factory
    Credentials credentialsFromFactory = deserializedFactory.getCredentials();

    // 7. Assertions using Google Truth
    assertThat(credentialsFromFactory).isInstanceOf(ImpersonatedCredentials.class);

    ImpersonatedCredentials deserializedImpersonatedCredentials =
        (ImpersonatedCredentials) credentialsFromFactory;

    // The core assertion: verify the calendar is not null after deserialization
    // and the factory's getCredentials() logic has run.
    // Accessing the calendar field via reflection as it's not public
    java.lang.reflect.Field calendarField =
        ImpersonatedCredentials.class.getDeclaredField("calendar");
    calendarField.setAccessible(true);
    Calendar calendar = (Calendar) calendarField.get(deserializedImpersonatedCredentials);
    assertThat(calendar).isNotNull();
  }

  private ServiceAccountCredentials createServiceAccountCredentials(String clientId) {
    return ServiceAccountCredentials.newBuilder()
        .setClientId(clientId)
        .setClientEmail(CLIENT_EMAIL)
        .setPrivateKey(privateKey)
        .setPrivateKeyId(PRIVATE_KEY_ID)
        .setScopes(SCOPES)
        .setServiceAccountUser(USER)
        .setProjectId(PROJECT_ID)
        .build();
  }

  private class TestBigQueryConfig implements BigQueryConfig {

    private final Optional<String> bigQueryStorageGrpcEndpoint;

    TestBigQueryConfig(Optional<String> bigQueryStorageGrpcEndpoint) {
      this.bigQueryStorageGrpcEndpoint = bigQueryStorageGrpcEndpoint;
    }

    @Override
    public Optional<String> getAccessTokenProviderFQCN() {
      return Optional.empty();
    }

    @Override
    public Optional<String> getAccessTokenProviderConfig() {
      return Optional.empty();
    }

    @Override
    public Optional<String> getCredentialsKey() {
      return Optional.empty();
    }

    @Override
    public Optional<String> getCredentialsFile() {
      return Optional.empty();
    }

    @Override
    public String getLoggedInUserName() {
      return null;
    }

    @Override
    public Set<String> getLoggedInUserGroups() {
      return null;
    }

    @Override
    public Optional<Map<String, String>> getImpersonationServiceAccountsForUsers() {
      return Optional.empty();
    }

    @Override
    public Optional<Map<String, String>> getImpersonationServiceAccountsForGroups() {
      return Optional.empty();
    }

    @Override
    public Optional<String> getImpersonationServiceAccount() {
      return Optional.empty();
    }

    @Override
    public Optional<String> getAccessToken() {
      return Optional.empty();
    }

    @Override
    public String getParentProjectId() {
      return null;
    }

    @Override
    public boolean useParentProjectForMetadataOperations() {
      return false;
    }

    @Override
    public boolean isViewsEnabled() {
      return false;
    }

    @Override
    public int getBigQueryClientConnectTimeout() {
      return 0;
    }

    @Override
    public int getBigQueryClientReadTimeout() {
      return 0;
    }

    @Override
    public RetrySettings getBigQueryClientRetrySettings() {
      return null;
    }

    @Override
    public BigQueryProxyConfig getBigQueryProxyConfig() {
      return bigQueryProxyConfig;
    }

    @Override
    public Optional<String> getBigQueryStorageGrpcEndpoint() {
      return bigQueryStorageGrpcEndpoint;
    }

    @Override
    public Optional<String> getBigQueryHttpEndpoint() {
      return Optional.empty();
    }

    @Override
    public int getCacheExpirationTimeInMinutes() {
      return 0;
    }

    @Override
    public ImmutableMap<String, String> getBigQueryJobLabels() {
      return ImmutableMap.<String, String>of();
    }

    @Override
    public Optional<Long> getCreateReadSessionTimeoutInSeconds() {
      return Optional.empty();
    }

    @Override
    public int getChannelPoolSize() {
      return 1;
    }

    @Override
    public Optional<Integer> getFlowControlWindowBytes() {
      return Optional.of(2 << 20);
    }

    @Override
    public Priority getQueryJobPriority() {
      return Priority.INTERACTIVE;
    }

    @Override
    public long getBigQueryJobTimeoutInMinutes() {
      return 6 * 60;
    }

    @Override
    public Optional<ImmutableList<String>> getCredentialsScopes() {
      return Optional.empty();
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof TestBigQueryConfig)) {
        return false;
      }
      TestBigQueryConfig that = (TestBigQueryConfig) o;
      return Objects.equal(bigQueryStorageGrpcEndpoint, that.bigQueryStorageGrpcEndpoint);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(bigQueryStorageGrpcEndpoint);
    }
  }
}
