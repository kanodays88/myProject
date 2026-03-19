package com.hmdp.dto;

import com.hmdp.entity.Blog;
import lombok.Data;

import java.util.List;

@Data
public class BlogListToJsonDTO {
    private List<Blog> blogs;
}
