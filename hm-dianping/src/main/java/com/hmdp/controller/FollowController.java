package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.entity.Follow;
import com.hmdp.service.IFollowService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;


@RestController
@RequestMapping("/follow")
@Slf4j
public class FollowController {

    @Autowired
    private IFollowService followService;

    @PutMapping("/{id}/{status}")
    public Result followUser(@PathVariable("id") Long userId,@PathVariable("status") boolean followStatus){
        Result r = followService.followUser(userId,followStatus);
        return r;
    }

    @GetMapping("/or/not/{id}")
    public Result isFollow(@PathVariable("id") Long userId){
        Result r = followService.isFollow(userId);
        return r;
    }

}
