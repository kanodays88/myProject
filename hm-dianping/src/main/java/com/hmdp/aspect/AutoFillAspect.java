package com.hmdp.aspect;


/**
 *   指定插入目标方法的位置
 *   前置 @Before
 *   后置 @AfterReturning
 *   异常 @AfterThrowing
 *   最后 @After
 *   环绕 @Around
 */

/**增强方法中获取目标方法的返回信息
 * 获取目标方法的信息  ---  JoinPoint类做参数传入
 * 获取目标方法的返回结果 --  @AfterReturning注解中使用
 *          传参中传入Object实例，@AfterReturning注解中指定  returning属性=“Object实例”  可以接收目标方法返回的参数
 * 获取目标异常信息  ---  @AfterThrowing注解中使用
 *          传参中传入Throwable实例，@AfterThrowing注解中指定  throwing属性=“Throwable实例”  可以接收目标方法返回的异常信息
 */

import com.hmdp.annotation.AutoFill;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.lang.reflect.Method;
import java.time.LocalDateTime;

/**
 * 切点表达式
 *      固定语法 execution(a b c.d.e(f))
 *      a部分. 访问修饰符
 *          public/private
 *      b部分. 方法返回的参数类型
 *          String void int
 *      如果不需要指定访问修饰符和返回值，这两位整合成*
 *      c部分. 包的位置
 *          具体包：com.kanodays88.service.impl
 *          单层模糊：com.kanodays88.service.*  ---  相当于指定service下的所有包
 *          多层模糊：com..impl  ---  相当于指定所有包下的impl包
 *      d部分. 类的名称
 *          具体名称：ServiceImpl
 *          模糊：*   ---   相当于指定该包下的所有类
 *          部分模糊：  *Impl 或者 Service*  ---  相当于匹配所有Impl结尾的所有类，所有Service开头的所有类
 *      e部分. 方法名
 *          同类一样
 *      f部分. 形参参数列表
 *          没有参数： ()
 *          有具体参数： (String,int)
 *          模糊参数： (..)
 *          部分模糊： (String..) 前面指定String后面随意
 *                   (..int)  后面指定int前面随意
 *                   (String..int)  前面String后面int中间随意
 */

@Component
@Aspect //将该类标识为切面供肉容器读取
@Slf4j  //日志输出
public class AutoFillAspect {
    /**
     * 用于处理关于category插入和修改的统一切面操作
     * @param joinPoint
     */
    //@annotation (...) → 匹配带有指定注解的方法
    @Before("execution(* com.hmdp.mapper.*.*(..)) && @annotation(com.hmdp.annotation.AutoFill)")
    public void autoFill(JoinPoint joinPoint){
        log.info("开始进行自动填充切入");
        //获取参数列表
        Object[] args = joinPoint.getArgs();
        Object o = args[0];

        //获取目标方法注解中的值，需要用值判断用什么操作，JoinPoint → Signature → MethodSignature → Method → 注解信息
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();//获取Signature再强转MethodSignature
        AutoFill annotation = signature.getMethod().getAnnotation(AutoFill.class);//通过MethodSignature获取切点切入的目标方法method,再通过指定注解反射获取方法上指定注解的信息

        //初始化需要修改的数据
        LocalDateTime now = LocalDateTime.now();


        //插入操作
        if(annotation.value() == "insert"){
            //初始化创建修改时间，
            try {
                //getClass获取当前运行时对象具体的类，getDeclaredMethod获取指定方法名和对应参数类型的方法
                Method setCreateTime = o.getClass().getDeclaredMethod("setCreateTime", LocalDateTime.class);
                Method setUpdateTime = o.getClass().getDeclaredMethod("setUpdateTime", LocalDateTime.class);

                //invoke规则
                //如果方法是实例方法（非 static）：第一个参数必须是方法所属的对象实例（比如 o）；
                //如果方法是静态方法：第一个参数传 null（因为静态方法属于类，不属于对象）；
                //后续参数：按方法的参数列表顺序传入，数量和类型必须完全匹配。
                setCreateTime.invoke(o,now);//执行o对象的setCreateTime方法，传入now参数
                setUpdateTime.invoke(o,now);
            }catch (Exception e){
                e.printStackTrace();
            }
        }

        //更新操作
        if(annotation.value() == "update"){
            try{
                //还需要修改更新时间和更新人,顺便把原版本号插入
                Method setUpdateTime = o.getClass().getDeclaredMethod("setUpdateTime", LocalDateTime.class);
                setUpdateTime.invoke(o,now);

            }catch(Exception e){
                e.printStackTrace();
            }

        }
    }

}
