package com.mydata.dao.base;


import com.mydata.em.StatisticsType;
import com.mydata.helper.OrderBy;
import com.mydata.helper.PageData;
import com.mydata.helper.Param;

import java.io.Serializable;
import java.util.*;

/***
 * DAO通用操作接口
 *
 * @author Liu Tao
 *
 * @param <POJO>
 */
public interface IMyData<POJO> {

    /**
     * 保存
     * @param pojo
     * @return
     */
    Integer save(POJO pojo);

    /**
     * 批量保存
     * @param pojos
     * @return
     */
    Integer saveList(List<POJO> pojos);

    /**
     * 更新
     * @param po
     */
    void update(POJO po);

    /**
     * 更新
     * @param pms 条件 {"id":1}
     * @param mps {"username":"liutao","password":"root"}
     * @return
     */
    Integer update(Set<Param> pms, Map<String, Object> mps);

    /**
     * 查询总记录数
     * @param pms 条件
     * @param distincts   distincts=>COUNT(distincts),  NULL => COUNT(*)
     * @return
     */
    Long getCount(Set<Param> pms, String... distincts);


    /**
     * 根据条件删除
     * @param pms 条件
     * @return
     */
    Integer delete(Set<Param> pms);

    /**
     * 根据主键删除
     * @param id
     * @return
     */
    Integer deleteById(Serializable... id);

    /**
     * 根据条件查询所有记录
     * @param pms 条件
     * @param cls SELECT cls FROM pms ;  NULL=> SELECT * FROM pms
     * @return
     */
    List<POJO> getList(Set<Param> pms, String... cls);

    /**
     * 根据条件查询排序列表
     * @param orderbys  SELECT cls FROM TABLE WHERE pms ORDER BY orderbys
     * @param pms
     * @param cls  NULL=>SELECT * FROM TABLE WHERE pms ORDER BY orderbys
     * @return
     */
    List<POJO> getListAndOrderBy(LinkedHashSet<OrderBy> orderbys, Set<Param> pms, String... cls);

    /**
     * 查询排序列表
     * @param orderbys SELECT cls FROM TABLE ORDER BY orderbys
     * @param cls NULL=>SELECT * FROM TABLE ORDER BY orderbys
     * @return
     */
    List<POJO> getListAndOrderBy(LinkedHashSet<OrderBy> orderbys, String... cls);

    /**
     * 根据主键查询对象
     * @param id  SELECT cls FROM TABLE WHERE ID = id
     * @param cls NULL=>SELECT * FROM TABLE WHERE ID=id
     * @return
     */
    POJO getById(Serializable id, String... cls);

    /**
     * 根据唯一限定条件查询对象  如果有多条返回 null
     * @param propertyName SELECT cls FROM TABLE WHERE propertyName=value
     * @param value
     * @param cls NULL=>SELECT * FROM TABLE WHERE propertyName=value
     * @return
     */
    POJO get(String propertyName, Serializable value, String... cls);


    /**
     * 根据多个限定条件查询对象  如果有多条返回 null
     * @param pms SELECT cls FROM TABLE WHERE pms
     * @param cls NULL=>SELECT * FROM TABLE WHERE pms
     * @return
     */
    POJO get(Set<Param> pms, String... cls);

    /**
     * 根据主键列表查询
     * @param ids SELECT cls FROM TABLE WHERE ID IN (ids)
     * @param cls NULL=>SELECT * FROM TABLE WHERE ID IN (ids)
     * @return
     */
    List<POJO> getListByIds(List<Serializable> ids, String... cls);

    /**
     * 根据字段值列表查询
     * @param propertyName SELECT cls FROM TABLE WHERE propertyName IN (vls)
     * @param vls
     * @param cls NULL=>SELECT * FROM TABLE WHERE propertyName IN (vls)
     * @return
     */
    List<POJO> getList(String propertyName, List<Serializable> vls, String... cls);

    /**
     * 根据条件分页排序查询
     * @param curPage SELECT cls FROM TABLE WHERE pms ORDER BY orderbys LIMIT curPage 1,pageSize 10
     * @param pageSize
     * @param orderbys
     * @param pms
     * @param cls NULL=>SELECT * FROM TABLE WHERE pms ORDER BY orderbys LIMIT curPage 1,pageSize 10
     * @return
     */
    List<POJO> getList(int curPage, int pageSize, LinkedHashSet<OrderBy> orderbys, Set<Param> pms, String... cls);

    /**
     * 分组查询分页列表
     * @param curPage   SELECT  [funs...],[groupbys...] FROM TABLE  WHERE pms GROUP  BY  groupbys ORDER  BY orderbys  LIMIT  0,10;
     * @param pageSize
     * @param orderbys 排序字段名,必须包含在返回的数据之内
     * @param pms
     * @param funs    <函数名,属性名>
     * @param groupby
     * @return [ [funs...,groupbys...] , [...] ]
     */
    List<Object[]> getGroupList(int curPage, int pageSize, LinkedHashSet<OrderBy> orderbys, Set<Param> pms, LinkedHashMap<String, String> funs, String... groupby);

