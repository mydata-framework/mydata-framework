package run.mydata.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 扩展长度
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ColumnMoreLength {

    /**
     * example: "8,2" use for DECIMAL(8,2)
     * @return .
     */
    String length();

}
