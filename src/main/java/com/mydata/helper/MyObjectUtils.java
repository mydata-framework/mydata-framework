package com.mydata.helper;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * 对象封装
 *
 * @author Liu Tao
 */
public interface MyObjectUtils {
    /**
     * 封装对象
     *
     * @param os        对象属性值
     * @param propertys 属性名称
     * @param clazz     对象类型
     * @return
     */
    public static <T> List<T> encapsulation(List<Object[]> os, String[] propertys, Class<T> clazz) {
        List<T> ts = new ArrayList<>();
        if (os != null && os.size() > 0 && propertys != null && propertys.length > 0 && clazz != null
                && clazz != Object.class && os.get(0).length == propertys.length) {

            try {
                Field[] fds = clazz.getDeclaredFields();
                for (Object[] objs : os) {
                    T t = clazz.newInstance();
                    for (int i = 0; i < propertys.length; i++) {
                        for (Field fd : fds) {
                            if (fd.getName().equals(propertys[i])) {
                                setObjectValue(fd, objs[i], t);
                                break;
                            }
                        }
                    }
                    ts.add(t);
                }
            } catch (Exception e) {

                e.printStackTrace();
                throw new IllegalArgumentException(e);
            }

        }

        return ts;
    }

    /**
     * 设置对象属性的值
     *
     * @param property 属性名称
     * @param ov       值
     * @param obj      目标对象
     * @throws IllegalArgumentException
     * @throws IllegalAccessException
     * @throws NoSuchFieldException
     * @throws SecurityException
     */
    public static <T> void setObjectValue(String property, Object ov, T obj)
            throws IllegalArgumentException, IllegalAccessException, NoSuchFieldException, SecurityException {
        setObjectValue(obj.getClass().getDeclaredField(property), ov, obj);
    }

    /**
     * 设置对象属性字段的值
     *
     * @param fd  属性字段
     * @param ov  值
     * @param obj 目标对象
     * @throws IllegalArgumentException
     * @throws IllegalAccessException
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static <T> void setObjectValue(Field fd, Object ov, T obj) throws IllegalArgumentException, IllegalAccessException {
        if (obj != null && fd != null) {
            fd.setAccessible(true);
            if (ov == null) {
                fd.set(obj,ov);
            }else {
                if (fd.getType().isEnum()) {
                    Class<Enum> cls = (Class<Enum>) fd.getType();
                    if (ov instanceof Number) {
                        Enum[] ccs = (Enum[]) fd.getType().getEnumConstants();
                        fd.set(obj, Enum.valueOf(cls, ccs[Number.class.cast(ov).intValue()].name()));
                    } else if (ov instanceof String) {
                        fd.set(obj, Enum.valueOf(cls, ov.toString()));
                    } else {
                        fd.set(obj, ov);
                    }
                } else {
                    if (ov.getClass() == BigDecimal.class && fd.getType() != BigDecimal.class) {
                        BigDecimal bdov = (BigDecimal) ov;
                        if (fd.getType() == Boolean.class) {
                            if (bdov.byteValue() == 1) {
                                fd.set(obj, true);
                            } else {
                                fd.set(obj, false);
                            }
                        } else {
                            setNumberValue(fd, obj, bdov);
                        }
                    } else if (ov.getClass() == BigInteger.class && fd.getType() != BigInteger.class) {
                        BigInteger bdov = (BigInteger) ov;
                        setNumberValue(fd, obj, bdov);
                    } else {
                        if (fd.getType() == Time.class && ov.getClass() == Timestamp.class) {
                            Timestamp tmst = (Timestamp) ov;
                            fd.set(obj, new Time(tmst.getTime()));
                        } else {
                            if (ov instanceof Clob) {
                                Clob clob = (Clob) ov;
                                try (Reader crd = clob.getCharacterStream();) {
                                    char[] cbuf = new char[(int) clob.length()];
                                    crd.read(cbuf);
                                    fd.set(obj, new String(cbuf));
                                } catch (IOException | SQLException e) {
                                    throw new IllegalStateException(e);
                                }
                            } else if (ov instanceof Blob) {
                                Blob blob = (Blob) ov;
                                try (InputStream bstream = blob.getBinaryStream();) {
                                    byte[] bts = new byte[(int) blob.length()];
                                    bstream.read(bts);
                                    fd.set(obj, bts);
                                } catch (IOException | SQLException e) {
                                    throw new IllegalStateException(e);
                                }
                            } else {
                                fd.set(obj, ov);
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * 设置数字类型字段的值
     *
     * @param fd
     * @param obj
     * @param bdov
     */
    public static <T> void setNumberValue(Field fd, T obj, Number bdov) {
        if (bdov != null) {
            fd.setAccessible(true);
            try {
                if (fd.getType() == Long.class) {
                    fd.set(obj, bdov.longValue());
                } else if (fd.getType() == Integer.class) {
                    fd.set(obj, bdov.intValue());
                } else if (fd.getType() == Float.class) {
                    fd.set(obj, bdov.floatValue());
                } else if (fd.getType() == Double.class) {
                    fd.set(obj, bdov.doubleValue());
                } else if (fd.getType() == Short.class) {
                    fd.set(obj, bdov.shortValue());
                } else {
                    if (fd.getType() == BigInteger.class && bdov instanceof BigDecimal) {
                        fd.set(obj, ((BigDecimal) bdov).toBigInteger());
                    } else {
                        fd.set(obj, bdov);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
                throw new IllegalStateException(e);
            }
        }
    }

}
