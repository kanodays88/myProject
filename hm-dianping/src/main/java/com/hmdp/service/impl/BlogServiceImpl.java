package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.RedisUtil;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@Slf4j
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {


    @Autowired
    private BlogMapper blogMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RedisUtil redisUtil;

    private static final String BLOG_CACHE_NAME = "cache:blog::";


    private static final String BLOG_CACHE_LIKE_USER_NAME = "cache:blog:likeUser::blog_";

    private static final String BLOG_CACHE_LIKE_NUMBER_NAME = "cache:blog:likeNumber::blog_";

    @Override
    public Result queryBlogById(Long blogId) {
        //获取缓存
        RedisData cacheData = redisUtil.getValueTime(BLOG_CACHE_NAME, blogId, blogMapper);
        if(cacheData == null){
            Blog blog = blogMapper.selectById(blogId);
            User user = userMapper.selectById(blog.getUserId());
            blog.setUserId(user.getId());
            blog.setName(user.getNickName());
            blog.setIcon(user.getIcon());
            boolean cacheStatus = redisUtil.setValueForRedis(BLOG_CACHE_NAME + blogId, blog, LocalDateTime.now().plusMinutes(3));
            if(cacheStatus == false){
                log.info("缓存:{}添加失败",BLOG_CACHE_NAME+blogId);
                return Result.fail("查看失败");
            }
            return Result.ok(blog);
        }
        return Result.ok(cacheData.getData());
    }

    @Override
    public Result likeBlog(Long id) {
        //实现思路，在redis中存储blog点赞过的用户集合,维护redis缓存中点赞的数量，异步同步给数据库

        //判断该用户是否点赞过
        Boolean likeStatus = stringRedisTemplate.opsForSet().isMember(BLOG_CACHE_LIKE_USER_NAME + id, UserHolder.getUser().getId().toString());
        if(likeStatus == true){
            //取消点赞
            //删除该次点赞记录
            stringRedisTemplate.opsForSet().remove(BLOG_CACHE_LIKE_USER_NAME + id, UserHolder.getUser().getId().toString());
            //同步减少redis中维护的点赞数,使用lua脚本

            //将blogId发送到消息队列，消费者异步处理点赞数--
            rabbitTemplate.convertAndSend(id.toString());

        }


        return null;
    }
}
