package com.ymatou.mq.rabbit.dispatcher.service;

import com.ymatou.mq.infrastructure.model.*;
import com.ymatou.mq.infrastructure.service.MessageConfigService;
import com.ymatou.mq.infrastructure.service.MessageService;
import com.ymatou.mq.infrastructure.support.enums.CallbackFromEnum;
import com.ymatou.mq.infrastructure.support.enums.CompensateFromEnum;
import com.ymatou.mq.infrastructure.support.enums.CompensateStatusEnum;
import com.ymatou.mq.infrastructure.support.enums.DispatchStatusEnum;
import com.ymatou.mq.rabbit.dispatcher.support.AdjustableSemaphore;
import org.apache.http.HttpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.Date;
import java.util.List;

/**
 * 消息分发callack service
 * Created by zhangzhihua on 2017/4/1.
 */
@Component
public class DispatchCallbackService {

    private static final Logger logger = LoggerFactory.getLogger(DispatchCallbackService.class);

    private AdjustableSemaphore semaphore;

    @Autowired
    private MessageConfigService messageConfigService;

    @Autowired
    private MessageService messageService;

    /**
     * 回调处理
     * @param message
     */
    public void invoke(Message message){
        List<CallbackConfig> callbackConfigList = messageConfigService.getCallbackConfigList(message.getAppId(),message.getQueueCode());
        if(CollectionUtils.isEmpty(callbackConfigList)){
            logger.error("appId:{},queueCode:{} not exist subscribler.",message.getAppId(),message.getQueueCode());
            return;
        }

        for(CallbackConfig callbackConfig:callbackConfigList){
            try {
                doInvokeOne(message,callbackConfig,null);
            } catch (InterruptedException e) {
                //TODO 异常处理&返回值
                logger.error("invoke one error.",e);
            }
        }

    }

    /**
     * 调用一个订阅者
     * @param message
     * @param callbackConfig
     */
    void doInvokeOne(Message message,CallbackConfig callbackConfig,Long timeout) throws InterruptedException {
        //TODO 信号量处理
        if (semaphore != null) {
            if (timeout != null) {
                semaphore.tryAcquire(timeout);
            } else {
                semaphore.acquire();
            }
        }

        //async http send
        new AsyncHttpInvokeService(message,callbackConfig,this).send();
    }

    /**
     *
     * @param message
     * @param callbackConfig
     */
    public void onInvokeSuccess(Message message,CallbackConfig callbackConfig,HttpResponse result){
        //TODO 更新分发明细状态
        CallbackResult callbackResult = this.buildCallbackResult(message,callbackConfig,result);
        messageService.updateDispatchDetail(callbackResult);
    }

    /**
     *
     * @param message
     * @param callbackConfig
     */
    public void onInvokeFail(Message message,CallbackConfig callbackConfig,Exception ex){
        boolean isNeedInsertCompensate = this.isNeedInsertCompensate(callbackConfig);
        if(isNeedInsertCompensate){//若需要插补单
            //TODO 插补单
            MessageCompensate messageCompensate = this.buildCompensate(message,callbackConfig);
            messageService.insertCompensate(messageCompensate);
            //更新分发明细状态
            CallbackResult callbackResult = this.buildCallbackResult(message,callbackConfig,ex,true);
            messageService.updateDispatchDetail(callbackResult);
        }else{//若不需要插补单
            //更新分发明细状态
            CallbackResult callbackResult = this.buildCallbackResult(message,callbackConfig,ex,false);
            messageService.updateDispatchDetail(callbackResult);
        }
    }

    /**
     * 判断是否需要插补单记录，开启配置&开启消息存储&开启补单
     * @param callbackConfig
     * @return
     */
    boolean isNeedInsertCompensate(CallbackConfig callbackConfig){
        //若队列配置、回调配置开启
        if(callbackConfig.getQueueConfig().getEnable() && callbackConfig.getEnable()){
            //若队列配置开启消息存储
            if(callbackConfig.getQueueConfig().getEnableLog()){
                //若开启补单
                if(callbackConfig.getIsRetry() != null && callbackConfig.getIsRetry() > 0){
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 构造回调结果
     * @param message
     * @param callbackConfig
     * @param result
     * @return
     */
    CallbackResult buildCallbackResult(Message message,CallbackConfig callbackConfig,HttpResponse result){
        CallbackResult callbackResult = new CallbackResult();
        callbackResult.setAppId(message.getAppId());
        callbackResult.setQueueCode(message.getQueueCode());
        callbackResult.setConsumerId(callbackConfig.getCallbackKey());
        callbackResult.setMsgId(message.getId());
        //调用来源
        callbackResult.setFrom(CallbackFromEnum.DISPATCH.ordinal());
        //调用url
        callbackResult.setUrl(callbackConfig.getUrl());
        //调用请求报文
        callbackResult.setRequest(message.getBody());
        //调用响应报文
        callbackResult.setResponse("");
        //调用开始时间
        callbackResult.setReqTime(null);
        //调用结束时间
        callbackResult.setRespTime(new Date());
        //调用结果
        if(result != null && result.getStatusLine() != null && result.getStatusLine().getStatusCode() == 200){
            callbackResult.setResult(DispatchStatusEnum.SUCCESS.ordinal());
        }else{
            callbackResult.setResult(DispatchStatusEnum.FAIL.ordinal());
        }
        return callbackResult;
    }

    /**
     * 构造回调结果
     * @param message
     * @param callbackConfig
     * @param ex
     * @param isNeedCompensate
     * @return
     */
    CallbackResult buildCallbackResult(Message message,CallbackConfig callbackConfig,Exception ex,boolean isNeedCompensate){
        CallbackResult callbackResult = new CallbackResult();
        //调用来源
        callbackResult.setFrom(CallbackFromEnum.DISPATCH.ordinal());
        //调用url
        callbackResult.setUrl(callbackConfig.getUrl());
        //调用请求报文
        callbackResult.setRequest(message.getBody());
        //调用响应报文
        callbackResult.setResponse("");
        //调用开始时间
        callbackResult.setReqTime(null);
        //调用结束时间
        callbackResult.setRespTime(new Date());
        //调用结果
        if(isNeedCompensate){
            callbackResult.setResult(DispatchStatusEnum.COMPENSATE.ordinal());
        }else{
            callbackResult.setResult(DispatchStatusEnum.FAIL.ordinal());
        }
        return callbackResult;
    }

    /**
     * 构造补单
     * @param message
     * @param callbackConfig
     * @return
     */
    MessageCompensate buildCompensate(Message message,CallbackConfig callbackConfig){
        //TODO
        MessageCompensate messageCompensate = new MessageCompensate();
        messageCompensate.setId(this.buildCompensateId(message,callbackConfig));
        messageCompensate.setAppId(message.getAppId());
        messageCompensate.setQueueCode(message.getQueueCode());
        messageCompensate.setBizId(message.getBizId());
        messageCompensate.setSource(CompensateFromEnum.DISPATCH.ordinal());
        messageCompensate.setStatus(CompensateStatusEnum.INIT.ordinal());
        //TODO 时间
        messageCompensate.setCreateTime(new Date());
        //TODO 次数
        return messageCompensate;
    }

    /**
     * 生成补单id TODO
     * @param message
     * @param callbackConfig
     * @return
     */
    String buildCompensateId(Message message,CallbackConfig callbackConfig){
        return String.format("%s_%s",message.getId(),callbackConfig.getCallbackKey());
    }

}
