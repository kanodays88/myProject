package com.hmdp;


import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Blog;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.RedisUtil;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;

@SpringBootTest
public class MyRabbitMQTest {

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RedisUtil redisUtil;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

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




    @Test
    public void test1(){
        Blog blog = new Blog();
        redisUtil.setValueForRedis("111",blog, LocalDateTime.now().plusMinutes(3));

        String s = stringRedisTemplate.opsForValue().get("111");
        RedisData bean = JSONUtil.toBean(s, RedisData.class);
        Blog data = (Blog) bean.getData();

    }

}
