package run.mydata.helper;

import java.util.Arrays;
import java.util.LinkedHashSet;

/**
 * 排序
 *
 * @author Liu Tao
 */
public class OrderBy {
    // 排序的属性
    private String propertyName;
    // 如果以统计函数排序制定函数名称
    private String funName;
    // 是否降序排列
    private Boolean isDesc = true;

    public OrderBy() {
        super();
    }

    public OrderBy(String propertyName) {
        super();
        this.propertyName = propertyName;
        this.isDesc = true;
    }

    public OrderBy(String propertyName, Boolean isDesc) {
        super();
        this.propertyName = propertyName;
        this.isDesc = isDesc;
    }

    public OrderBy(String propertyName, String funName, Boolean isDesc) {
        super();
        this.propertyName = propertyName;
        this.funName = funName;
        this.isDesc = isDesc;
    }

    public String getPropertyName() {
        return propertyName;
    }

    public void setPropertyName(String propertyName) {
        this.propertyName = propertyName;
    }

    public String getFunName() {
        return funName;
    }

    public void setFunName(String funName) {
        this.funName = funName;
    }

    public Boolean getIsDesc() {
        return isDesc;
    }

    public void setIsDesc(Boolean isDesc) {
        this.isDesc = isDesc;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((funName == null) ? 0 : funName.hashCode());
        result = prime * result + ((isDesc == null) ? 0 : isDesc.hashCode());
        result = prime * result + ((propertyName == null) ? 0 : propertyName.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        OrderBy other = (OrderBy) obj;
        if (funName == null) {
            if (other.funName != null)
                return false;
        } else if (!funName.equals(other.funName))
            return false;
        if (isDesc == null) {
            if (other.isDesc != null)
                return false;
        } else if (!isDesc.equals(other.isDesc))
            return false;
        if (propertyName == null) {
            if (other.propertyName != null)
                return false;
        } else if (!propertyName.equals(other.propertyName))
            return false;
        return true;
    }

    public static LinkedHashSet<OrderBy> getOrderBys(OrderBy... bies) {
        return new LinkedHashSet<>(Arrays.asList(bies));
    }

    //缩短代码长度,推荐静态导入 import static run.mydata.helper.Param.*;  import static run.mydata.em.Operate.*; import static run.mydata.helper.OrderBy.*;
    public static LinkedHashSet<OrderBy> os(OrderBy... bies) {
        return new LinkedHashSet<>(Arrays.asList(bies));
    }
    public static OrderBy o(String propertyName){
        return new OrderBy(propertyName);
    }
    public static OrderBy o(String propertyName, Boolean isDesc){
        return new OrderBy(propertyName,isDesc);
    }
    public static OrderBy o(String propertyName, String funName, Boolean isDesc){
        return new OrderBy(propertyName,funName,isDesc);
    }


}