    /**
     * 分组查询总记录数
     * @param pms  SELECT COUNT(*) FROM TABLE WHERE pms GROUP BY groupby
     * @param groupby
     * @return
     */
    Long getGroupbyCount(Set<Param> pms, String... groupby);

    /**
     * 获取分页列表
     * @param curPage SELECT cls FROM TABLE WHERE pms ORDER BY orderbys LIMIT 1,10
     * @param pageSize
     * @param orderbys
     * @param pms
     * @param cls
     * @return
     */
    PageData<POJO> getPageInfo(int curPage, int pageSize, LinkedHashSet<OrderBy> orderbys, Set<Param> pms,String... cls);

    /***
     * 分组分页
     * @param curPage  SELECT  [funs...],[groupbys...] FROM TABLE  WHERE pms GROUP  BY  groupbys ORDER  BY orderbys  LIMIT  0,10;
     * @param pageSize
     * @param orderbys 排序字段名,必须包含在返回的数据之内
     * @param pms      查询条件
     * @param funs     统计函数
     * @param groupby  分组字段不能为空
     * @return 函数在前分组字段在后
     */
    PageData<Object[]> getGroupPageInfo(int curPage, int pageSize, LinkedHashSet<OrderBy> orderbys, Set<Param> pms,LinkedHashMap<String, String> funs, String... groupby);

    /**
     * 使用统计函数
     * @param pms          SELECT functionName(property) FROM TABLE WHERE pms
     * @param property     属性名称
     * @param functionName 函数名 StatisticsType.SUM, MIN, MAX
     * @return
     */
    Double getStatisticsValue(Set<Param> pms, String property, StatisticsType functionName);

    /**
     * 不排序分页查询数据集合 性能好
     * @param pms SELECT cls FROM TABLE WHERE pms LIMIT 1,10
     * @param curPage
     * @param pageSize
     * @param cls NULL=>SELECT * FROM TABLE WHERE pms LIMIT 1,10
     * @return
     */
    List<POJO> getList(Set<Param> pms, int curPage, int pageSize, String... cls);
    /**
     * 分页不排序 性能高，速度快
     * @param pms SELECT cls FROM TABLE WHERE pms LIMT 1,10
     * @param curPage
     * @param pageSize
     * @param cls NULL=> SELECT * FROM TABLE WHERE pms LIMT 1,10
     * @return
     */
    PageData<POJO> getPageInfo(Set<Param> pms, int curPage, int pageSize, String... cls);

    /**
     * 获取属性值列表
     * @param property SELECT property FROM TABLE WHERE
     * @param pms
     * @return
     */
    List<Object> getVlList(String property, Set<Param> pms);

    /**
     * 去重 获取属性值列表
     * @param property SELECT DISTINCT property FROM TABLE WHERE pms
     * @param pms
     * @param isDistinct
     * @return
     */
    List<Object> getVlList(String property, Set<Param> pms, boolean isDistinct);

    /**
     * 去重
     * @param isDistinct  SELECT DISTINCT cls FROM TABLE WHERE pms
     * @param pms
     * @param cls
     * @return
     */
    List<POJO> getList(boolean isDistinct, Set<Param> pms, String... cls);

    /**
     * 获取最小日期时间值
     * @param pms      条件
     * @param dataTypePropertyName 需要统计的日期时间属性名称
     * @return
     */
    Date getMinDate(Set<Param> pms, String dataTypePropertyName);

    /**
     * 获取最大日期时间值
     * @param pms      条件
     * @param dataTypePropertyName 需要统计的日期时间属性名称
     * @return
     */
    Date getMaxDate(Set<Param> pms, String dataTypePropertyName);

    List<POJO> getListFromMater(Set<Param> pms, String... cls);
    Long getCountFromMaster(Set<Param> pms, String... distincts);
    POJO getByIdFromMaster(Serializable id, String... strings);
    POJO getByMaster(String propertyName, Serializable value, String... cls);
    PageData<POJO> getPageInfoFromMaster(int curPage, int pageSize, LinkedHashSet<OrderBy> orderbys, Set<Param> params,String... strings);
    List<Object[]> getGroupListFromMaster(int curPage, int pageSize, LinkedHashSet<OrderBy> orderbys, Set<Param> pms,LinkedHashMap<String, String> funs, String... groupby);
    List<POJO> getListFromMaster(int curPage, int pageSize, LinkedHashSet<OrderBy> orderbys, Set<Param> pms,String... cls);
    void refreshCurrentTables();
    List<POJO> getListFromMaster(Set<Param> pms, int curPage, int pageSize, String... cls);//从主库获取数据 不排序分页查询集合数据 性能最好
    PageData<POJO> getPageInfoFromMaster(Set<Param> pms, int curPage, int pageSize, String... cls);//分页不排序 性能高，速度快
    List<Object> getVlListFromMaster(String property, Set<Param> pms);
    List<Object> getVlListFromMaster(String property, Set<Param> pms, boolean isDistinct);
    List<POJO> getListFromMater(boolean isDistinct, Set<Param> pms, String... cls);
    Long getGroupbyCountFromMaster(Set<Param> pms, String... groupby);
}
