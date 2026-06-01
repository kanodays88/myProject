package com.hmdp.config;


import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class RabbitMQConfig {

    public static final String SECKILL_DLX = "seckillVoucherDLX";
    public static final String SECKILL_QUEUE = "seckillVoucherQueue_1";
    public static final String SECKILL_DLQ = "seckillVoucherDLQ";

    @Bean
    public DirectExchange seckillVoucherDLX() {
        //创建死信交换机
        return new DirectExchange(SECKILL_DLX, true, false);
    }

    @Bean
    public Queue seckillVoucherDLQ() {
        //创建死信队列
        return new Queue(SECKILL_DLQ, true);
    }

    @Bean
    public Binding dlqBinding() {
        //通过路由键绑定死信交换机和死信队列
        return BindingBuilder.bind(seckillVoucherDLQ())
                .to(seckillVoucherDLX())
                .with(SECKILL_DLQ);//设置路由键，死信交换机可以通过该路由键找到对应名字的死信队列
    }

    @Bean
    public Queue seckillVoucherQueue() {
        //配置队列额外参数
        Map<String, Object> args = new HashMap<>(2);
        args.put("x-dead-letter-exchange", SECKILL_DLX);//配置死信交换机，当消息变成死信会被转发到该交换机
        args.put("x-dead-letter-routing-key", SECKILL_DLQ);//配置死信路由键，死信交换机可以通过该路由键找到对应名字的死信队列
        return new Queue(SECKILL_QUEUE, true, false, false, args);
    }
}