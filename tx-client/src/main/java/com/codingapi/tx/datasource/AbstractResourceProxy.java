package com.codingapi.tx.datasource;


import com.codingapi.tx.aop.bean.TxCompensateLocal;
import com.codingapi.tx.aop.bean.TxTransactionLocal;
import com.codingapi.tx.datasource.service.DataSourceService;
import com.lorne.core.framework.utils.task.Task;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * create by lorne on 2017/8/22
 */

public abstract class AbstractResourceProxy<C,T extends ILCNResource> implements ILCNTransactionControl {


    protected Map<String, T> pools = new ConcurrentHashMap<>();


    private Logger logger = LoggerFactory.getLogger(AbstractResourceProxy.class);


    @Autowired
    protected DataSourceService dataSourceService;


    @Override
    public boolean hasGroup(String group){
        return pools.containsKey(group);
    }


    @Override
    public boolean hasTransaction() {
        return true;
    }


    //default size
    protected volatile int maxCount = 5;

    //default time (seconds)
    protected int maxWaitTime = 30;

    protected volatile int nowCount = 0;

    // not thread
    protected ICallClose<T> subNowCount = new ICallClose<T>() {

        @Override
        public void close(T connection) {
            Task waitTask = connection.getWaitTask();
            if (waitTask != null) {
                if (!waitTask.isRemove()) {
                    waitTask.remove();
                }
            }

            pools.remove(connection.getGroupId());
            nowCount--;
        }
    };

    protected T loadConnection(){
        TxTransactionLocal txTransactionLocal = TxTransactionLocal.current();

        logger.info("loadConnection !");

        if(txTransactionLocal==null||txTransactionLocal.isHasMoreService()){
            return null;
        }
        T old = pools.get(txTransactionLocal.getGroupId());
        if (old != null) {
            old.setHasIsGroup(true);

            txTransactionLocal.setHasIsGroup(true);
            TxTransactionLocal.setCurrent(txTransactionLocal);
            return old;
        }
        return null;
    }

    protected abstract C createLcnConnection(C connection, TxTransactionLocal txTransactionLocal);


    protected abstract void initDbType();

    protected abstract C getRollback(C connection);

    private C createConnection(TxTransactionLocal txTransactionLocal, C connection){
        if (nowCount == maxCount) {
            for (int i = 0; i < maxWaitTime; i++) {
                for(int j=0;j<100;j++){
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    if (nowCount < maxCount) {
                        return createLcnConnection(connection, txTransactionLocal);
                    }
                }
            }
        } else if (nowCount < maxCount) {
            return createLcnConnection(connection, txTransactionLocal);
        } else {
            logger.info("connection was overload");
            return null;
        }
        return connection;
    }



    protected C initLCNConnection(C connection) {
        logger.info("initLCNConnection");
        C lcnConnection = connection;
        TxTransactionLocal txTransactionLocal = TxTransactionLocal.current();

        if (txTransactionLocal != null&&!txTransactionLocal.isHasMoreService()) {

            logger.info("lcn datasource transaction control ");


            //补偿的情况的
            if (TxCompensateLocal.current() != null) {
                logger.info("rollback transaction ");
                return getRollback(connection);
            }

            if(StringUtils.isNotEmpty(txTransactionLocal.getGroupId())){
                if (!txTransactionLocal.isHasStart()) {
                    logger.info("lcn transaction ");
                    return createConnection(txTransactionLocal, connection);
                }
            }


        }
        logger.info("load default connection !");
        return lcnConnection;
    }



    public void setMaxWaitTime(int maxWaitTime) {
        this.maxWaitTime = maxWaitTime;
    }

    public void setMaxCount(int maxCount) {
        this.maxCount = maxCount;
    }



}
