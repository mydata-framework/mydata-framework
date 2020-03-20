package run.mydata.exception;

/**
 * Version Optimistic Lock Exception
 *
 * @author Liu Tao
 */
public class ObjectOptimisticLockingFailureException extends RuntimeException{
    public ObjectOptimisticLockingFailureException(){super();};
    public ObjectOptimisticLockingFailureException(String msg){super(msg);};

}
