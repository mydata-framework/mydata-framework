package run.mydata.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 特殊场景写主动指定表字段类型
 * 注意必须保证指定类型与实体类字段兼容, 且并不是所有字段都支持, 例如时间类型不支持 加了@Lob的String类型是不支持的
 * 例如String 默认字段类型是varchar, 可以通过该注解指定为 char, varchar与char同属于String实体类型则兼容
 *
 * @author Liu Tao
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ColumnType {

    /**
     * 表字段类型, 例如char
     *
     * @return .
     */
    String value();
}
