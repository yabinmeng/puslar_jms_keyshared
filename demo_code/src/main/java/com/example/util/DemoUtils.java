package com.example.util;

import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.RandomUtils;

import javax.jms.JMSContext;
import javax.jms.Session;
import javax.jms.TextMessage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class DemoUtils {
    public static final String TEST_SRV_IP = "10.101.32.133";

    // Message category identifier and property key ("JMSXGroupID")
    public static final int MAX_MSG_CAT_COUNT = 3;

    // Maximum number of messages to publish
    public static final int MAX_MSG_TO_PRODUCE = 15;

    // Maximum message payload (text) length
    public static final int MAX_TEXT_MSG_LENGTH = 20;

    public static String getCatIdStr(int i) {
        return "cat_" + String.valueOf(i);
    }

    /**
     * Generate a series of TextMessages. Each TextMessage is associated with a
     * specific message identifier that is in the range of [1, (MAX_MSG_ID_COUNT + 1)]
     * JMS API 1.1
     *
     * @param session: JMS session
     * @param msgNum: total number of messages to generate
     * @return
     */
    public static ArrayList<TextMessage> createMessage(Session session, int msgNum) throws Exception {
        ArrayList<TextMessage> messageArray = new ArrayList<>();

        for (int i=0; i<msgNum; i++) {
            String msgPayload = RandomStringUtils.randomAlphanumeric(DemoUtils.MAX_TEXT_MSG_LENGTH);
            TextMessage message = session.createTextMessage(msgPayload);
            message.setStringProperty("JMSXGroupID", getCatIdStr(RandomUtils.nextInt(1, (DemoUtils.MAX_MSG_CAT_COUNT + 1))));
            messageArray.add(message);
        }

        return messageArray;
    }

    /**
     * Get the count of the messages by "JMSXGroupID" property value
     * @param messages
     * @return
     */
    public static Map<String, Integer> getJMSMsgCntByGroupId(ArrayList<TextMessage> messages) throws Exception {
        HashMap<String, Integer> msgCntMap = new HashMap<>();

        for (TextMessage message : messages) {
            String msgGroupIdVal = message.getStringProperty("JMSXGroupID");
            if (msgGroupIdVal == null) {
                if (!msgCntMap.containsKey("null")) {
                    msgCntMap.put("null", 1);
                }
                else {
                    Integer cnt = msgCntMap.get("null");
                    msgCntMap.put("null", Integer.valueOf(cnt.intValue() + 1));
                }
            }
            else {
                if (!msgCntMap.containsKey(msgGroupIdVal)) {
                    msgCntMap.put(msgGroupIdVal, 1);
                }
                else {
                    Integer cnt = msgCntMap.get(msgGroupIdVal);
                    msgCntMap.put(msgGroupIdVal, Integer.valueOf(cnt.intValue() + 1));
                }
            }
        }

        return  msgCntMap;
    }


    /**
     * Generate a series of TextMessages. Each TextMessage is associated with a
     * specific message identifier that is in the range of [1, (MAX_MSG_ID_COUNT + 1)]
     * JMS API 2.0
     *
     * @param context: JMSContext
     * @param msgNum: total number of messages to generate
     * @return
     */
    public static ArrayList<TextMessage> createMessage(JMSContext context, int msgNum) throws Exception {
        ArrayList<TextMessage> messageArray = new ArrayList<>();

        for (int i=0; i<msgNum; i++) {
            String msgPayload = RandomStringUtils.randomAlphanumeric(DemoUtils.MAX_TEXT_MSG_LENGTH);
            TextMessage message = context.createTextMessage(msgPayload);
            message.setStringProperty("JMSXGroupID", getCatIdStr(RandomUtils.nextInt(1, (DemoUtils.MAX_MSG_CAT_COUNT + 1))));
            messageArray.add(message);
        }

        return messageArray;
    }
}
