/*
 * Copyright 2016 Couchbase, Inc.
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
 */

package com.couchbase.connect.kafka;

import com.couchbase.client.core.logging.LogRedaction;
import com.couchbase.client.dcp.core.logging.RedactionLevel;
import com.couchbase.connect.kafka.config.source.CouchbaseSourceTaskConfig;
import com.couchbase.connect.kafka.dcp.Event;
import com.couchbase.connect.kafka.filter.Filter;
import com.couchbase.connect.kafka.handler.source.CouchbaseSourceRecord;
import com.couchbase.connect.kafka.handler.source.DocumentEvent;
import com.couchbase.connect.kafka.handler.source.SourceHandler;
import com.couchbase.connect.kafka.handler.source.SourceHandlerParams;
import com.couchbase.connect.kafka.util.Version;
import com.couchbase.connect.kafka.util.config.ConfigHelper;
import org.apache.kafka.common.config.ConfigException;
import org.apache.kafka.common.utils.Utils;
import org.apache.kafka.connect.errors.ConnectException;
import org.apache.kafka.connect.source.SourceRecord;
import org.apache.kafka.connect.source.SourceTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class CouchbaseSourceTask extends SourceTask {
  private static final Logger LOGGER = LoggerFactory.getLogger(CouchbaseSourceTask.class);

  private static final long MAX_TIMEOUT = 10000L;

  private String connectorName;
  private CouchbaseReader couchbaseReader;
  private BlockingQueue<Event> queue;
  private BlockingQueue<Throwable> errorQueue;
  private String topic;
  private String bucket;
  private volatile boolean running;
  private Filter filter;
  private SourceHandler sourceHandler;
  private int batchSizeMax;
  private boolean connectorNameInOffsets;

  @Override
  public String version() {
    return Version.getVersion();
  }

  @Override
  public void start(Map<String, String> properties) {
    this.connectorName = properties.get("name");

    CouchbaseSourceTaskConfig config;
    try {
      config = ConfigHelper.parse(CouchbaseSourceTaskConfig.class, properties);
      if (connectorName == null || connectorName.isEmpty()) {
        throw new ConfigException("Connector must have a non-blank 'name' config property.");
      }
    } catch (ConfigException e) {
      throw new ConnectException("Couldn't start CouchbaseSourceTask due to configuration error", e);
    }

    LogRedaction.setRedactionLevel(config.logRedaction());
    RedactionLevel.set(toDcp(config.logRedaction()));

    filter = Utils.newInstance(config.eventFilter());
    sourceHandler = Utils.newInstance(config.sourceHandler());

    topic = config.topic();
    bucket = config.bucket();
    connectorNameInOffsets = config.connectorNameInOffsets();
    batchSizeMax = config.batchSizeMax();

    Short[] partitions = toBoxedShortArray(config.partitions());
    Map<Short, SeqnoAndVbucketUuid> partitionToSavedSeqno = readSourceOffsets(partitions);

    running = true;
    queue = new LinkedBlockingQueue<>();
    errorQueue = new LinkedBlockingQueue<>(1);
    couchbaseReader = new CouchbaseReader(config, connectorName, queue, errorQueue, partitions, partitionToSavedSeqno);
    couchbaseReader.start();
  }

  private RedactionLevel toDcp(com.couchbase.client.core.logging.RedactionLevel level) {
    switch (level) {
      case FULL:
        return RedactionLevel.FULL;
      case NONE:
        return RedactionLevel.NONE;
      case PARTIAL:
        return RedactionLevel.PARTIAL;
      default:
        throw new IllegalArgumentException("Unrecognized redaction level: " + level);
    }
  }

  @Override
  public List<SourceRecord> poll()
      throws InterruptedException {
    List<SourceRecord> results = new LinkedList<>();
    int batchSize = batchSizeMax;

    while (running) {
      Event event = queue.poll(100, TimeUnit.MILLISECONDS);
      if (event != null) {
        try {
          if (filter == null || filter.pass(event.message())) {
            SourceRecord record = convert(event);
            if (record != null) {
              results.add(record);
            }
          }

          event.ack();
          batchSize--;
        } finally {
          event.message().release();
        }
      }
      if (!results.isEmpty() &&
          (batchSize == 0 || event == null)) {
        LOGGER.info("Poll returns {} result(s)", results.size());
        return results;
      }

      final Throwable fatalError = errorQueue.poll();
      if (fatalError != null) {
        throw new ConnectException(fatalError);
      }
    }
    return results;
  }

  public SourceRecord convert(Event event) {
    final DocumentEvent docEvent = DocumentEvent.create(event.message(), bucket, event.vbucketUuid());

    CouchbaseSourceRecord r = sourceHandler.handle(new SourceHandlerParams(docEvent, topic));
    if (r == null) {
      return null;
    }

    return new SourceRecord(
        sourcePartition(docEvent.vBucket()),
        sourceOffset(docEvent),
        r.topic() == null ? topic : r.topic(),
        r.kafkaPartition(),
        r.keySchema(), r.key(),
        r.valueSchema(), r.value(),
        r.timestamp());
  }

  @Override
  public void stop() {
    running = false;
    if (couchbaseReader != null) {
      couchbaseReader.shutdown();
      try {
        couchbaseReader.join(MAX_TIMEOUT);
        if (couchbaseReader.isAlive()) {
          LOGGER.error("Reader thread is still alive after shutdown request.");
        }
      } catch (InterruptedException e) {
        LOGGER.error("Interrupted while joining reader thread.", e);
      }
    }

    if (queue != null) {
      LOGGER.info("Releasing unconsumed events: {}", queue.size());
      // Don't need to ACK, since DCP connection is already closed.
      releaseAll(queue);
    }
  }

  private void releaseAll(Iterable<Event> events) {
    RuntimeException deferredException = null;

    for (Event event : events) {
      try {
        event.message().release();
      } catch (RuntimeException t) {
        LOGGER.warn("Failed to release buffer {}", event, t);
        deferredException = t;
      }
    }
    if (deferredException != null) {
      throw deferredException;
    }
  }

  /**
   * Loads as many of the requested source offsets as possible.
   * See the caveats for {@link org.apache.kafka.connect.storage.OffsetStorageReader#offsets(Collection)}.
   *
   * @return a map of partitions to sequence numbers.
   */
  private Map<Short, SeqnoAndVbucketUuid> readSourceOffsets(Short[] partitions) {
    Map<Short, SeqnoAndVbucketUuid> partitionToSequenceNumber = new HashMap<>();

    Map<Map<String, Object>, Map<String, Object>> offsets = context.offsetStorageReader().offsets(
        sourcePartitions(partitions));

    LOGGER.debug("Raw source offsets: {}", offsets);

    for (Map.Entry<Map<String, Object>, Map<String, Object>> entry : offsets.entrySet()) {
      Map<String, Object> partitionIdentifier = entry.getKey();
      Map<String, Object> offset = entry.getValue();
      if (offset == null) {
        continue;
      }
      short partition = Short.parseShort((String) partitionIdentifier.get("partition"));
      long seqno = (Long) offset.get("bySeqno");
      Long vbuuid = (Long) offset.get("vbuuid"); // might be absent if upgrading from older version
      partitionToSequenceNumber.put(partition, new SeqnoAndVbucketUuid(seqno, vbuuid));
    }

    LOGGER.debug("Partition to saved seqno: {}", partitionToSequenceNumber);

    return partitionToSequenceNumber;
  }

  private List<Map<String, Object>> sourcePartitions(Short[] partitions) {
    List<Map<String, Object>> sourcePartitions = new ArrayList<>();
    for (Short partition : partitions) {
      sourcePartitions.add(sourcePartition(partition));
    }
    return sourcePartitions;
  }

  /**
   * Converts a Couchbase DCP partition (also known as a vBucket) into the Map format required by Kafka Connect.
   */
  private Map<String, Object> sourcePartition(short partition) {
    final Map<String, Object> sourcePartition = new HashMap<>(3);
    sourcePartition.put("bucket", bucket);
    sourcePartition.put("partition", String.valueOf(partition)); // Stringify for robust round-tripping across Kafka [de]serialization
    if (connectorNameInOffsets) {
      sourcePartition.put("connector", connectorName);
    }
    return sourcePartition;
  }

  /**
   * Converts a Couchbase DCP sequence number + vBucket UUID into the Map format required by Kafka Connect.
   */
  private static Map<String, Object> sourceOffset(DocumentEvent event) {
    Map<String, Object> offset = new HashMap<>();
    offset.put("bySeqno", event.bySeqno());
    offset.put("vbuuid", event.vBucketUuid());
    return offset;
  }

  private static Short[] toBoxedShortArray(Collection<String> stringifiedShorts) {
    return stringifiedShorts.stream()
        .map(Short::valueOf)
        .toArray(Short[]::new);
  }
}
