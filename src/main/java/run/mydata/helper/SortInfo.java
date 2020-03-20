package run.mydata.helper;

import java.util.Arrays;
import java.util.LinkedHashSet;

/**
 * 排序属性信息
 *
 * @author Liu Tao
 */
public class SortInfo {

    private String paramName;

    private boolean isDesc;

    public String getParamName() {
        return paramName;
    }

    public void setParamName(String paramName) {
        this.paramName = paramName;
    }

    public boolean isDesc() {
        return isDesc;
    }

    public void setDesc(boolean isDesc) {
        this.isDesc = isDesc;
    }

    public SortInfo(String pname, boolean isDesc) {
        super();
        this.paramName = pname;
        this.isDesc = isDesc;
    }

    public SortInfo() {
        super();
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + (isDesc ? 1231 : 1237);
        result = prime * result + ((paramName == null) ? 0 : paramName.hashCode());
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
        SortInfo other = (SortInfo) obj;
        if (isDesc != other.isDesc)
            return false;
        if (paramName == null) {
            if (other.paramName != null)
                return false;
        } else if (!paramName.equals(other.paramName))
            return false;
        return true;
    }

    public static LinkedHashSet<SortInfo> getSortInfos(SortInfo... infos) {
        return new LinkedHashSet<>(Arrays.asList(infos));
    }

}
