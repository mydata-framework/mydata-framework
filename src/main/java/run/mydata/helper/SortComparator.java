package run.mydata.helper;

import java.lang.reflect.Field;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashSet;

/**
 * 排序操作
 *
 * @param <T>
 * @author Liu Tao
 */
public class SortComparator<T> implements Comparator<T> {
    private LinkedHashSet<SortInfo> sorts;

    @Override
    public int compare(T o1, T o2) {
        int i = 0;
        if (sorts != null && sorts.size() > 0) {
            SortInfo stz = null;
            Iterator<SortInfo> ite = sorts.iterator();
            while (ite.hasNext()) {
                stz = ite.next();
                if (o1.getClass().isArray()) {
                    Object[] os1 = (Object[]) o1;
                    Object[] os2 = (Object[]) o2;
                    Integer idx = Integer.valueOf(stz.getParamName());
                    if (idx >= 0 && idx < os1.length) {
                        i = comparebase(os1[idx], os2[idx], stz.isDesc());
                    }
                    if (i != 0) {
                        break;
                    }
                } else {
                    try {
                        Field fd = o1.getClass().getDeclaredField(stz.getParamName());
                        fd.setAccessible(true);
                        Object v1 = fd.get(o1);
                        Object v2 = fd.get(o2);
                        i = comparebase(v1, v2, stz.isDesc());
                        if (i != 0) {
                            break;
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                        throw new IllegalStateException(e);
                    }
                }
            }
        }
        return i;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private int comparebase(Object v1, Object v2, boolean isDesc) {
        if (v1 != null && v2 != null) {
            Comparable o1 = (Comparable) v1;
            Comparable o2 = (Comparable) v2;
            if (isDesc) {
                return o2.compareTo(o1);
            }
            return o1.compareTo(o2);
        }
        return 0;
    }

    public SortComparator(LinkedHashSet<SortInfo> sorts) {
        super();
        this.sorts = sorts;
    }
}
