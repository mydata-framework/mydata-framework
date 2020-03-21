package run.mydata.helper;

/**
 * 并行查询
 *
 * @param <T> .
 * @author Liu Tao
 */
public class QueryVo<T> {

    private String tbn;

    private T ov;

    public String getTbn() {
        return tbn;
    }

    public void setTbn(String tbn) {
        this.tbn = tbn;
    }

    public T getOv() {
        return ov;
    }

    public void setOv(T ov) {
        this.ov = ov;
    }

    public QueryVo(String tbn, T ov) {
        super();
        this.tbn = tbn;
        this.ov = ov;
    }

    public QueryVo() {
        super();
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((tbn == null) ? 0 : tbn.hashCode());
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
        QueryVo other = (QueryVo) obj;
        if (tbn == null) {
            if (other.tbn != null)
                return false;
        } else if (!tbn.equals(other.tbn))
            return false;
        return true;
    }

}
