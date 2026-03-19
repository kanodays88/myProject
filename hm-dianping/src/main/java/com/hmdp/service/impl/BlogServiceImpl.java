package com.hmdp.service.impl;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.BlogListToJsonDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.RedisUtil;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;


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

    private static final Long BLOG_LIKE_NUMBER_TTL_TIME = 24*60L;

    private static final String CACHE_BLOG_QUERY_PAGE = "cache:blog:page::";

    private static final String ID_PREFIX = UUID.randomUUID().toString()+"-";

    //创建线程池为10个线程
    private final ExecutorService CACHE_REFRESH_POOL = Executors.newFixedThreadPool(10);

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
        //同步维护的点赞数
        JSONObject data = (JSONObject) cacheData.getData();//由于是复杂类型Blog,实际运行时JSONUtil会解析成JSONObject
        Blog bean = data.toBean(Blog.class);
        //查询缓存中维护的点赞数
        String likeNum = stringRedisTemplate.opsForValue().get(BLOG_CACHE_LIKE_NUMBER_NAME + blogId);
        bean.setLiked(Integer.valueOf(likeNum));
        return Result.ok(bean);
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
            //同步减少redis中维护的点赞数
            stringRedisTemplate.opsForValue().decrement(BLOG_CACHE_LIKE_NUMBER_NAME+id);
            //刷新TTL时间
            stringRedisTemplate.expire(BLOG_CACHE_LIKE_NUMBER_NAME+id,BLOG_LIKE_NUMBER_TTL_TIME, TimeUnit.MINUTES);
            //将blogId发送到消息队列，消费者异步处理数据库中点赞数--
            rabbitTemplate.convertAndSend(id.toString()+",false");
        }
        else{
            //进行点赞
            //添加该用户的点赞记录
            stringRedisTemplate.opsForSet().add(BLOG_CACHE_LIKE_USER_NAME+id,UserHolder.getUser().getId().toString());
            //同步增加redis中的点赞数
            stringRedisTemplate.opsForValue().increment(BLOG_CACHE_LIKE_NUMBER_NAME+id);
            //刷新TTL时间
            stringRedisTemplate.expire(BLOG_CACHE_LIKE_NUMBER_NAME+id,BLOG_LIKE_NUMBER_TTL_TIME, TimeUnit.MINUTES);
            //将blogId发送到消息队列，消费者异步处理数据库中点赞数--
            rabbitTemplate.convertAndSend(id.toString()+",true");
        }


        return Result.ok();
    }

    @Override
    public Result queryHotBlog(Integer current) {
        //获取缓存
        String json = stringRedisTemplate.opsForValue().get(CACHE_BLOG_QUERY_PAGE + current);
        if(json != null && !json.equals("")){
            //不为空，则获取缓存，同时将维护的点赞数替换掉缓存中的点赞数

            //由于这里redisData.data装的是复杂类型（和redisData一样），所以需要二次转化
            //本质上是toBean识别不出内部的复杂类型,默认打成JSONObject
            RedisData redisData = JSONUtil.toBean(json, RedisData.class);
            Object o = redisData.getData();
            BlogListToJsonDTO blogListToJsonDTO = JSONUtil.toBean(JSONUtil.toJsonStr(o), BlogListToJsonDTO.class);
            List<Blog> records = blogListToJsonDTO.getBlogs();
            //判断过不过时
            if(LocalDateTime.now().compareTo(redisData.getExpireTime()) > 0){
                CACHE_REFRESH_POOL.submit(()->{
                    getResult(current);
                });
                return Result.ok(records);
            }
            for(Blog b:records){
                Long id = b.getId();
                //查询缓存中维护的点赞数
                String likeNum = stringRedisTemplate.opsForValue().get(BLOG_CACHE_LIKE_NUMBER_NAME + id);
                if(likeNum == null){
                    //没有维护点赞数，需要查库
                    boolean lockStatus = false;//锁状态
                    try{
                        //获取分布式锁
                        lockStatus = redisUtil.tryLock("lock:" + BLOG_CACHE_LIKE_NUMBER_NAME + id, ID_PREFIX + Thread.currentThread().getId(), 10);
                        if(lockStatus == false){
                            //锁获取失败
                            return Result.fail("请刷新重试");
                        }
                        Blog blog = blogMapper.selectById(id);
                        redisUtil.setValueForRedis(BLOG_CACHE_LIKE_NUMBER_NAME+id,blog.getLiked().toString(),BLOG_LIKE_NUMBER_TTL_TIME, TimeUnit.MINUTES);
                        likeNum = blog.getLiked().toString();
                    }finally {
                        if(lockStatus == true){
                            redisUtil.deleteLock("lock:" + BLOG_CACHE_LIKE_NUMBER_NAME + id, ID_PREFIX + Thread.currentThread().getId());
                        }
                    }
                }
                //替代缓存中的点赞数
                b.setLiked(Integer.valueOf(likeNum));
            }
            return Result.ok(records);
        }

        List<Blog> blogs = getResult(current);
        if(blogs == null) return Result.fail("请重试");
        else return Result.ok(blogs);
    }

    /**
     * 方法提取，blog页面的分页查询
     * @param current
     * @return
     */
    private @NonNull List<Blog> getResult(Integer current) {
        //获取分布式锁
        boolean lockStatus = false;
        try{
            lockStatus = redisUtil.tryLock("lock:"+CACHE_BLOG_QUERY_PAGE+ current,ID_PREFIX + Thread.currentThread().getId(), 10);
            if(lockStatus == false){
                //有人获取锁了，
                return null;
            }
            //分页查询并按点赞数递减
            Page<Blog> page = new Page<>(current,SystemConstants.MAX_PAGE_SIZE);
            LambdaQueryWrapper<Blog> wrapper = new LambdaQueryWrapper<>();
            wrapper.orderByDesc(Blog::getLiked);
            Page<Blog> blogPage = blogMapper.selectPage(page, wrapper);

            // 获取当前页数据
            List<Blog> records = page.getRecords();

            for(Blog b:records){
                Long userId = b.getUserId();
                User user = userMapper.selectById(userId);
                b.setIcon(user.getIcon());
                b.setName(user.getNickName());
            }
            //存入缓存
            BlogListToJsonDTO blogListToJsonDTO = new BlogListToJsonDTO();
            blogListToJsonDTO.setBlogs(records);
            redisUtil.setValueForRedis(CACHE_BLOG_QUERY_PAGE+ current,blogListToJsonDTO,LocalDateTime.now().plusMinutes(10));

            return records;
        }finally {
            if(lockStatus == true){
                redisUtil.deleteLock("lock:"+CACHE_BLOG_QUERY_PAGE+ current,ID_PREFIX + Thread.currentThread().getId());
            }
        }
    }
}
