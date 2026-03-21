package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.UserHolder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;


@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {


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
            return Result.ok();
        }else{
            boolean remove = remove(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Follow>()
                    .eq(Follow::getUserId, user)
                    .eq(Follow::getFollowUserId, userId));
            if(!remove){
                return Result.fail("取关失败");
            }
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


}
