package com.hmdp;


import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
public class MyRabbitMQTest {

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Test
    public void testSimpleQueue(){
        //消息队列名字
        String queueName = "simpleQueue_1";
        //交换机名字，绑定了两个消息队列，群发类型的交换机
        String exchangeName = "kanodaysFanout"; //fanout类型交换机可以群发
        //消息
        String msg = "cnmdb";
        //发送到消息队列                           消息队列为空则表示不指定消息队列
        rabbitTemplate.convertAndSend(exchangeName,"",msg);
    }

    //在方法上添加@RabbitListener
    //当Spring项目启动时，会为这些方法添加消费者实例，绑定到线程池中
    //当监听到的消息队列有消息时，线程池会分配空闲线程给消费者实例，执行对应方法




}
