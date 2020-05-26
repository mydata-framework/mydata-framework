package run.mydata.manager;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import run.mydata.annotation.TransactionalOption;

import javax.annotation.Resource;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.Arrays;

@Aspect
public class TransManagerDefault {
    private static Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
    private IConnectionManager connectionManager;

    @Around("@annotation(org.springframework.transaction.annotation.Transactional)")
    public Object transactional(ProceedingJoinPoint pjp) throws Throwable {
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        Method method = signature.getMethod();
        TransactionalOption option = method.getAnnotation(TransactionalOption.class);
        if (option != null && option.connectionManagerNames().length != 0) {//use option connection Manager
            boolean contains = Arrays.asList(option.connectionManagerNames()).contains(connectionManager.getConnectionManagerName());
            try {
                if (contains) {
                    log.debug("begin transaction  {}", Thread.currentThread().getName());
                    Boolean b = connectionManager.beginTransaction(false);
                    Object rz = pjp.proceed();
                    if (b) {
                        log.debug("commit transaction  {}", Thread.currentThread().getName());
                        connectionManager.commitTransaction();
                    }
                    return rz;
                } else {
                    return pjp.proceed();
                }
            } catch (Throwable e) {
                if (contains) {
                    log.debug("rollback transaction  {}", Thread.currentThread().getName());
                    connectionManager.rollbackTransaction();
                }
                throw e;
            }
        } else { //use all
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
    }

    public void setConnectionManager(IConnectionManager connectionManager) {
        this.connectionManager=connectionManager;
    }
}
