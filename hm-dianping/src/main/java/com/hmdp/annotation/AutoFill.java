package com.hmdp.annotation;


import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)  //标记此注解是用在方法上的
@Retention(RetentionPolicy.RUNTIME)  //标记此注解声明周期，只要在runtime就存在
public @interface AutoFill {
    String value();//需要指定OperationType类型的值
}
