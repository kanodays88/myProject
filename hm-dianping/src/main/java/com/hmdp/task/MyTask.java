package com.hmdp.task;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.hmdp.entity.Blog;
import com.hmdp.mapper.BlogMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Set;

@Component
@EnableAsync
@Slf4j
public class MyTask {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private BlogMapper blogMapper;

    private static final String BLOG_CACHE_LIKE_NUMBER_NAME = "cache:blog:likeNumber::blog_";
    //存储此时数据库中的blog点赞数
    private static HashMap<Long,Integer> blogLikeNumber = new HashMap<>();
    /**
     * 定时扫描同步redis中的点赞数
     *
     */
    @Scheduled(cron = "*/2 * * * * ?")
    @Async
    public void syncLikeNumber(){
        log.info("线程：{} 开始同步redis点赞数到数据库",Thread.currentThread().getId());
        //批量获取所有以BLOG_CACHE_LIKE_NUMBER_NAME为前缀的key
        Set<String> keys = stringRedisTemplate.keys(BLOG_CACHE_LIKE_NUMBER_NAME + "*");

        //为空则取消执行
        if(keys == null || keys.isEmpty()){
            return;
        }

        for(String key:keys){
            //初始化数据，blogId和点赞数likeNumber
            String likeNumberStr = stringRedisTemplate.opsForValue().get(key);
            String[] strings = key.split("_");
            Long blogId = Long.valueOf(strings[1]);
            Integer likeNumber = Integer.valueOf(likeNumberStr);
            //不为空，且点赞数没变化直接跳过
            if(blogLikeNumber.get(blogId) != null && blogLikeNumber.get(blogId) == likeNumber) continue;

            //为空或者点赞数有变化，修改数据库
            LambdaUpdateWrapper<Blog> wrapper = new LambdaUpdateWrapper<>();
            wrapper.eq(Blog::getId,blogId).set(Blog::getLiked,likeNumber);
            int rows = blogMapper.update(wrapper);
            if(rows <= 0){
                throw new RuntimeException("点赞数同步失败");
            }
            blogLikeNumber.put(blogId,likeNumber);

        }
    }
}
