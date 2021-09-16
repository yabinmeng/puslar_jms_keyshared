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

public class AmqJmsDemo11 extends AmqJmsDemo {

    private static final Logger LOGGER = LogManager.getLogger(AmqJmsDemo11.class);

    public static class DestQueueConsumer implements Runnable, ExceptionListener {
        Connection connection;
        MessageConsumer consumer;
        Queue destination;

        public DestQueueConsumer(Connection connection, Session session, MessageConsumer consumer, Queue destination) {
            this.connection = connection;
            this.consumer = consumer;
            this.destination = destination;
        }

        public void run() {
            try {
                connection.setExceptionListener(this);

                int totalMsgRecved = 0;

                while (true) {
                    TextMessage message = (TextMessage) consumer.receive(10);

                    if (message != null) {
                        message.acknowledge();
                        destMsgRecvedCntAtomic.incrementAndGet();

                        LOGGER.info(" <<< [" + Thread.currentThread().getName() + "] " +
                                "[dest queue consumer] (Queue: " + destination.getQueueName() + "); " +
                                "Message received: [JMSXGroupID - " + message.getStringProperty("JMSXGroupID") + "] " + message.getText());
                    }

                    // LOGGER.info("     ##### [" + Thread.currentThread().getName() + "]" + totalMsgRecved);

                    totalMsgRecved = destMsgRecvedCntAtomic.get();
                    if ( totalMsgRecved >= DemoUtils.MAX_MSG_TO_PRODUCE ) {
                        break;
                    }

                    Thread.sleep(1);
                }

                // LOGGER.info(" ##### [" + Thread.currentThread().getName() + " exit!]");
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
            // Create a ConnectionFactory
            ActiveMQConnectionFactory connectionFactory = new ActiveMQConnectionFactory(BROKER_URL);
            Connection connection = connectionFactory.createConnection(BROKER_USERNAME, BROKER_PASSWORD);
            connection.start();
            
            Session mainSession = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);


            ////////////////
            // Create the source topic, the producer and the bridge consumer
            //
            // Create the source topic
            Topic srcTopic = mainSession.createTopic(AMQ_SRC_TOPIC_NAME);

            // Create the bridge subscriber (consumer) to the Topic
            // - This needs to be created before the producer in order to
            //   receive the published messages
            MessageConsumer bridgeTopicConsumer = mainSession.createConsumer(srcTopic);

            // Create the producer that publishes messages to the source topic
            MessageProducer srcTopicProducer = mainSession.createProducer(srcTopic);
            srcTopicProducer.setDeliveryMode(DeliveryMode.PERSISTENT);

            LOGGER.info("\n\nPublish " + DemoUtils.MAX_MSG_TO_PRODUCE + " messages to the source topic " +
                    "(name: " + srcTopic.getTopicName() + ") ...\n");
            ArrayList<TextMessage> textMessages = DemoUtils.createMessage(mainSession, DemoUtils.MAX_MSG_TO_PRODUCE);
            for (int i = 0; i < DemoUtils.MAX_MSG_TO_PRODUCE; i++) {
                TextMessage textMessage = textMessages.get(i);
                LOGGER.info(" >>> [" + Thread.currentThread().getName() + "] " +
                        "[source topic producer] (Topic: " + srcTopic.getTopicName() + "); " +
                        "Message sent: [JMSXGroupID - " + textMessage.getStringProperty("JMSXGroupID") + "] " + textMessage.getText());
                srcTopicProducer.send(textMessage);
            }


            ////////////////
            // The bridge consumer receives the messages from the source topic
            // and send them to the corresponding destination queues based on the
            // message category property value
            //
            // Create a series of destQueues, corresponding producers, destQueue consumers, and destQueue consumer sessions
            // Each queue corresponds to a specific message category.
            Map<String, MessageProducer> destQueueProducers = new HashMap<>();
            Map<String, Queue> destQueues = new HashMap<>();
            Map<String, Session> destQueueConsumerSessions = new HashMap<>();
            Map<String, MessageConsumer> destQueueConsumers = new HashMap<>();

            for (int i = 0; i < DemoUtils.MAX_MSG_CAT_COUNT; i++) {
                Queue queue = mainSession.createQueue(AMQ_DEST_QUEUE_NAM_BASE + "_" + (i+1));
                destQueues.put(DemoUtils.getCatIdStr(i+1), queue);

                MessageProducer destQueueProducer = mainSession.createProducer(queue);
                destQueueProducer.setDeliveryMode(DeliveryMode.PERSISTENT);
                destQueueProducers.put(DemoUtils.getCatIdStr(i+1), destQueueProducer);

                Session destQueueConsumerSession = connection.createSession(false, Session.CLIENT_ACKNOWLEDGE);
                destQueueConsumerSessions.put(DemoUtils.getCatIdStr(i+1), destQueueConsumerSession);

                MessageConsumer destQueueConsumer = destQueueConsumerSession.createConsumer(queue);
                destQueueConsumers.put(DemoUtils.getCatIdStr(i+1), destQueueConsumer);
            }

            for (int i = 0; i < DemoUtils.MAX_MSG_TO_PRODUCE; i++) {
                Message msgRecved = bridgeTopicConsumer.receive(1000);

                if (msgRecved != null) {
                    msgRecved.acknowledge();

                    String msgCatId = msgRecved.getStringProperty("JMSXGroupID");

                    MessageProducer destQueueProducer = destQueueProducers.get(msgCatId);
                    destQueueProducer.send(msgRecved);
                }
            }


            ////////////////
            // Create a series of destination queue consumer thread to receive messages from
            // the corresponding queue (each queue corresponds to one particular message identifier)
            //
            LOGGER.info("\n\nLaunch \"destQueue\" consumer threads to receive messages per message category ...\n");

            ExecutorService executor = Executors.newFixedThreadPool(DemoUtils.MAX_MSG_CAT_COUNT);

            for (int i = 0; i < DemoUtils.MAX_MSG_CAT_COUNT; i++) {
                DestQueueConsumer destQueueConsumer =
                        new DestQueueConsumer(connection,
                                destQueueConsumerSessions.get(DemoUtils.getCatIdStr(i+1)),
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
            LOGGER.info("\n\nClean up JMS resources ...\n\n");

            // -- destQueue consumers
            for (MessageConsumer destQueueConsumer : destQueueConsumers.values()) {
                destQueueConsumer.close();
            }

            // -- sessions for destQueue consumers
            for (Session destQueueConsumerSession : destQueueConsumerSessions.values()) {
                destQueueConsumerSession.close();
            }

            // -- producers for destQueues
            for (MessageProducer destQueueProducer: destQueueProducers.values()) {
                destQueueProducer.close();
            }

            // -- bridgeConsumer to srcTopic
            bridgeTopicConsumer.close();

            // -- producer to srcTopic
            srcTopicProducer.close();

            // -- main session and connection
            mainSession.close();
            connection.close();
        }
        catch (Exception e) {
            LOGGER.info("Exception caught: " + e);
            e.printStackTrace();
        }

        System.exit(0);
    }
}
