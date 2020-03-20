package run.mydata.manager;

import run.mydata.annotation.MyTransaction;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Resource;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * 事物管理器
 *
 * @author Liu Tao
 */
@Aspect
public class TransManager {
    private static Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
    private @Resource IConnectionManager connectionManager;

    @Around("@annotation(run.mydata.annotation.MyTransaction)")
    public Object transactional(ProceedingJoinPoint pjp) throws Throwable {

        MyTransaction transaction = getTransaction(pjp);

        IConnectionManager ccm = getCurrentConnectionManager(transaction);
        log.debug("currentconnectionmanager  is {}", ccm.toString());
        try {
            boolean readOnly = false;
            if (transaction != null) {
                readOnly = transaction.readOnly();
            }
            log.debug("begin transaction  {}", Thread.currentThread().getName());
            boolean b = ccm.beginTransaction(readOnly);
            Object rz = pjp.proceed();
            if (b) {
                log.debug("commit transaction  {}", Thread.currentThread().getName());
                ccm.commitTransaction();
            }
            return rz;
        } catch (Throwable e) {
            log.debug("rollback transaction  {}", Thread.currentThread().getName());
            ccm.rollbackTransaction();
            throw e;
        }
    }

    private IConnectionManager getCurrentConnectionManager(MyTransaction transaction) {
        log.debug("connectionmanager is {}", connectionManager.toString());
        if (transaction != null && transaction.connectionManager() != null
                && transaction.connectionManager().trim().length() > 0) {
            log.debug(transaction.connectionManager());
            return connectionManagers.getOrDefault(transaction.connectionManager().trim(), connectionManager);
        } else {
            return connectionManager;
        }
    }

    private MyTransaction getTransaction(ProceedingJoinPoint pjp) {
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        Method method = signature.getMethod();
        MyTransaction myAnnotation = method.getAnnotation(MyTransaction.class);
        if (myAnnotation != null) {
            // cglib
            log.debug("proxyTargetClass:{}", true);
            return myAnnotation;
        } else {
            try {
                // jdk PROXY
                Method tgmethod = pjp.getTarget().getClass().getMethod(method.getName(), method.getParameterTypes());
                if (tgmethod != null) {
                    myAnnotation = tgmethod.getAnnotation(MyTransaction.class);
                    log.debug("proxyTargetClass:{}", false);
                    return myAnnotation;
                } else {
                    log.error("target method  is null ");
                    return null;
                }
            } catch (NoSuchMethodException | SecurityException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    public void setConnectionManager(IConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }

    public TransManager(IConnectionManager connectionManager) {
        super();
        this.connectionManager = connectionManager;
    }

    public void setConnectionManagers(Map<String, IConnectionManager> connectionManagers) {
        this.connectionManagers = connectionManagers;
    }

    private Map<String, IConnectionManager> connectionManagers = new HashMap<>();

    public Map<String, IConnectionManager> put(String name, IConnectionManager connectionManager) {
        connectionManagers.put(name.trim(), connectionManager);
        return connectionManagers;
    }

    public TransManager() {
        super();
    }

}
