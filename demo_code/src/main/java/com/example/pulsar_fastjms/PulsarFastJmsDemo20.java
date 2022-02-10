package com.example.pulsar_fastjms;

import com.datastax.oss.pulsar.jms.PulsarConnectionFactory;
import com.datastax.oss.pulsar.jms.messages.PulsarTextMessage;
import com.example.util.DemoUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.pulsar.client.api.Message;

import javax.jms.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class PulsarFastJmsDemo20 {

    static final Logger LOGGER = LogManager.getLogger(PulsarFastJmsDemo20.class);

    static AtomicInteger destMsgRecvedCntAtomic = new AtomicInteger(0);

    static final String PULSAR_WEB_SVC_URL = "http://" + DemoUtils.TEST_SRV_IP + ":8080";
    static final String PULSAR_BROKER_SVC_RUL = "pulsar://" + DemoUtils.TEST_SRV_IP + ":6650";

    static final String TOKEN_FILE = "/path/to/cluster-admin.jwt";

    static final String SRC_TOPIC_NAME = "persistent://public/default/jmsdemo";

    public static class ConsumerMsgRecvWorker implements Runnable, ExceptionListener {
        JMSContext context;
        JMSConsumer consumer;
        Topic destination;

        public ConsumerMsgRecvWorker(JMSContext context, JMSConsumer consumer, Topic destination) {
            this.context = context;
            this.consumer = consumer;
            this.destination = destination;
        }

        public void run() {
            try {
                context.setExceptionListener(this);
                LOGGER.info("   starts to receive messages");

                int totalMsgRecved = 0;
                Map<String, Integer> msgCntMap = new HashMap<>();

                while (true) {
                    TextMessage message = (TextMessage) consumer.receive(10);

                    if (message != null) {
                        message.acknowledge();
                        destMsgRecvedCntAtomic.incrementAndGet();

                        Message pMsg = ((PulsarTextMessage)message).getReceivedPulsarMessage();
                        String pMsgKey = pMsg.getKey();

                        if (!msgCntMap.containsKey(pMsgKey)) {
                            msgCntMap.put(pMsgKey, 1);
                        }
                        else {
                            Integer cnt = msgCntMap.get(pMsgKey);
                            msgCntMap.put(pMsgKey, Integer.valueOf(cnt.intValue() + 1));
                        }

                        LOGGER.info("   message received: [Key - " + pMsgKey + "] " +
                                "[JMS Property(JMXGroupID) - " + message.getStringProperty ("JMSXGroupID") + "] " + message.getText());
                    }

                    totalMsgRecved = destMsgRecvedCntAtomic.get();
                    if ( totalMsgRecved >= DemoUtils.MAX_MSG_TO_PRODUCE ) {
                        break;
                    }

                    Thread.sleep(1);
                    Thread.yield();
                }

                LOGGER.info("   finishes receiving messages (total received: " + msgCntMap.toString() + ")");
            } catch (Exception e) {
                LOGGER.info("[" + getClass().getName() + "] Exception caught: " + e);
                e.printStackTrace();
            }
        }

        public synchronized void onException(JMSException ex) {
            LOGGER.info("JMS Exception occurred.  Shutting down client.");
        }
    }


    /**
     * Create the connection property map that is required for establishing
     * the connection to the Pulsar server
     * @return
     */
    public static Map<String, Object> getPulsarJmsConfMap() {
        Map<String, Object> clientConfMap = new HashMap<>();

        clientConfMap.put("webServiceUrl", PULSAR_WEB_SVC_URL);
        clientConfMap.put("brokerServiceUrl", PULSAR_BROKER_SVC_RUL);
        clientConfMap.put("authPlugin", "org.apache.pulsar.client.impl.auth.AuthenticationToken");
        clientConfMap.put("authParams", "file://" + TOKEN_FILE);

        // Needed for KEY_SHARED subscription
        Map<String, Object> producerConfig = new HashMap<>();
        producerConfig.put("batcherBuilder", "KEY_BASED");
        // default is true
        producerConfig.put("batchingEnabled", "true");

        clientConfMap.put("producerConfig", producerConfig);

        // Needed for KEY_SHARED subscription
        clientConfMap.put("jms.topicSharedSubscriptionType", "Key_Shared");

        return clientConfMap;
    }

    public static void main(String[] arg) {

        try {
            ////////////////
            // Establish connection to the Pulsar server
            //
            Map<String, Object> jmsConfMap = getPulsarJmsConfMap();
            LOGGER.info("Establish connection to Pulsar server with config: \n" +
                    "    " + jmsConfMap.toString() + "\n");
            PulsarConnectionFactory factory = new PulsarConnectionFactory(jmsConfMap);
            // JMSContext producerContext = factory.createContext(Session.AUTO_ACKNOWLEDGE);
            JMSContext producerContext = factory.createContext(Session.CLIENT_ACKNOWLEDGE);


            ////////////////
            // Create a Pulsar topic and a producer
            Topic pulsarTopic = producerContext.createTopic(SRC_TOPIC_NAME);
            LOGGER.info("Created a topic: " + pulsarTopic.getTopicName() + "\n");
            JMSProducer topicProducer = producerContext.createProducer();
            LOGGER.info("Created a producer: " + topicProducer.toString() + "\n");


            ////////////////
            // Create a series of consumer threads with KEY_SHARED subscription type to receive messages from
            // the corresponding queue (each queue corresponds to one particular message identifier)
            //
            LOGGER.info("Create a series of consumers to receive messages by their category IDs (KEY_SHARED)");
            Map<String, JMSContext> destConsumerContexts = new HashMap<>();
            Map<String, JMSConsumer> destConsumers = new HashMap<>();
            for (int i = 0; i < DemoUtils.MAX_MSG_CAT_COUNT; i++) {
                JMSContext destConsumerContext = factory.createContext(Session.CLIENT_ACKNOWLEDGE);
                destConsumerContexts.put(DemoUtils.getCatIdStr(i+1), destConsumerContext);

                JMSConsumer destConsumer = destConsumerContext.createSharedDurableConsumer(pulsarTopic, "mysubscription");
                destConsumers.put(DemoUtils.getCatIdStr(i+1), destConsumer);

                if (i < (DemoUtils.MAX_MSG_CAT_COUNT - 1))
                    LOGGER.info("   created a consumer: " + destConsumer.toString());
                else
                    LOGGER.info("   created a consumer: " + destConsumer.toString() + "\n");
            }

            ////////////////
            //
            LOGGER.info("Publish " + DemoUtils.MAX_MSG_TO_PRODUCE + " messages to the source topic " +
                    "(name: " + pulsarTopic.getTopicName() + ")");
            ArrayList<TextMessage> textMessages = DemoUtils.createMessage(producerContext, DemoUtils.MAX_MSG_TO_PRODUCE);
            Map<String, Integer> msgCntByGroupID = DemoUtils.getJMSMsgCntByGroupId(textMessages);
            for (int i = 0; i < DemoUtils.MAX_MSG_TO_PRODUCE; i++) {
                TextMessage textMessage = textMessages.get(i);
                topicProducer.send(pulsarTopic, textMessage);

                LOGGER.info("   message published: [JMS Property(JMSXGroupID) - " +
                        textMessage.getStringProperty("JMSXGroupID") + "] " + textMessage.getText());
            }
            LOGGER.info("Message count: " + msgCntByGroupID.toString() + "\n");


            ////////////////
            // Launch the consumers concurrently to receive the messages
            LOGGER.info("Launch message receiving worker threads to receive messages concurrently");
            ExecutorService executor = Executors.newFixedThreadPool(DemoUtils.MAX_MSG_CAT_COUNT);
            for (int i = 0; i < DemoUtils.MAX_MSG_CAT_COUNT; i++) {
                ConsumerMsgRecvWorker destConsumer =
                        new ConsumerMsgRecvWorker(
                                destConsumerContexts.get(DemoUtils.getCatIdStr(i+1)),
                                destConsumers.get(DemoUtils.getCatIdStr(i+1)),
                                pulsarTopic);
                Runnable destConsumerWorker = new Thread(destConsumer);
                executor.submit(destConsumerWorker);
            }

            executor.shutdown();
            while (!executor.isTerminated()) {
            }

            ////////////////
            // Clean up
            //
            // -- KEY_SHARED consumers
            for (JMSConsumer destConsumer : destConsumers.values()) {
                destConsumer.close();
            }

            // -- contexts for KEY_SHARED consumers
            for (JMSContext destConsumerContext : destConsumerContexts.values()) {
                destConsumerContext.close();
            }

            // -- main session and connection
            producerContext.close();

        }
        catch (Exception e) {
            LOGGER.info("Exception caught: " + e);
            e.printStackTrace();
        }

        System.exit(0);
    }
}
