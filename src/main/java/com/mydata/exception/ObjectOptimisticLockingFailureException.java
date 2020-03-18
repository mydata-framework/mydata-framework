package com.mydata.exception;

/**
 * Version Optimistic Lock Exception
 */
public class ObjectOptimisticLockingFailureException extends RuntimeException{
    public ObjectOptimisticLockingFailureException(){super();};
    public ObjectOptimisticLockingFailureException(String msg){super(msg);};

}
