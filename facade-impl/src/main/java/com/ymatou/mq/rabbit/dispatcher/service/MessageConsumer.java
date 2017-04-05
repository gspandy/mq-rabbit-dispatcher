package com.ymatou.mq.rabbit.dispatcher.service;

import com.rabbitmq.client.*;
import com.ymatou.mq.infrastructure.model.Message;
import com.ymatou.mq.rabbit.RabbitChannelFactory;
import com.ymatou.mq.rabbit.config.RabbitConfig;
import com.ymatou.mq.rabbit.support.ChannelWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * rabbit consumer
 * Created by zhangzhihua on 2017/4/1.
 */
public class MessageConsumer {

    private static final Logger logger = LoggerFactory.getLogger(MessageConsumer.class);

    /**
     * 应用id
     */
    private String appId;

    /**
     * 队列code
     */
    private String queueCode;

    /**
     * rabbit配置
     */
    private RabbitConfig rabbitConfig;

    /**
     * master集群通道
     */
    private Channel masterChannel;

    /**
     * slave集群通道
     */
    private Channel slaveChannel;

    private DispatchCallbackService dispatchCallbackService;

    public MessageConsumer(String appId, String queueCode){
        this.appId = appId;
        this.queueCode = queueCode;
        this.init();
    }

    /**
     * 初始化channel
     */
    public void init(){
        //TODO 根据配置启动master/slave rabbit监听
        //TODO 创建conn指定线程池数量
        //TODO 可调整conn/channel对应的数量关系
        ChannelWrapper channelWrapper = RabbitChannelFactory.getChannelWrapper(rabbitConfig);
        masterChannel = channelWrapper.getChannel();
        //TODO slave channel
    }

    /**
     * 启动消费监听
     */
    public void start(){
        try {
            masterChannel.basicConsume(this.queueCode,false,new ConumserHandler());
        } catch (IOException e) {
            logger.error("basic consume error.",e);
        }
    }

    /**
     * 关闭消费监听
     */
    public void stop(){
        //TODO
    }

    /**
     * 消息处理
     */
    class ConumserHandler implements Consumer {

        @Override
        public void handleDelivery(String consumerTag, Envelope envelope, AMQP.BasicProperties properties, byte[] body) throws IOException {
            Message message = new Message();

            try {
                dispatchCallbackService.invoke(message);
            } catch (Exception e) {
                logger.error("dispatch callback message:{} error.",message,e);
            } finally {
                //TODO ack
                masterChannel.basicAck(envelope.getDeliveryTag(),true);
                //TODO slave ack
            }
        }

        @Override
        public void handleCancel(String consumerTag) throws IOException {

        }

        @Override
        public void handleConsumeOk(String consumerTag) {

        }

        @Override
        public void handleCancelOk(String consumerTag) {

        }

        @Override
        public void handleShutdownSignal(String consumerTag, ShutdownSignalException sig) {

        }

        @Override
        public void handleRecoverOk(String consumerTag) {

        }
    }

    public RabbitConfig getRabbitConfig() {
        return rabbitConfig;
    }

    public Channel getMasterChannel() {
        return masterChannel;
    }

    public void setMasterChannel(Channel masterChannel) {
        this.masterChannel = masterChannel;
    }

    public String getQueueCode() {
        return queueCode;
    }

    public void setQueueCode(String queueCode) {
        this.queueCode = queueCode;
    }

    public Channel getSlaveChannel() {
        return slaveChannel;
    }

    public void setSlaveChannel(Channel slaveChannel) {
        this.slaveChannel = slaveChannel;
    }

    public void setRabbitConfig(RabbitConfig rabbitConfig) {
        this.rabbitConfig = rabbitConfig;
    }

    public DispatchCallbackService getDispatchCallbackService() {
        return dispatchCallbackService;
    }

    public void setDispatchCallbackService(DispatchCallbackService dispatchCallbackService) {
        this.dispatchCallbackService = dispatchCallbackService;
    }
}