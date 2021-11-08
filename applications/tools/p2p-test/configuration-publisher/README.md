# P2P Configuration publisher application
A utility to publish configuration for the p2p components

## Building
To build run:
`./gradlew :applications:tools:p2p-test:configuration-publisher:clean :applications:tools:p2p-test:configuration-publisher:appJar`
This will create an executable jar in `applications/p2p-gateway-config-publisher/build/bin` 

## Running
To run the application use:
`java -jar ./applications/tools/p2p-test/configuration-publisher/build/bin/corda-configuration-publisher-5.0.0.0-SNAPSHOT.jar <gateway/linkmanager/file>`

### Command arguments:
#### Common arguments:
```
      [@<filename>...]   One or more argument files containing options.
      --config-topic-name=<configTopicName>
                         The config topic name
                           Default: ConfigTopic
  -h, --help             Display help and exit
  -k, --kafka-servers=<kafkaServers>
                         The kafka servers
                           Default: localhost:9092
      --topic-prefix=<topicPrefix>
                         The topic prefix
                           Default:
```
#### Gateway Arguments:
```
      [@<filename>...]    One or more argument files containing options.
      --acquireTimeoutSec=<acquireTimeoutSec>
                          The client connection acquire timeout in seconds
                            Default: 10
      --connectionIdleTimeoutSec=<connectionIdleTimeoutSec>
                          The amount of time to keep inactive client connection
                            before closing it in seconds
                            Default: 60
  -h, --help              display this help message
      --host=<hostname>   The name of the HTTP host
                            Default: yift-XPS-15-7590
      --keyStore=<keyStoreFile>
                          The key store file
                            Default: keystore.jks
      --keyStorePassword=<keyStorePassword>
                          The key store password
                            Default: password
      --maxClientConnections=<maxClientConnections>
                          The maximal number of client connections
                            Default: 100
      --port=<port>       The HTTP port
                            Default: 80
      --responseTimeoutMilliSecs=<responseTimeoutMilliSecs>
                          Time after which a message delivery is considered
                            failed in milliseconds
                            Default: 1000
      --retryDelayMilliSecs=<retryDelayMilliSecs>
                          Time after which a message is retried, when
                            previously failed in milliseconds
                            Default: 1000
      --revocationCheck=<revocationCheck>
                          Revocation Check mode (one of: SOFT_FAIL, HARD_FAIL,
                            OFF)
                            Default: OFF
      --trustStore=<trustStoreFile>
                          The trust store file
                            Default: truststore.jks
      --trustStorePassword=<trustStorePassword>
                          The trust store password
                            Default: password
```
#### Link manager Arguments:
```
      [@<filename>...]   One or more argument files containing options.
  -h, --help             display this help message
      --heartbeatMessagePeriodMilliSecs=<heartbeatMessagePeriodMilliSecs>
                         Heartbeat message period in milli seconds
                           Default: 2000
      --locallyHostedIdentity=<locallyHostedIdentity>
                         Local hosted identity (in the form of <x500Name>:
                           <groupId>)
      --maxMessageSize=<maxMessageSize>
                         The maximal message size
                           Default: 500
      --messageReplayPeriodSecs=<messageReplayPeriodSecs>
                         message replay period in seconds
                           Default: 2
      --protocolMode=<protocolModes>
                         Supported protocol mode (out of: AUTHENTICATION_ONLY,
                           AUTHENTICATED_ENCRYPTION)
                           Default: [AUTHENTICATED_ENCRYPTION]
      --sessionTimeoutMilliSecs=<sessionTimeoutMilliSecs>
                         Session timeout in milliseconds
                           Default: 10000
```

## Example
1. Before starting the application, run a kafka cluster. See examples in [here](../../../../testing/message-patterns/README.md).
2. Start the gateway: 
```bash
java \
  -Djdk.net.hosts.file=./components/gateway/src/integration-test/resources/hosts \
  -jar ./applications/p2p-gateway/build/bin/corda-p2p-gateway-5.0.0.0-SNAPSHOT.jar
```
The `-Djdk.net.hosts.file` will overwrite the hosts file, allow the JVM to open localhost as if it was `www.alice.net`
3. Publish the configuration:
```bash
java \
-jar ./applications/tools/p2p-test/configuration-publisher/build/bin/corda-configuration-publisher-5.0.0.0-SNAPSHOT.jar \
gateway \
--keyStore ./components/gateway/src/integration-test/resources/sslkeystore_alice.jks \
--trustStore ./components/gateway/src/integration-test/resources/truststore.jks \
--port 3123 \
--host www.alice.net
```
The `keyStore` and `trustStore` are valid stores used in the integration tests.

Or, one can load the configuration from an arguments file. For example, from [gateway-args-example](gateway-args-example.txt):
```bash
java \
-jar ./applications/tools/p2p-test/configuration-publisher/build/bin/corda-configuration-publisher-5.0.0.0-SNAPSHOT.jar \
@./applications/tools/p2p-test/configuration-publisher/gateway-args-example.txt
```