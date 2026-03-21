package com.hmdp;

import com.hmdp.dto.UserDTO;
import com.hmdp.service.impl.FollowServiceImpl;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
public class FollowServiceTest {

    @Autowired
    private FollowServiceImpl followService;

    private UserDTO testUser;

    @BeforeEach
    public void setUp() {
        testUser = new UserDTO();
        testUser.setId(1L);
        testUser.setNickName("testUser");
        UserHolder.saveUser(testUser);
    }

    @Test
    public void testFollowUser() {
        Long followUserId = 2L;
        followService.followUser(followUserId, true);
    }

    @Test
    public void testUnfollowUser() {
        Long followUserId = 2L;
        followService.followUser(followUserId, false);
    }
}
