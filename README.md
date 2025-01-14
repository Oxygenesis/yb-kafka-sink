# YugabyteDB Apache Kafka Connector

An Apache Kafka® sink for transferring events/messages from Kafka topics to YugabyteDB. It supports both CQL as well as JDBC Sink.

## Documentation

All documentation for cql Sink is available online [here](https://docs.datastax.com/en/kafka/doc/index.html).
All documentation for jdbc Sink is available online [here](https://docs.confluent.io/kafka-connect-jdbc/current/sink-connector/sink_config_options.html).

## Building from the sources

If you want to use the connector you need to build the jar from sources.
To do so please follow those steps:

1. First build the uber-jar: 

       git clone https://github.com/yugabyte/dsbulk.git
       cd dsbulk
       // install locally
       mvn clean install -DskipTests
       
       git clone https://github.com/yugabyte/messaging-connectors-commons.git
       cd messaging-connectors-commons
       // install locally
       mvn clean install -DskipTests
       
       git clone https://github.com/yugabyte/yb-kafka-sink.git
       cd kafka-sink
       mvn clean package -DskipTests
       
2. Open the Connect worker config file `config/connect-standalone.properties`. Update the plugin 
   search path to include the uber-jar kafka-connect-yugabytedb-sink-1.4.1-SNAPSHOT.jar :

       plugin.path=<previous value>,<full path to repo>/dist/target/kafka-connect-yugabytedb-sink-1.4.1-SNAPSHOT.jar

3. Edit the `dist/conf/yugabyte-sink-standalone.properties.sample` config file in this project to 
   meet your needs, or copy it out and edit elsewhere. The edited file should be named 
   `yugabyte-sink-standalone.properties`.

4. A sample sink config file for cql would be something like
 ```json
 {
    "name": "yb-cql-sink",
    "config": {
    "connector.class": "com.datastax.oss.kafka.sink.CassandraSinkConnector",
    "tasks.max": "12",
    "maxNumberOfRecordsInBatch":"100",
    "topics": "ticks",
    "contactPoints": "<ip1>,<ip2>,<ip3>",
    "loadBalancing.localDc": "us-west-2",
    "topic.avro-stream.stocks.ticks.mapping": "name=key, symbol=value.symbol, ts=value.datetime, exchange=value.exchange, industry=value.industry, value=value.value",
    "topic.avro-stream.stocks.ticks.consistencyLevel": "QUORUM"
    }
 }
```
A sample sink config for sql would be something like
```json
{
  "name": "yb-jdbc-sink",
  "config": {
    "connector.class": "com.yugabyte.jdbc.JdbcSinkConnector",
    "tasks.max": "10",
    "topics": "ticks",
    "connection.urls":"jdbc:postgresql://localhost:5433/yugabyte",
    "connection.user":"yugabyte",
    "connection.password":"yugabyte",
    "batch.size":"256",
    "mode":"INSERT",
    "auto.create":"true"
  }
}
```
5. Run connect-standalone and specify the path to the that config file:

       bin/connect-standalone.sh \
          config/connect-standalone.properties 
          <full path to file>/yugabyte-sink-standalone.properties

5. In Confluent, you would do this:

       bin/confluent load cassandra-sink -d <full path to file>/yugabyte-sink-standalone.properties

## Mapping specification

To see practical examples and usages of mapping, see:
https://docs.datastax.com/en/kafka/doc/search.html?searchQuery=mapping 
