/*
 * Copyright 2018 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.cloud.bigquery.connector.common;

import static com.google.cloud.bigquery.connector.common.BigQueryErrorCode.UNSUPPORTED;
import static java.lang.String.format;

import com.google.cloud.bigquery.TableDefinition;
import com.google.cloud.bigquery.TableId;
import com.google.cloud.bigquery.TableInfo;
import com.google.cloud.bigquery.storage.v1.ArrowSerializationOptions;
import com.google.cloud.bigquery.storage.v1.BigQueryReadClient;
import com.google.cloud.bigquery.storage.v1.CreateReadSessionRequest;
import com.google.cloud.bigquery.storage.v1.ReadSession;
import com.google.cloud.bigquery.storage.v1.ReadSession.TableModifiers;
import com.google.cloud.bigquery.storage.v1.ReadSession.TableReadOptions;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.ImmutableList;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.protobuf.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// A helper class, also handles view materialization
public class ReadSessionCreator {

  public static final int DEFAULT_MAX_PARALLELISM = 20_000;
  public static final int MINIMAL_PARALLELISM = 1;
  public static final int DEFAULT_MIN_PARALLELISM_FACTOR = 3;

  private static final Logger log = LoggerFactory.getLogger(ReadSessionCreator.class);
  private static boolean initialized = false;
  private static Cache<CreateReadSessionRequest, ReadSession> READ_SESSION_CACHE;

  private final ReadSessionCreatorConfig config;
  private final BigQueryClient bigQueryClient;
  private final BigQueryClientFactory bigQueryReadClientFactory;

  private static synchronized void initializeCache(long readSessionCacheDurationMins) {
    if (!initialized) {
      READ_SESSION_CACHE =
          CacheBuilder.newBuilder()
              .expireAfterWrite(readSessionCacheDurationMins, TimeUnit.MINUTES)
              .maximumSize(1000)
              .build();
      initialized = true;
    }
  }

  public ReadSessionCreator(
      ReadSessionCreatorConfig config,
      BigQueryClient bigQueryClient,
      BigQueryClientFactory bigQueryReadClientFactory) {
    this.config = config;
    this.bigQueryClient = bigQueryClient;
    this.bigQueryReadClientFactory = bigQueryReadClientFactory;
    initializeCache(config.getReadSessionCacheDurationMins());
  }

  /**
   * Creates a new ReadSession for parallel reads.
   *
   * <p>Some attributes are governed by the {@link ReadSessionCreatorConfig} that this object was
   * constructed with.
   *
   * @param table The table to create the session for.
   * @param selectedFields Projection : the fields (e.g. columns) to return
   * @param filter Selection: how to filter rows that match this filter
   * @return ReadSessionResponse
   */
  public ReadSessionResponse create(
      TableId table, ImmutableList<String> selectedFields, Optional<String> filter) {
    Instant sessionPrepStartTime = Instant.now();
    TableInfo tableDetails = bigQueryClient.getTable(table);
    TableInfo actualTable = getActualTable(tableDetails, selectedFields, filter);

    BigQueryReadClient bigQueryReadClient = bigQueryReadClientFactory.getBigQueryReadClient();
    log.info(
        "|creation a read session for table {}, parameters: "
            + "|selectedFields=[{}],"
            + "|filter=[{}]"
            + "|snapshotTimeMillis[{}]",
        actualTable.getFriendlyName(),
        String.join(",", selectedFields),
        filter.orElse("None"),
        config.getSnapshotTimeMillis().isPresent()
            ? String.valueOf(config.getSnapshotTimeMillis().getAsLong())
            : "None");

    String tablePath = toTablePath(actualTable.getTableId());
    CreateReadSessionRequest request =
        config
            .getRequestEncodedBase()
            .map(
                value -> {
                  try {
                    return com.google.cloud.bigquery.storage.v1.CreateReadSessionRequest.parseFrom(
                        java.util.Base64.getDecoder().decode(value));
                  } catch (com.google.protobuf.InvalidProtocolBufferException e) {
                    throw new RuntimeException("Couldn't decode:" + value, e);
                  }
                })
            .orElse(CreateReadSessionRequest.newBuilder().build());
    ReadSession.Builder requestedSession = request.getReadSession().toBuilder();
    config.getTraceId().ifPresent(traceId -> requestedSession.setTraceId(traceId));

    TableReadOptions.Builder readOptions = requestedSession.getReadOptionsBuilder();
    if (!isInputTableAView(tableDetails)) {
      filter.ifPresent(readOptions::setRowRestriction);
    }
    readOptions.addAllSelectedFields(selectedFields);
    readOptions.setArrowSerializationOptions(
        ArrowSerializationOptions.newBuilder()
            .setBufferCompression(config.getArrowCompressionCodec())
            .build());
    readOptions.setResponseCompressionCodec(config.getResponseCompressionCodec());

    int preferredMinStreamCount =
        config
            .getPreferredMinParallelism()
            .orElseGet(
                () -> {
                  int defaultPreferredMinStreamCount =
                      Math.max(
                          MINIMAL_PARALLELISM,
                          DEFAULT_MIN_PARALLELISM_FACTOR * config.getDefaultParallelism());
                  log.debug(
                      "using default preferred min parallelism [{}]",
                      defaultPreferredMinStreamCount);
                  return defaultPreferredMinStreamCount;
                });

    int maxStreamCount =
        config
            .getMaxParallelism()
            .orElseGet(
                () -> {
                  int defaultMaxStreamCount =
                      Math.max(ReadSessionCreator.DEFAULT_MAX_PARALLELISM, preferredMinStreamCount);
                  log.debug("using default max parallelism [{}]", defaultMaxStreamCount);
                  return defaultMaxStreamCount;
                });
    int minStreamCount = preferredMinStreamCount;
    if (minStreamCount > maxStreamCount) {
      minStreamCount = maxStreamCount;
      log.warn(
          "preferred min parallelism is larger than the max parallelism, therefore setting it to"
              + " max parallelism [{}]",
          minStreamCount);
    }
    Instant sessionPrepEndTime = Instant.now();

    TableModifiers.Builder modifiers = TableModifiers.newBuilder();

    if (!isInputTableAView(tableDetails)) {
      config
          .getSnapshotTimeMillis()
          .ifPresent(
              millis -> {
                Instant snapshotTime = Instant.ofEpochMilli(millis);
                modifiers.setSnapshotTime(
                    Timestamp.newBuilder()
                        .setSeconds(snapshotTime.getEpochSecond())
                        .setNanos(snapshotTime.getNano())
                        .build());
              });
    }

    CreateReadSessionRequest createReadSessionRequest =
        request
            .newBuilder()
            .setParent("projects/" + bigQueryClient.getProjectId())
            .setReadSession(
                requestedSession
                    .setDataFormat(config.getReadDataFormat())
                    .setReadOptions(readOptions)
                    .setTableModifiers(modifiers)
                    .setTable(tablePath)
                    .build())
            .setMaxStreamCount(maxStreamCount)
            .setPreferredMinStreamCount(minStreamCount)
            .build();
    if (config.isReadSessionCachingEnabled()
        && getReadSessionCache().asMap().containsKey(createReadSessionRequest)) {
      ReadSession readSession = getReadSessionCache().asMap().get(createReadSessionRequest);
      log.info("Reusing read session: {}, for table: {}", readSession.getName(), table);
      return new ReadSessionResponse(readSession, actualTable);
    }
    ReadSession readSession = bigQueryReadClient.createReadSession(createReadSessionRequest);

    if (readSession != null) {
      Instant sessionCreationEndTime = Instant.now();
      if (config.isReadSessionCachingEnabled()) {
        getReadSessionCache().put(createReadSessionRequest, readSession);
      }
      JsonObject jsonObject = new JsonObject();
      jsonObject.addProperty("readSessionName", readSession.getName());
      jsonObject.addProperty("readSessionCreationStartTime", sessionPrepStartTime.toString());
      jsonObject.addProperty("readSessionCreationEndTime", sessionCreationEndTime.toString());
      jsonObject.addProperty(
          "readSessionPrepDuration",
          Duration.between(sessionPrepStartTime, sessionPrepEndTime).toMillis());
      jsonObject.addProperty(
          "readSessionCreationDuration",
          Duration.between(sessionPrepEndTime, sessionCreationEndTime).toMillis());
      jsonObject.addProperty(
          "readSessionDuration",
          Duration.between(sessionPrepStartTime, sessionCreationEndTime).toMillis());
      log.info("Read session:{}", new Gson().toJson(jsonObject));
      log.info(
          "Received {} partitions from the BigQuery Storage API for"
              + " session {}. Notice that the number of streams in actual may be lower than the"
              + " requested number, depending on the amount parallelism that is reasonable for"
              + " the table and the maximum amount of parallelism allowed by the system.",
          readSession.getStreamsCount(),
          readSession.getName());
    }

    return new ReadSessionResponse(readSession, actualTable);
  }

  static String toTablePath(TableId tableId) {
    return format(
        "projects/%s/datasets/%s/tables/%s",
        tableId.getProject(), tableId.getDataset(), tableId.getTable());
  }

  public TableInfo getActualTable(
      TableInfo table, ImmutableList<String> requiredColumns, Optional<String> filter) {
    String[] filters = filter.map(Stream::of).orElseGet(Stream::empty).toArray(String[]::new);
    return getActualTable(table, requiredColumns, filters);
  }

  TableInfo getActualTable(
      TableInfo table, ImmutableList<String> requiredColumns, String[] filters) {
    TableDefinition tableDefinition = table.getDefinition();
    TableDefinition.Type tableType = tableDefinition.getType();
    if (TableDefinition.Type.TABLE == tableType
        || TableDefinition.Type.EXTERNAL == tableType
        || TableDefinition.Type.SNAPSHOT == tableType) {
      return table;
    }
    if (isInputTableAView(table)) {
      // get it from the view
      String querySql =
          bigQueryClient.createSql(
              table.getTableId(), requiredColumns, filters, config.getSnapshotTimeMillis());
      log.debug("querySql is {}", querySql);
      return bigQueryClient.materializeViewToTable(querySql, table.getTableId());
    } else {
      // not regular table or a view
      throw new BigQueryConnectorException(
          UNSUPPORTED,
          format(
              "Table type '%s' of table '%s.%s' is not supported",
              tableType, table.getTableId().getDataset(), table.getTableId().getTable()));
    }
  }

  public boolean isInputTableAView(TableInfo table) {
    TableDefinition tableDefinition = table.getDefinition();
    TableDefinition.Type tableType = tableDefinition.getType();

    if (TableDefinition.Type.VIEW == tableType
        || TableDefinition.Type.MATERIALIZED_VIEW == tableType) {
      if (!config.isViewsEnabled()) {
        throw new BigQueryConnectorException(
            UNSUPPORTED,
            format(
                "Views are not enabled. You can enable views by setting '%s' to true. Notice"
                    + " additional cost may occur.",
                config.getViewEnabledParamName()));
      }
      return true;
    }
    return false;
  }

  // visible for testing
  Cache<CreateReadSessionRequest, ReadSession> getReadSessionCache() {
    return READ_SESSION_CACHE;
  }
}
