package com.mydata.manager;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.lang.invoke.MethodHandles;

@Component @Aspect
public class TransManagerDefault {
    private static Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
    private @Resource IConnectionManager connectionManager;

    @Around("@annotation(org.springframework.transaction.annotation.Transactional)")
    public Object transactional(ProceedingJoinPoint pjp) throws Throwable {
        try {
            log.debug("begin transaction  {}", Thread.currentThread().getName());
            Boolean b = connectionManager.beginTransaction(false);
            Object rz = pjp.proceed();
            if (b) {
                log.debug("commit transaction  {}", Thread.currentThread().getName());
                connectionManager.commitTransaction();
            }
            return rz;
        } catch (Throwable e) {
            log.debug("rollback transaction  {}", Thread.currentThread().getName());
            connectionManager.rollbackTransaction();
            throw e;
        }
    }

    public void setConnectionManager(IConnectionManager connectionManager) {
        this.connectionManager=connectionManager;
    }
}
