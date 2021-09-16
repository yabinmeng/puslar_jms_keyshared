package com.example.activemq;

import com.example.util.DemoUtils;

import javax.jms.*;
import java.util.concurrent.atomic.AtomicInteger;

public class AmqJmsDemo {

    static volatile AtomicInteger destMsgRecvedCntAtomic = new AtomicInteger(0);

    // Source topic name
    static final String AMQ_SRC_TOPIC_NAME = "AMQ_SRC_TOPIC";

    // Destination queue name prefix
    static final String AMQ_DEST_QUEUE_NAM_BASE = "AMQ_DEST_QUEUE";

    // ActiveMQ broker URL
    static final String BROKER_URL = "tcp://" + DemoUtils.TEST_SRV_IP + ":61616";
    static final String BROKER_USERNAME = "admin";
    static final String BROKER_PASSWORD = "myadmin123!";

    static {
        AtomicInteger destMsgRecvedCntAtomic = new AtomicInteger(0);
    }
}
