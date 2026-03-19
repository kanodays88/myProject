package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.baomidou.mybatisplus.extension.service.IService;


public interface IBlogService extends IService<Blog> {

    /**
     *
     * 根据id查询blog
     * @param blogId
     * @return
     */
    Result queryBlogById(Long blogId);

    /**
     * 点赞blog
     * @param id
     * @return
     */
    Result likeBlog(Long id);

    /**
     *
     * 分页查询热点博客
     * @param current 页码
     * @return
     */
    Result queryHotBlog(Integer current);
}
