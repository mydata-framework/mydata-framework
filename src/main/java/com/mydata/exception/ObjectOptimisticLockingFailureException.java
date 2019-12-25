package com.mydata.exception;

public class ObjectOptimisticLockingFailureException extends RuntimeException{
    public ObjectOptimisticLockingFailureException(){super();};
    public ObjectOptimisticLockingFailureException(String msg){super(msg);};

}
