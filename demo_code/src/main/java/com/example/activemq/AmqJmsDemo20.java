package com.example.activemq;

import com.example.util.DemoUtils;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.jms.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AmqJmsDemo20 extends  AmqJmsDemo {

    private static final Logger LOGGER = LogManager.getLogger(AmqJmsDemo20.class);

    public static class DestQueueConsumer implements Runnable, ExceptionListener {
        JMSContext context;
        JMSConsumer consumer;
        Queue destination;

        public DestQueueConsumer(JMSContext context, JMSConsumer consumer, Queue destination) {
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

                        String msgCatId = message.getStringProperty("JMSXGroupID");
                        if (!msgCntMap.containsKey(msgCatId)) {
                            msgCntMap.put(msgCatId, 1);
                        }
                        else {
                            Integer cnt = msgCntMap.get(msgCatId);
                            msgCntMap.put(msgCatId, Integer.valueOf(cnt.intValue() + 1));
                        }

                        LOGGER.info("   message received: [JMS Property(JMXGroupID) - " + msgCatId + "] " + message.getText());
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

    public static void main(String[] arg) {

        try {
            ////////////////
            // Establish connection to the ActiveMQ server
            //
            // Create a ConnectionFactory and a JMSContext (JMS 2.0)
            LOGGER.info("Establish connection to ActiveMQ server (" + BROKER_URL + ")\n");
            ActiveMQConnectionFactory connectionFactory = new ActiveMQConnectionFactory(BROKER_URL);
            // JMSContext jmsContext = connectionFactory.createContext(BROKER_USERNAME, BROKER_PASSWORD, Session.AUTO_ACKNOWLEDGE);
            JMSContext jmsContext = connectionFactory.createContext(BROKER_USERNAME, BROKER_PASSWORD, Session.CLIENT_ACKNOWLEDGE);


            ////////////////
            // Create the source topic, the producer and the bridge consumer
            //
            // Create the source topic
            Topic srcTopic = jmsContext.createTopic(AMQ_SRC_TOPIC_NAME);
            LOGGER.info("Created one source topic: " + srcTopic.getTopicName() + "\n");

            // Create the producer that publishes messages to the source topic
            JMSProducer srcTopicProducer = jmsContext.createProducer();
            srcTopicProducer.setDeliveryMode(DeliveryMode.PERSISTENT);
            LOGGER.info("Created one producer to the source topic: " + srcTopicProducer.toString() + "\n");


            ////////////////
            // Create the bridge subscriber (consumer) to the Topic
            // - This needs to be created before the producer in order to
            //   receive the published messages
            LOGGER.info("Create a topic-to-queue bridge simulator");
            LOGGER.info("   Create one bridge consumer to the source topic");
            JMSConsumer bridgeTopicConsumer = jmsContext.createConsumer(srcTopic);
            LOGGER.info("      bridge topic consumer created: " + bridgeTopicConsumer.toString() + "\n");


            ////////////////
            // Create a series of destination queues and one bridge producer to them
            // Each queue corresponds to a specific message category.
            LOGGER.info("   Create a series of destination queues that the source topic is bridged to");
            Map<String, Queue> destQueues = new HashMap<>();
            for (int i = 0; i < DemoUtils.MAX_MSG_CAT_COUNT; i++) {
                Queue queue = jmsContext.createQueue(AMQ_DEST_QUEUE_NAM_BASE + "_" + (i+1));
                destQueues.put(DemoUtils.getCatIdStr(i+1), queue);
                if (i < (DemoUtils.MAX_MSG_CAT_COUNT - 1))
                    LOGGER.info("      destination queue created: " + queue.toString());
                else
                    LOGGER.info("      destination queue created: " + queue.toString() + "\n");
            }

            LOGGER.info("   Create one bridge producer that filters and routes the messages from the source topic to the destination queues");
            JMSProducer destQueueProducer = jmsContext.createProducer();
            destQueueProducer.setDeliveryMode(DeliveryMode.PERSISTENT);
            LOGGER.info("      bridge destination queue producer created: " + destQueueProducer.toString());
            LOGGER.info("The topic-to-queue bridge simulator is created\n");


            ////////////////
            // Publish message to the source topic
            LOGGER.info("Publish " + DemoUtils.MAX_MSG_TO_PRODUCE + " messages to the source topic " +
                    "(name: " + srcTopic.getTopicName() + ")");
            ArrayList<TextMessage> textMessages = DemoUtils.createMessage(jmsContext, DemoUtils.MAX_MSG_TO_PRODUCE);
            Map<String, Integer> msgCntByGroupID = DemoUtils.getJMSMsgCntByGroupId(textMessages);
            for (int i = 0; i < DemoUtils.MAX_MSG_TO_PRODUCE; i++) {
                TextMessage textMessage = textMessages.get(i);
                LOGGER.info("   message published: [JMS Property(JMSXGroupID) - " +
                        textMessage.getStringProperty("JMSXGroupID") + "] " + textMessage.getText());
                srcTopicProducer.send(srcTopic, textMessage);
            }
            LOGGER.info("Message count: " + msgCntByGroupID.toString() + "\n");


            ////////////////
            // The bridge topic consumer receives the messages from the source topic
            // and send them to the corresponding destination queues based on the
            // message category property value
            //
            LOGGER.info("The bridge simulator (the source topic consumer and the destination queue producer) does the filtering and routing magic ...\n");
            for (int i = 0; i < DemoUtils.MAX_MSG_TO_PRODUCE; i++) {
                Message msgRecved = bridgeTopicConsumer.receive(1000);

                if (msgRecved != null) {
                    msgRecved.acknowledge();

                    String msgCatId = msgRecved.getStringProperty("JMSXGroupID");

                    Queue destQueue = destQueues.get(msgCatId);
                    destQueueProducer.send(destQueue, msgRecved);
                }
            }


            ////////////////
            // Create a series of destQueue consumer thread to receive messages from
            // the corresponding queue (each queue corresponds to one particular message identifier)
            //
            LOGGER.info("Create a sires of destination queue consumers to receive messages by their category IDs");
            // Create a series of dest queue consumers and corresponding contexts.
            // Each queue corresponds to a specific message category.
            Map<String, JMSContext> destQueueConsumerContexts = new HashMap<>();
            Map<String, JMSConsumer> destQueueConsumers = new HashMap<>();
            for (int i = 0; i < DemoUtils.MAX_MSG_CAT_COUNT; i++) {
                JMSContext destQueueConsumerContext = connectionFactory.createContext(BROKER_USERNAME, BROKER_PASSWORD, Session.CLIENT_ACKNOWLEDGE);
                destQueueConsumerContexts.put(DemoUtils.getCatIdStr(i+1), destQueueConsumerContext);

                Queue destQueue = destQueues.get(DemoUtils.getCatIdStr(i+1));
                JMSConsumer destQueueConsumer = destQueueConsumerContext.createConsumer(destQueue);
                destQueueConsumers.put(DemoUtils.getCatIdStr(i+1), destQueueConsumer);

                if (i < (DemoUtils.MAX_MSG_CAT_COUNT - 1))
                    LOGGER.info("   created a consumer: " + destQueueConsumer.toString());
                else
                    LOGGER.info("   created a consumer: " + destQueueConsumer.toString() + "\n");
            }

            LOGGER.info("Launch message receiving worker threads to receive messages concurrently");
            // Launch the destQueue consumer threads concurrently to receive the messages
            ExecutorService executor = Executors.newFixedThreadPool(DemoUtils.MAX_MSG_CAT_COUNT*2);
            for (int i = 0; i < DemoUtils.MAX_MSG_CAT_COUNT; i++) {
                DestQueueConsumer destQueueConsumer =
                        new DestQueueConsumer(
                                destQueueConsumerContexts.get(DemoUtils.getCatIdStr(i+1)),
                                destQueueConsumers.get(DemoUtils.getCatIdStr(i+1)),
                                destQueues.get(DemoUtils.getCatIdStr(i+1)));
                Runnable destQueueConsumerWorker = new Thread(destQueueConsumer);
                executor.execute(destQueueConsumerWorker);
            }

            executor.shutdown();
            while (!executor.isTerminated()) {
            }


            ////////////////
            // Clean up
            //
            // -- destQueue consumers
            for (JMSConsumer destQueueConsumer : destQueueConsumers.values()) {
                destQueueConsumer.close();
            }

            // -- contexts for destQueue consumers
            for (JMSContext destQueueContext : destQueueConsumerContexts.values()) {
                destQueueContext.close();
            }

            // -- bridgeConsumer to srcTopic
            bridgeTopicConsumer.close();

            // -- main session and connection
            jmsContext.close();
        }
        catch (Exception e) {
            LOGGER.info("Exception caught: " + e);
            e.printStackTrace();
        }

        System.exit(0);
    }
}
