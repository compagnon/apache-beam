/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.sdk.io.jms;

import static org.apache.beam.vendor.guava.v26_0_jre.com.google.common.base.Preconditions.checkArgument;

import com.google.auto.value.AutoValue;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.Stream;
import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.Destination;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.MessageProducer;
import javax.jms.Session;
import javax.jms.TextMessage;
import org.apache.beam.sdk.annotations.Experimental;
import org.apache.beam.sdk.annotations.Experimental.Kind;
import org.apache.beam.sdk.coders.Coder;
import org.apache.beam.sdk.coders.SerializableCoder;
import org.apache.beam.sdk.io.Read.Unbounded;
import org.apache.beam.sdk.io.UnboundedSource;
import org.apache.beam.sdk.io.UnboundedSource.CheckpointMark;
import org.apache.beam.sdk.io.UnboundedSource.UnboundedReader;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.SerializableBiFunction;
import org.apache.beam.sdk.transforms.SerializableFunction;
import org.apache.beam.sdk.transforms.display.DisplayData;
import org.apache.beam.sdk.values.PBegin;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PCollectionTuple;
import org.apache.beam.sdk.values.TupleTag;
import org.apache.beam.sdk.values.TupleTagList;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.joda.time.Duration;
import org.joda.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An unbounded source for JMS destinations (queues or topics).
 *
 * <h3>Reading from a JMS destination</h3>
 *
 * <p>JmsIO source returns unbounded collection of JMS records as {@code PCollection<JmsRecord>}. A
 * {@link JmsRecord} includes JMS headers and properties, along with the JMS {@link
 * javax.jms.TextMessage} payload.
 *
 * <p>To configure a JMS source, you have to provide a {@link javax.jms.ConnectionFactory} and the
 * destination (queue or topic) where to consume. The following example illustrates various options
 * for configuring the source:
 *
 * <pre>{@code
 * pipeline.apply(JmsIO.read()
 *    .withConnectionFactory(myConnectionFactory)
 *    .withQueue("my-queue")
 *    // above two are required configuration, returns PCollection<JmsRecord>
 *
 *    // rest of the settings are optional
 *
 * }</pre>
 *
 * <p>It is possible to read any type of JMS {@link javax.jms.Message} into a custom POJO using the
 * following configuration:
 *
 * <pre>{@code
 * pipeline.apply(JmsIO.<T>readMessage()
 *    .withConnectionFactory(myConnectionFactory)
 *    .withQueue("my-queue")
 *    .withMessageMapper((MessageMapper<T>) message -> {
 *      // code that maps message to T
 *    })
 *    .withCoder(
 *      // a coder for T
 *    )
 *
 * }</pre>
 *
 * <h3>Writing to a JMS destination</h3>
 *
 * <p>JmsIO sink supports writing text messages to a JMS destination on a broker. To configure a JMS
 * sink, you must specify a {@link javax.jms.ConnectionFactory} and a {@link javax.jms.Destination}
 * name. For instance:
 *
 * <pre>{@code
 * pipeline
 *   .apply(...) // returns PCollection<String>
 *   .apply(JmsIO.write()
 *       .withConnectionFactory(myConnectionFactory)
 *       .withQueue("my-queue")
 * }</pre>
 */
@Experimental(Kind.SOURCE_SINK)
@SuppressWarnings({
  "nullness" // TODO(https://issues.apache.org/jira/browse/BEAM-10402)
})
public class JmsIO {

  private static final Logger LOG = LoggerFactory.getLogger(JmsIO.class);

  public static Read<JmsRecord> read() {
    return new AutoValue_JmsIO_Read.Builder<JmsRecord>()
        .setMaxNumRecords(Long.MAX_VALUE)
        .setCoder(SerializableCoder.of(JmsRecord.class))
        .setMessageMapper(
            (MessageMapper<JmsRecord>)
                new MessageMapper<JmsRecord>() {

                  @Override
                  public JmsRecord mapMessage(Message message) throws Exception {
                    TextMessage textMessage = (TextMessage) message;
                    Map<String, Object> properties = new HashMap<>();
                    @SuppressWarnings("rawtypes")
                    Enumeration propertyNames = textMessage.getPropertyNames();
                    while (propertyNames.hasMoreElements()) {
                      String propertyName = (String) propertyNames.nextElement();
                      properties.put(propertyName, textMessage.getObjectProperty(propertyName));
                    }

                    return new JmsRecord(
                        textMessage.getJMSMessageID(),
                        textMessage.getJMSTimestamp(),
                        textMessage.getJMSCorrelationID(),
                        textMessage.getJMSReplyTo(),
                        textMessage.getJMSDestination(),
                        textMessage.getJMSDeliveryMode(),
                        textMessage.getJMSRedelivered(),
                        textMessage.getJMSType(),
                        textMessage.getJMSExpiration(),
                        textMessage.getJMSPriority(),
                        properties,
                        textMessage.getText());
                  }
                })
        .build();
  }

  public static <T> Read<T> readMessage() {
    return new AutoValue_JmsIO_Read.Builder<T>().setMaxNumRecords(Long.MAX_VALUE).build();
  }

  public static <EventT> Write<EventT> write() {
    return new AutoValue_JmsIO_Write.Builder<EventT>().build();
  }

  /**
   * A {@link PTransform} to read from a JMS destination. See {@link JmsIO} for more information on
   * usage and configuration.
   */
  @AutoValue
  public abstract static class Read<T> extends PTransform<PBegin, PCollection<T>> {

    /**
     * NB: According to http://docs.oracle.com/javaee/1.4/api/javax/jms/ConnectionFactory.html "It
     * is expected that JMS providers will provide the tools an administrator needs to create and
     * configure administered objects in a JNDI namespace. JMS provider implementations of
     * administered objects should be both javax.jndi.Referenceable and java.io.Serializable so that
     * they can be stored in all JNDI naming contexts. In addition, it is recommended that these
     * implementations follow the JavaBeansTM design patterns."
     *
     * <p>So, a {@link ConnectionFactory} implementation is serializable.
     */
    abstract @Nullable ConnectionFactory getConnectionFactory();

    abstract @Nullable String getQueue();

    abstract @Nullable String getTopic();

    abstract @Nullable String getUsername();

    abstract @Nullable String getPassword();

    abstract long getMaxNumRecords();

    abstract @Nullable Duration getMaxReadTime();

    abstract @Nullable MessageMapper<T> getMessageMapper();

    abstract @Nullable Coder<T> getCoder();

    abstract @Nullable AutoScaler getAutoScaler();

    abstract Builder<T> builder();

    @AutoValue.Builder
    abstract static class Builder<T> {
      abstract Builder<T> setConnectionFactory(ConnectionFactory connectionFactory);

      abstract Builder<T> setQueue(String queue);

      abstract Builder<T> setTopic(String topic);

      abstract Builder<T> setUsername(String username);

      abstract Builder<T> setPassword(String password);

      abstract Builder<T> setMaxNumRecords(long maxNumRecords);

      abstract Builder<T> setMaxReadTime(Duration maxReadTime);

      abstract Builder<T> setMessageMapper(MessageMapper<T> mesageMapper);

      abstract Builder<T> setCoder(Coder<T> coder);

      abstract Builder<T> setAutoScaler(AutoScaler autoScaler);

      abstract Read<T> build();
    }

    /**
     * Specify the JMS connection factory to connect to the JMS broker.
     *
     * <p>For instance:
     *
     * <pre>{@code
     * pipeline.apply(JmsIO.read().withConnectionFactory(myConnectionFactory)
     *
     * }</pre>
     *
     * @param connectionFactory The JMS {@link ConnectionFactory}.
     * @return The corresponding {@link JmsIO.Read}.
     */
    public Read<T> withConnectionFactory(ConnectionFactory connectionFactory) {
      checkArgument(connectionFactory != null, "connectionFactory can not be null");
      return builder().setConnectionFactory(connectionFactory).build();
    }

    /**
     * Specify the JMS queue destination name where to read messages from. The {@link JmsIO.Read}
     * acts as a consumer on the queue.
     *
     * <p>This method is exclusive with {@link JmsIO.Read#withTopic(String)}. The user has to
     * specify a destination: queue or topic.
     *
     * <p>For instance:
     *
     * <pre>{@code
     * pipeline.apply(JmsIO.read().withQueue("my-queue")
     *
     * }</pre>
     *
     * @param queue The JMS queue name where to read messages from.
     * @return The corresponding {@link JmsIO.Read}.
     */
    public Read<T> withQueue(String queue) {
      checkArgument(queue != null, "queue can not be null");
      return builder().setQueue(queue).build();
    }

    /**
     * Specify the JMS topic destination name where to receive messages from. The {@link JmsIO.Read}
     * acts as a subscriber on the topic.
     *
     * <p>This method is exclusive with {@link JmsIO.Read#withQueue(String)}. The user has to
     * specify a destination: queue or topic.
     *
     * <p>For instance:
     *
     * <pre>{@code
     * pipeline.apply(JmsIO.read().withTopic("my-topic")
     *
     * }</pre>
     *
     * @param topic The JMS topic name.
     * @return The corresponding {@link JmsIO.Read}.
     */
    public Read<T> withTopic(String topic) {
      checkArgument(topic != null, "topic can not be null");
      return builder().setTopic(topic).build();
    }

    /** Define the username to connect to the JMS broker (authenticated). */
    public Read<T> withUsername(String username) {
      checkArgument(username != null, "username can not be null");
      return builder().setUsername(username).build();
    }

    /** Define the password to connect to the JMS broker (authenticated). */
    public Read<T> withPassword(String password) {
      checkArgument(password != null, "password can not be null");
      return builder().setPassword(password).build();
    }

    /**
     * Define the max number of records that the source will read. Using a max number of records
     * different from {@code Long.MAX_VALUE} means the source will be {@code Bounded}, and will stop
     * once the max number of records read is reached.
     *
     * <p>For instance:
     *
     * <pre>{@code
     * pipeline.apply(JmsIO.read().withNumRecords(1000)
     *
     * }</pre>
     *
     * @param maxNumRecords The max number of records to read from the JMS destination.
     * @return The corresponding {@link JmsIO.Read}.
     */
    public Read<T> withMaxNumRecords(long maxNumRecords) {
      checkArgument(maxNumRecords >= 0, "maxNumRecords must be > 0, but was: %s", maxNumRecords);
      return builder().setMaxNumRecords(maxNumRecords).build();
    }

    /**
     * Define the max read time that the source will read. Using a non null max read time duration
     * means the source will be {@code Bounded}, and will stop once the max read time is reached.
     *
     * <p>For instance:
     *
     * <pre>{@code
     * pipeline.apply(JmsIO.read().withMaxReadTime(Duration.minutes(10))
     *
     * }</pre>
     *
     * @param maxReadTime The max read time duration.
     * @return The corresponding {@link JmsIO.Read}.
     */
    public Read<T> withMaxReadTime(Duration maxReadTime) {
      checkArgument(maxReadTime != null, "maxReadTime can not be null");
      return builder().setMaxReadTime(maxReadTime).build();
    }

    public Read<T> withMessageMapper(MessageMapper<T> messageMapper) {
      checkArgument(messageMapper != null, "messageMapper can not be null");
      return builder().setMessageMapper(messageMapper).build();
    }

    public Read<T> withCoder(Coder<T> coder) {
      checkArgument(coder != null, "coder can not be null");
      return builder().setCoder(coder).build();
    }

    /**
     * Sets the {@link AutoScaler} to use for reporting backlog during the execution of this source.
     */
    public Read<T> withAutoScaler(AutoScaler autoScaler) {
      checkArgument(autoScaler != null, "autoScaler can not be null");
      return builder().setAutoScaler(autoScaler).build();
    }

    @Override
    public PCollection<T> expand(PBegin input) {
      checkArgument(getConnectionFactory() != null, "withConnectionFactory() is required");
      checkArgument(
          getQueue() != null || getTopic() != null,
          "Either withQueue() or withTopic() is required");
      checkArgument(
          getQueue() == null || getTopic() == null, "withQueue() and withTopic() are exclusive");
      checkArgument(getMessageMapper() != null, "withMessageMapper() is required");
      checkArgument(getCoder() != null, "withCoder() is required");

      // handles unbounded source to bounded conversion if maxNumRecords is set.
      Unbounded<T> unbounded = org.apache.beam.sdk.io.Read.from(createSource());

      PTransform<PBegin, PCollection<T>> transform = unbounded;

      if (getMaxNumRecords() < Long.MAX_VALUE || getMaxReadTime() != null) {
        transform =
            unbounded.withMaxReadTime(getMaxReadTime()).withMaxNumRecords(getMaxNumRecords());
      }

      return input.getPipeline().apply(transform);
    }

    @Override
    public void populateDisplayData(DisplayData.Builder builder) {
      super.populateDisplayData(builder);
      builder.addIfNotNull(DisplayData.item("queue", getQueue()));
      builder.addIfNotNull(DisplayData.item("topic", getTopic()));
    }

    ///////////////////////////////////////////////////////////////////////////////////////

    /**
     * Creates an {@link UnboundedSource UnboundedSource&lt;JmsRecord, ?&gt;} with the configuration
     * in {@link Read}. Primary use case is unit tests, should not be used in an application.
     */
    UnboundedSource<T, JmsCheckpointMark> createSource() {
      return new UnboundedJmsSource<T>(this);
    }
  }

  private JmsIO() {}

  /**
   * An interface used by {@link JmsIO.Read} for converting each jms {@link Message} into an element
   * of the resulting {@link PCollection}.
   */
  @FunctionalInterface
  public interface MessageMapper<T> extends Serializable {
    T mapMessage(Message message) throws Exception;
  }

  /** An unbounded JMS source. */
  static class UnboundedJmsSource<T> extends UnboundedSource<T, JmsCheckpointMark> {

    private final Read<T> spec;

    public UnboundedJmsSource(Read<T> spec) {
      this.spec = spec;
    }

    @Override
    public List<UnboundedJmsSource<T>> split(int desiredNumSplits, PipelineOptions options)
        throws Exception {
      List<UnboundedJmsSource<T>> sources = new ArrayList<>();
      if (spec.getTopic() != null) {
        // in the case of a topic, we create a single source, so a unique subscriber, to avoid
        // element duplication
        sources.add(new UnboundedJmsSource<T>(spec));
      } else {
        // in the case of a queue, we allow concurrent consumers
        for (int i = 0; i < desiredNumSplits; i++) {
          sources.add(new UnboundedJmsSource<T>(spec));
        }
      }
      return sources;
    }

    @Override
    public UnboundedJmsReader<T> createReader(
        PipelineOptions options, JmsCheckpointMark checkpointMark) {
      return new UnboundedJmsReader<T>(this, checkpointMark);
    }

    @Override
    public Coder<JmsCheckpointMark> getCheckpointMarkCoder() {
      return SerializableCoder.of(JmsCheckpointMark.class);
    }

    @Override
    public Coder<T> getOutputCoder() {
      return this.spec.getCoder();
    }
  }

  static class UnboundedJmsReader<T> extends UnboundedReader<T> {

    private UnboundedJmsSource<T> source;
    private JmsCheckpointMark checkpointMark;
    private Connection connection;
    private Session session;
    private MessageConsumer consumer;
    private AutoScaler autoScaler;

    private T currentMessage;
    private Instant currentTimestamp;

    public UnboundedJmsReader(UnboundedJmsSource<T> source, JmsCheckpointMark checkpointMark) {
      this.source = source;
      if (checkpointMark != null) {
        this.checkpointMark = checkpointMark;
      } else {
        this.checkpointMark = new JmsCheckpointMark();
      }
      this.currentMessage = null;
    }

    @Override
    public boolean start() throws IOException {
      Read<T> spec = source.spec;
      ConnectionFactory connectionFactory = spec.getConnectionFactory();
      try {
        Connection connection;
        if (spec.getUsername() != null) {
          connection = connectionFactory.createConnection(spec.getUsername(), spec.getPassword());
        } else {
          connection = connectionFactory.createConnection();
        }
        connection.start();
        this.connection = connection;
        if (spec.getAutoScaler() == null) {
          this.autoScaler = new DefaultAutoscaler();
        } else {
          this.autoScaler = spec.getAutoScaler();
        }
        this.autoScaler.start();
      } catch (Exception e) {
        throw new IOException("Error connecting to JMS", e);
      }

      try {
        this.session = this.connection.createSession(false, Session.CLIENT_ACKNOWLEDGE);
      } catch (Exception e) {
        throw new IOException("Error creating JMS session", e);
      }

      try {
        if (spec.getTopic() != null) {
          this.consumer = this.session.createConsumer(this.session.createTopic(spec.getTopic()));
        } else {
          this.consumer = this.session.createConsumer(this.session.createQueue(spec.getQueue()));
        }
      } catch (Exception e) {
        throw new IOException("Error creating JMS consumer", e);
      }

      return advance();
    }

    @Override
    public boolean advance() throws IOException {
      try {
        Message message = this.consumer.receiveNoWait();

        if (message == null) {
          currentMessage = null;
          return false;
        }

        checkpointMark.add(message);

        currentMessage = this.source.spec.getMessageMapper().mapMessage(message);
        currentTimestamp = new Instant(message.getJMSTimestamp());

        return true;
      } catch (Exception e) {
        throw new IOException(e);
      }
    }

    @Override
    public T getCurrent() throws NoSuchElementException {
      if (currentMessage == null) {
        throw new NoSuchElementException();
      }
      return currentMessage;
    }

    @Override
    public Instant getWatermark() {
      return checkpointMark.getOldestMessageTimestamp();
    }

    @Override
    public Instant getCurrentTimestamp() {
      if (currentMessage == null) {
        throw new NoSuchElementException();
      }
      return currentTimestamp;
    }

    @Override
    public CheckpointMark getCheckpointMark() {
      return checkpointMark;
    }

    @Override
    public long getTotalBacklogBytes() {
      return this.autoScaler.getTotalBacklogBytes();
    }

    @Override
    public UnboundedSource<T, ?> getCurrentSource() {
      return source;
    }

    @Override
    public void close() throws IOException {
      try {
        if (consumer != null) {
          consumer.close();
          consumer = null;
        }
        if (session != null) {
          session.close();
          session = null;
        }
        if (connection != null) {
          connection.stop();
          connection.close();
          connection = null;
        }
        if (autoScaler != null) {
          autoScaler.stop();
          autoScaler = null;
        }
      } catch (Exception e) {
        throw new IOException(e);
      }
    }
  }

  /**
   * A {@link PTransform} to write to a JMS queue. See {@link JmsIO} for more information on usage
   * and configuration.
   */
  @AutoValue
  public abstract static class Write<EventT>
      extends PTransform<PCollection<EventT>, WriteJmsResult<EventT>> {

    abstract @Nullable ConnectionFactory getConnectionFactory();

    abstract @Nullable String getQueue();

    abstract @Nullable String getTopic();

    abstract @Nullable String getUsername();

    abstract @Nullable String getPassword();

    abstract @Nullable SerializableBiFunction<EventT, Session, Message> getValueMapper();

    abstract @Nullable SerializableFunction<EventT, String> getTopicNameMapper();

    abstract Builder<EventT> builder();

    @AutoValue.Builder
    abstract static class Builder<EventT> {
      abstract Builder<EventT> setConnectionFactory(ConnectionFactory connectionFactory);

      abstract Builder<EventT> setQueue(String queue);

      abstract Builder<EventT> setTopic(String topic);

      abstract Builder<EventT> setUsername(String username);

      abstract Builder<EventT> setPassword(String password);

      abstract Builder<EventT> setValueMapper(
          SerializableBiFunction<EventT, Session, Message> valueMapper);

      abstract Builder<EventT> setTopicNameMapper(
          SerializableFunction<EventT, String> topicNameMapper);

      abstract Write<EventT> build();
    }

    /**
     * Specify the JMS connection factory to connect to the JMS broker.
     *
     * <p>For instance:
     *
     * <pre>{@code
     * .apply(JmsIO.write().withConnectionFactory(myConnectionFactory)
     *
     * }</pre>
     *
     * @param connectionFactory The JMS {@link ConnectionFactory}.
     * @return The corresponding {@link JmsIO.Read}.
     */
    public Write<EventT> withConnectionFactory(ConnectionFactory connectionFactory) {
      checkArgument(connectionFactory != null, "connectionFactory can not be null");
      return builder().setConnectionFactory(connectionFactory).build();
    }

    /**
     * Specify the JMS queue destination name where to send messages to. The {@link JmsIO.Write}
     * acts as a producer on the queue.
     *
     * <p>This method is exclusive with {@link JmsIO.Write#withTopic(String)}. The user has to
     * specify a destination: queue, topic, or topicNameMapper.
     *
     * <p>For instance:
     *
     * <pre>{@code
     * .apply(JmsIO.write().withQueue("my-queue")
     *
     * }</pre>
     *
     * @param queue The JMS queue name where to send messages to.
     * @return The corresponding {@link JmsIO.Read}.
     */
    public Write<EventT> withQueue(String queue) {
      checkArgument(queue != null, "queue can not be null");
      return builder().setQueue(queue).build();
    }

    /**
     * Specify the JMS topic destination name where to send messages to. The {@link JmsIO.Read} acts
     * as a publisher on the topic.
     *
     * <p>This method is exclusive with {@link JmsIO.Write#withQueue(String)}. The user has to
     * specify a destination: queue, topic, or topicNameMapper.
     *
     * <p>For instance:
     *
     * <pre>{@code
     * .apply(JmsIO.write().withTopic("my-topic")
     *
     * }</pre>
     *
     * @param topic The JMS topic name.
     * @return The corresponding {@link JmsIO.Read}.
     */
    public Write<EventT> withTopic(String topic) {
      checkArgument(topic != null, "topic can not be null");
      return builder().setTopic(topic).build();
    }

    /** Define the username to connect to the JMS broker (authenticated). */
    public Write<EventT> withUsername(String username) {
      checkArgument(username != null, "username can not be null");
      return builder().setUsername(username).build();
    }

    /** Define the password to connect to the JMS broker (authenticated). */
    public Write<EventT> withPassword(String password) {
      checkArgument(password != null, "password can not be null");
      return builder().setPassword(password).build();
    }

    /**
     * Specify the JMS topic destination name where to send messages to dynamically. The {@link
     * JmsIO.Write} acts as a publisher on the topic.
     *
     * <p>This method is exclusive with {@link JmsIO.Write#withQueue(String) and
     * {@link JmsIO.Write#withTopic(String)}. The user has to specify a {@link SerializableFunction}
     * that takes {@code EventT} object as a parameter, and returns the topic name depending of the content
     * of the event object.
     *
     * <p>For example:
     * <pre>{@code
     * SerializableFunction<CompanyEvent, String> topicNameMapper =
     *   (event ->
     *    String.format(
     *    "company/%s/employee/%s",
     *    event.getCompanyName(),
     *    event.getEmployeeId()));
     * }</pre>
     *
     * <pre>{@code
     * .apply(JmsIO.write().withTopicNameMapper(topicNameNapper)
     * }</pre>
     *
     * @param topicNameMapper The function returning the dynamic topic name.
     * @return The corresponding {@link JmsIO.Write}.
     */
    public Write<EventT> withTopicNameMapper(SerializableFunction<EventT, String> topicNameMapper) {
      checkArgument(topicNameMapper != null, "topicNameMapper can not be null");
      return builder().setTopicNameMapper(topicNameMapper).build();
    }

    /**
     * Map the {@code EventT} object to a {@link javax.jms.Message}.
     *
     * <p>For instance:
     *
     * <pre>{@code
     * SerializableBiFunction<SomeEventObject, Session, Message> valueMapper = (e, s) -> {
     *
     *       try {
     *         TextMessage msg = s.createTextMessage();
     *         msg.setText(Mapper.MAPPER.toJson(e));
     *         return msg;
     *       } catch (JMSException ex) {
     *         throw new JmsIOException("Error!!", ex);
     *       }
     *     };
     *
     * }</pre>
     *
     * <pre>{@code
     * .apply(JmsIO.write().withValueMapper(valueNapper)
     * }</pre>
     *
     * @param valueMapper The function returning the {@link javax.jms.Message}
     * @return The corresponding {@link JmsIO.Write}.
     */
    public Write<EventT> withValueMapper(
        SerializableBiFunction<EventT, Session, Message> valueMapper) {
      checkArgument(valueMapper != null, "valueMapper can not be null");
      return builder().setValueMapper(valueMapper).build();
    }

    @Override
    public WriteJmsResult<EventT> expand(PCollection<EventT> input) {
      checkArgument(getConnectionFactory() != null, "withConnectionFactory() is required");
      checkArgument(
          getTopicNameMapper() != null || getQueue() != null || getTopic() != null,
          "Either withTopicNameMapper(topicNameMapper), withQueue(queue), or withTopic(topic) is required");
      boolean exclusiveTopicQueue = isExclusiveTopicQueue();
      checkArgument(
          exclusiveTopicQueue,
          "Only one of withQueue(queue), withTopic(topic), or withTopicNameMapper(function) must be set.");
      checkArgument(getValueMapper() != null, "withValueMapper() is required");

      final TupleTag<EventT> failedMessagesTag = new TupleTag<>();
      final TupleTag<EventT> messagesTag = new TupleTag<>();
      PCollectionTuple res =
          input.apply(
              ParDo.of(new WriterFn<>(this, failedMessagesTag))
                  .withOutputTags(messagesTag, TupleTagList.of(failedMessagesTag)));
      PCollection<EventT> failedMessages = res.get(failedMessagesTag).setCoder(input.getCoder());
      res.get(messagesTag).setCoder(input.getCoder());
      return WriteJmsResult.in(input.getPipeline(), failedMessagesTag, failedMessages);
    }

    private boolean isExclusiveTopicQueue() {
      boolean exclusiveTopicQueue =
          Stream.of(getQueue() != null, getTopic() != null, getTopicNameMapper() != null)
                  .filter(b -> b)
                  .count()
              == 1;
      return exclusiveTopicQueue;
    }

    private static class WriterFn<EventT> extends DoFn<EventT, EventT> {

      private Write<EventT> spec;

      private Connection connection;
      private Session session;
      private MessageProducer producer;
      private Destination destination;
      private final TupleTag<EventT> failedMessageTag;

      public WriterFn(Write<EventT> spec, TupleTag<EventT> failedMessageTag) {
        this.spec = spec;
        this.failedMessageTag = failedMessageTag;
      }

      @Setup
      public void setup() throws Exception {
        if (producer == null) {
          if (spec.getUsername() != null) {
            this.connection =
                spec.getConnectionFactory()
                    .createConnection(spec.getUsername(), spec.getPassword());
          } else {
            this.connection = spec.getConnectionFactory().createConnection();
          }
          this.connection.start();
          // false means we don't use JMS transaction.
          this.session = this.connection.createSession(false, Session.AUTO_ACKNOWLEDGE);

          if (spec.getQueue() != null) {
            this.destination = session.createQueue(spec.getQueue());
          } else if (spec.getTopic() != null) {
            this.destination = session.createTopic(spec.getTopic());
          }

          this.producer = this.session.createProducer(null);
        }
      }

      @ProcessElement
      public void processElement(ProcessContext ctx) {
        Destination destinationToSendTo = destination;
        try {
          Message message = spec.getValueMapper().apply(ctx.element(), session);
          if (spec.getTopicNameMapper() != null) {
            destinationToSendTo =
                session.createTopic(spec.getTopicNameMapper().apply(ctx.element()));
          }
          producer.send(destinationToSendTo, message);
        } catch (Exception ex) {
          LOG.error("Error sending message on topic {}", destinationToSendTo);
          ctx.output(failedMessageTag, ctx.element());
        }
      }

      @Teardown
      public void teardown() throws Exception {
        producer.close();
        producer = null;
        session.close();
        session = null;
        connection.stop();
        connection.close();
        connection = null;
      }
    }
  }
}
