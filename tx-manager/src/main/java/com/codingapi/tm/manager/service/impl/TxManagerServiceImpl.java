package com.codingapi.tm.manager.service.impl;


import com.codingapi.tm.Constants;
import com.codingapi.tm.manager.ModelInfoManager;
import com.codingapi.tm.manager.service.TxManagerSenderService;
import com.codingapi.tm.manager.service.TxManagerService;
import com.codingapi.tm.config.ConfigReader;
import com.codingapi.tm.model.ModelInfo;
import com.codingapi.tm.netty.model.TxGroup;
import com.codingapi.tm.netty.model.TxInfo;
import com.codingapi.tm.redis.service.RedisServerService;
import com.lorne.core.framework.utils.KidUtils;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Created by lorne on 2017/6/7.
 */
@Service
public class TxManagerServiceImpl implements TxManagerService {



    @Autowired
    private ConfigReader configReader;

    @Autowired
    private RedisServerService redisServerService;


    @Autowired
    private TxManagerSenderService transactionConfirmService;



    private Logger logger = LoggerFactory.getLogger(TxManagerServiceImpl.class);


    @Override
    public TxGroup createTransactionGroup(String groupId) {
        TxGroup txGroup = new TxGroup();
        if (StringUtils.isNotEmpty(groupId)) {
            txGroup.setIsCommit(1);
        } else {
            groupId = KidUtils.generateShortUuid();
        }

        txGroup.setStartTime(System.currentTimeMillis());
        txGroup.setGroupId(groupId);

        String key = configReader.getKeyPrefix() + groupId;
        redisServerService.saveTransaction(key, txGroup.toJsonString());

        return txGroup;
    }


    @Override
    public TxGroup addTransactionGroup(String groupId, String taskId, int isGroup, String modelName, String methodStr) {
        String key = configReader.getKeyPrefix() + groupId;
        TxGroup txGroup = redisServerService.getTxGroupByKey(key);
        if (txGroup==null) {
            return null;
        }
        TxInfo txInfo = new TxInfo();
        txInfo.setModelName(modelName);
        txInfo.setKid(taskId);
        txInfo.setAddress(Constants.address);
        txInfo.setIsGroup(isGroup);
        txInfo.setMethodStr(methodStr);


        ModelInfo modelInfo =  ModelInfoManager.getInstance().getModelByChannelName(modelName);
        if(modelInfo!=null) {
            txInfo.setUniqueKey(modelInfo.getUniqueKey());
            txInfo.setModelIpAddress(modelInfo.getIpAddress());
            txInfo.setModel(modelInfo.getModel());
        }

        txGroup.addTransactionInfo(txInfo);

        redisServerService.saveTransaction(key, txGroup.toJsonString());

        return txGroup;
    }



    @Override
    public int cleanNotifyTransaction(String groupId, String taskId) {
        int res = 0;
        logger.info("start-cleanNotifyTransaction->groupId:"+groupId+",taskId:"+taskId);
        String key = configReader.getKeyPrefix() + groupId;
        TxGroup txGroup = redisServerService.getTxGroupByKey(key);
        if (txGroup==null) {
            logger.info("cleanNotifyTransaction - > txGroup is null ");
            return res;
        }

        if(txGroup.getHasOver()==0){
            logger.info("cleanNotifyTransaction - > groupId "+groupId+" not over !");
            return 0;
        }

        //更新数据
        boolean hasSet = false;
        for (TxInfo info : txGroup.getList()) {
            if (info.getKid().equals(taskId)) {
                if(info.getNotify()==0&&info.getIsGroup()==0) {
                    info.setNotify(1);
                    hasSet = true;
                    res = 1;

                    break;
                }
            }
        }

        //判断是否都结束
        boolean isOver = true;
        for (TxInfo info : txGroup.getList()) {
            if (info.getIsGroup() == 0 && info.getNotify() == 0) {
                isOver = false;
                break;
            }
        }

        if (isOver) {
            redisServerService.deleteKey(key);
        }

        //有更新的数据，需要修改记录
        if(!isOver&&hasSet) {
            redisServerService.saveTransaction(key, txGroup.toJsonString());
        }

        logger.info("end-cleanNotifyTransaction->groupId:"+groupId+",taskId:"+taskId+",res(1:commit,0:rollback):"+res);
        return res;
    }


    @Override
    public boolean closeTransactionGroup(String groupId,int state) {
        String key = configReader.getKeyPrefix() + groupId;
        TxGroup txGroup = redisServerService.getTxGroupByKey(key);
        if(txGroup==null){
            return false;
        }
        txGroup.setState(state);
        txGroup.setHasOver(1);
        redisServerService.saveTransaction(key,txGroup.toJsonString());
        return transactionConfirmService.confirm(txGroup);
    }


    @Override
    public void dealTxGroup(TxGroup txGroup, boolean hasOk) {
        if(hasOk) {
            String key = configReader.getKeyPrefix() + txGroup.getGroupId();
            redisServerService.deleteKey(key);
        }
    }


    @Override
    public void deleteTxGroup(TxGroup txGroup) {
        String key = configReader.getKeyPrefix() + txGroup.getGroupId();
        redisServerService.deleteKey(key);
    }



}
