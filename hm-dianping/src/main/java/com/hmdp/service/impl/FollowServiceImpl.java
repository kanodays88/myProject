package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;


@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    
    private static final String CACHE_FOLLOW_USER = "cache:follow::user";

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserService userService;

    /**
     * 更新Redis中用户关注集合的缓存
     * @param userId 当前用户ID
     * @param followUserId 目标用户ID
     * @param followStatus 关注状态，true表示关注，false表示取关
     */
    private void updateFollowCache(Long userId, Long followUserId, boolean followStatus) {
        String key = CACHE_FOLLOW_USER + userId;
        if (followStatus) {
            stringRedisTemplate.opsForSet().add(key, followUserId.toString());
        } else {
            stringRedisTemplate.opsForSet().remove(key, followUserId.toString());
        }
        stringRedisTemplate.expire(key, 7, TimeUnit.DAYS);
    }
    /**
     * 对目标用户关注或取关
     * @param userId 关注或者取关的目标用
     * @param followStatus 关注或取关的状态，true表示需要关注，false表示需要取关
     * @return
     */
    @Override
    public Result followUser(Long userId, boolean followStatus) {
        Long user = UserHolder.getUser().getId();
        if(followStatus){
            Follow follow = new Follow();
            follow.setUserId(user);
            follow.setFollowUserId(userId);
            follow.setCreateTime(LocalDateTime.now());
            boolean save = save(follow);
            if(!save){
                return Result.fail("关注失败");
            }
            updateFollowCache(user, userId, true);
            return Result.ok();
        }else{
            boolean remove = remove(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Follow>()
                    .eq(Follow::getUserId, user)
                    .eq(Follow::getFollowUserId, userId));
            if(!remove){
                return Result.fail("取关失败");
            }
            updateFollowCache(user, userId, false);
            return Result.ok();
        }
    }

    /**
     * 判断该用户是否被当前登录用户所关注
     * @param userId 该用户
     * @return
     */
    @Override
    public Result isFollow(Long userId) {
        Long currentUserId = UserHolder.getUser().getId();
        Follow one = getOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Follow>()
                .eq(Follow::getUserId, currentUserId)
                .eq(Follow::getFollowUserId, userId));
        return Result.ok(one != null);
    }

    /**
     * 查询当前用户与目标用户的共同关注
     * @param userId 目标用户ID
     * @return 共同关注用户的列表
     */
    @Override
    public Result commonFollow(Long userId) {
        Long currentUserId = UserHolder.getUser().getId();
        String currentKey = CACHE_FOLLOW_USER + currentUserId;
        String targetKey = CACHE_FOLLOW_USER + userId;
        Boolean currentExists = stringRedisTemplate.hasKey(currentKey);
        Boolean targetExists = stringRedisTemplate.hasKey(targetKey);
        // 检查缓存是否存在，不存在则从数据库重建
        if (currentExists == null || !currentExists) {
            rebuildFollowCache(currentUserId);
        }
        if (targetExists == null || !targetExists) {
            rebuildFollowCache(userId);
        }
        // 使用Redis的SINTER命令求两个关注集合的交集
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(currentKey, targetKey);
        if (intersect == null || intersect.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        // 将交集ID转换为Long类型
        List<Long> ids = intersect.stream().map(Long::parseLong).collect(Collectors.toList());
        // 根据用户ID查询用户详细信息并转换为UserDTO
        //解析：.query()创建wrapper条件构造
        //     .in()拼接条件
        //     .list()执行查询，底层实现seletList
        //     .stream()将集合转换成顺序流，函数式for循环
        //     .map(传入方法) 映射转换，根据方法将传入的参数转化为返回的参数
        //     .collect(Collectors.toList())将处理好的顺序流转化为集合，具体为List类型
        List<UserDTO> users = userService.query().in("id", ids).list()
                .stream().map(user -> {
                    UserDTO dto = new UserDTO();
                    dto.setId(user.getId());
                    dto.setNickName(user.getNickName());
                    dto.setIcon(user.getIcon());
                    return dto;
                }).collect(Collectors.toList());
        return Result.ok(users);
    }

    /**
     * 从数据库重建用户关注缓存
     * 当Redis缓存过期或不存在时调用此方法
     * @param userId 需要重建缓存的用户ID
     */
    private void rebuildFollowCache(Long userId) {
        // 从数据库查询该用户的所有关注记录
        List<Follow> follows = query().eq("user_id", userId).list();
        String key = CACHE_FOLLOW_USER + userId;
        if (!follows.isEmpty()) {
            // 将关注用户ID批量写入Redis Set集合
            //解析：follows.stream()将集合转化为顺序流，类似于for循环
            //     .map(传入方法) 将元素映射转换，常使用lambda箭头函数
            //     .toArray(String[]::new)  转化为数组类型，具体类型是String[]
            stringRedisTemplate.opsForSet().add(key, follows.stream()
                    .map(f -> f.getFollowUserId().toString())
                    .toArray(String[]::new));
            // 设置7天过期时间
            stringRedisTemplate.expire(key, 7, TimeUnit.DAYS);
        }
    }


}
