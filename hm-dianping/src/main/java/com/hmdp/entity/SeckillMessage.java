package com.hmdp.entity;


import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("tb_seckill_message")
public class SeckillMessage implements Serializable {
    private Long id;
    private Long voucherId;
    private Long userId;
    private Long orderId;
    private Integer status;       // 0-待处理 1-已发送 2-已送达 3-已回滚
    private Integer retryCount;
    private String errorMsg;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}