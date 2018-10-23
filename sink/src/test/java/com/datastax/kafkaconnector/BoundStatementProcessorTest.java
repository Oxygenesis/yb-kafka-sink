/*
 * Copyright DataStax, Inc.
 *
 *   This software is subject to the below license agreement.
 *   DataStax may make changes to the agreement from time to time,
 *   and will post the amended terms at
 *   https://www.datastax.com/terms/datastax-dse-bulk-utility-license-terms.
 */
package com.datastax.kafkaconnector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.datastax.kafkaconnector.record.RecordAndStatement;
import com.datastax.oss.driver.api.core.cql.BoundStatement;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.apache.kafka.connect.sink.SinkRecord;
import org.awaitility.Duration;
import org.junit.jupiter.api.Test;

class BoundStatementProcessorTest {
  @Test
  void should_categorize_statement_in_statement_group() {

    BoundStatement bs1 = mock(BoundStatement.class);
    ByteBuffer routingKey = ByteBuffer.wrap(new byte[] {1, 2, 3});
    when(bs1.getRoutingKey()).thenReturn(routingKey);

    SinkRecord record1 = new SinkRecord("mytopic", 0, null, null, null, "value", 1234L);
    RecordAndStatement recordAndStatement1 = new RecordAndStatement(record1, "ks.mytable", bs1);

    SinkRecord record2 = new SinkRecord("yourtopic", 0, null, null, null, "value", 1234L);
    RecordAndStatement recordAndStatement2 = new RecordAndStatement(record2, "ks.mytable", bs1);

    Map<String, Map<ByteBuffer, List<RecordAndStatement>>> statementGroups = new HashMap<>();

    // We don't care about the args to the constructor for this test.
    BoundStatementProcessor statementProcessor = new BoundStatementProcessor(null, null, null, 32);

    // Categorize the two statements. Although they refer to the same ks/table and have the
    // same routing key, they should be in different buckets.
    List<RecordAndStatement> result1 =
        statementProcessor.categorizeStatement(statementGroups, recordAndStatement1);
    List<RecordAndStatement> result2 =
        statementProcessor.categorizeStatement(statementGroups, recordAndStatement2);

    assertThat(result1.size()).isEqualTo(1);
    assertThat(result1.get(0)).isSameAs(recordAndStatement1);
    assertThat(statementGroups.size()).isEqualTo(2);
    assertThat(statementGroups.containsKey("mytopic.ks.mytable")).isTrue();
    Map<ByteBuffer, List<RecordAndStatement>> batchGroups =
        statementGroups.get("mytopic.ks.mytable");
    assertThat(batchGroups.size()).isEqualTo(1);
    assertThat(batchGroups.containsKey(routingKey)).isTrue();
    List<RecordAndStatement> batchGroup = batchGroups.get(routingKey);
    assertThat(batchGroup).isSameAs(result1);

    batchGroups = statementGroups.get("yourtopic.ks.mytable");
    assertThat(batchGroups.size()).isEqualTo(1);
    assertThat(batchGroups.containsKey(routingKey)).isTrue();
    batchGroup = batchGroups.get(routingKey);
    assertThat(batchGroup).isSameAs(result2);
  }

  @Test
  @SuppressWarnings("unchecked")
  void should_execute_one_statement_when_max_number_of_records_is_one() {
    // given
    DseSinkTask dseSinkTask = mock(DseSinkTask.class);
    BlockingQueue<RecordAndStatement> recordAndStatements = new LinkedBlockingQueue<>();
    BoundStatementProcessor statementProcessor =
        new BoundStatementProcessor(dseSinkTask, recordAndStatements, new LinkedList<>(), 1);

    AtomicInteger called = new AtomicInteger();
    Consumer mockConsumer = o -> called.incrementAndGet();
    // when
    Thread thread = new Thread(() -> statementProcessor.runLoop(mockConsumer));

    recordAndStatements.add(
        new RecordAndStatement(
            new SinkRecord("mytopic", 0, null, null, null, 5725368L, 1234L),
            "ks.tb",
            mock(BoundStatement.class)));

    thread.start();
    await().atMost(Duration.FIVE_SECONDS).until(() -> called.get() == 1);
  }

  @Test
  @SuppressWarnings("unchecked")
  void should_execute_one_statement_when_max_number_of_records_is_N() {
    // given
    int N = 6;
    DseSinkTask dseSinkTask = mock(DseSinkTask.class);
    BlockingQueue<RecordAndStatement> recordAndStatements = new LinkedBlockingQueue<>();
    BoundStatementProcessor statementProcessor =
        new BoundStatementProcessor(dseSinkTask, recordAndStatements, new LinkedList<>(), N);

    AtomicInteger called = new AtomicInteger();
    Consumer mockConsumer = o -> called.incrementAndGet();
    // when
    Thread thread = new Thread(() -> statementProcessor.runLoop(mockConsumer));

    for (int i = 0; i < N; i++) {
      recordAndStatements.add(
          new RecordAndStatement(
              new SinkRecord("mytopic", 0, null, null, null, 5725368L, 1234L),
              "ks.tb",
              mock(BoundStatement.class)));
    }

    thread.start();
    await().atMost(Duration.FIVE_SECONDS).until(() -> called.get() == 1);
  }

  @Test
  @SuppressWarnings("unchecked")
  void should_execute_one_statement_when_max_number_of_records_is_N_in_two_batches()
      throws InterruptedException {
    // given
    int N = 6;
    DseSinkTask dseSinkTask = mock(DseSinkTask.class);
    BlockingQueue<RecordAndStatement> recordAndStatements = new LinkedBlockingQueue<>();
    BoundStatementProcessor statementProcessor =
        new BoundStatementProcessor(dseSinkTask, recordAndStatements, new LinkedList<>(), N);

    AtomicInteger called = new AtomicInteger();
    Consumer mockConsumer = o -> called.incrementAndGet();
    CountDownLatch sendLatch = new CountDownLatch(1);
    // when
    Thread thread =
        new Thread(
            () -> {
              sendLatch.countDown();
              statementProcessor.runLoop(mockConsumer);
            });

    for (int i = 0; i < N - 1; i++) {
      recordAndStatements.add(
          new RecordAndStatement(
              new SinkRecord("mytopic", 0, null, null, null, 5725368L, 1234L),
              "ks.tb",
              mock(BoundStatement.class)));
    }

    thread.start();
    sendLatch.await();
    Thread.sleep(500);

    recordAndStatements.add(
        new RecordAndStatement(
            new SinkRecord("mytopic", 0, null, null, null, 5725368L, 1234L),
            "ks.tb",
            mock(BoundStatement.class)));
    await().atMost(Duration.FIVE_SECONDS).until(() -> called.get() == 1);
  }
}