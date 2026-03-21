package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Follow;
import com.baomidou.mybatisplus.extension.service.IService;


public interface IFollowService extends IService<Follow> {

    /**
     * 关注或者取关用户
     * @param userId
     * @param followStatus
     * @return
     */
    Result followUser(Long userId, boolean followStatus);

    Result isFollow(Long userId);
}
