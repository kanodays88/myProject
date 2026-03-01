package com.hmdp.utils;

import com.hmdp.dto.UserDTO;

//ThreadLocal是 Java 中的一个线程本地存储工具类，核心作用是：为每个线程提供一份「独立的变量副本」，线程间的变量互不干扰，相当于为线程开辟一段空间，线程内的方法可以存取该空间里面的数据
//ThreadLocal本质只是一个外壳，不存数据，真正的数据存在当前线程（Thread 类）的 threadLocals 成员变量中（这是一个 ThreadLocalMap 类型的哈希表）
//当线程通过 ThreadLocal.set(value) 存数据时，会以 ThreadLocal 实例为 key、要存储的值为 value，存入当前线程的 ThreadLocalMap
//当通过 ThreadLocal.get() 取数据时，会以当前 ThreadLocal 实例为 key，从当前线程的 ThreadLocalMap 中获取对应的副本值
public class UserHolder {
    private static final ThreadLocal<UserDTO> tl = new ThreadLocal<>();

    public static void saveUser(UserDTO user){
        tl.set(user);
    }

    public static UserDTO getUser(){
        return tl.get();
    }

    public static void removeUser(){
        tl.remove();
    }
}
