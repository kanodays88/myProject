package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.util.*;
import java.util.concurrent.TimeUnit;


@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {


    @Autowired
    private UserMapper userMapper;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result sendCode(String phone, HttpSession session) {
        //校验手机号是否是无效格式
        boolean phoneInvalid = RegexUtils.isPhoneInvalid(phone);
        if(phoneInvalid == true) return Result.fail("手机号格式不正确");
        //生成验证码
        String numbers = RandomUtil.randomNumbers(4);
        //将验证码保存到redis,以手机号为键，设置有效时间为两分钟
        stringRedisTemplate.opsForValue().set("login:code::"+phone,numbers,2, TimeUnit.MINUTES);
        //发送验证码，由于需要第三方平台发送，不做
        log.info("发送验证码成功，验证码：{}",numbers);
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //校验手机号是否是无效格式
        if(RegexUtils.isPhoneInvalid(loginForm.getPhone()) == true){
            //手机号格式错误
            return Result.fail("手机号格式错误");
        }
        //获取验证码并判断验证码是否一致
        String code = (String)stringRedisTemplate.opsForValue().get("login:code::"+loginForm.getPhone());
        if(loginForm.getCode().equals(code) == false){
            //验证码不一致
            return Result.fail("验证码不一致");
        }

        //根据手机号查找用户
        Map dataBaseMap = new HashMap();
        dataBaseMap.put("phone",loginForm.getPhone());
        List<User> list = userMapper.selectByMap(dataBaseMap);
        UserDTO userDTO = new UserDTO();

        //没找到，注册
        if(list == null || list.size() == 0){
            User user = User.builder().phone(loginForm.getPhone()).nickName("user_"+RandomUtil.randomString(10)).build();
            int rows = userMapper.insert(user);
            //将用户保存到redis,键就用UUID生成
            String token = UUID.randomUUID().toString();
            BeanUtils.copyProperties(user,userDTO);
            Map<String, Object> stringObjectMap = BeanUtil.beanToMap(userDTO, new HashMap<>(), CopyOptions.create().setIgnoreNullValue(true).setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
            //这里用的是stringRedisTemplate,序列化为string，所以传入的Map里的所有数据都得是string类型
            stringRedisTemplate.opsForHash().putAll("login:user::"+token,stringObjectMap);
            //设置有效时间,30分钟
            stringRedisTemplate.expire("login:user::"+token,30,TimeUnit.MINUTES);
            if(rows == 0) throw new RuntimeException("新建用户失败");
            return Result.ok(token);
        }

        //找到登录,直接将用户信息保存到redis
        //将用户保存到redis,键就用UUID生成
        String token = UUID.randomUUID().toString();
        BeanUtils.copyProperties(list.get(0),userDTO);
        Map<String, Object> stringObjectMap = BeanUtil.beanToMap(userDTO, new HashMap<>(), CopyOptions.create().setIgnoreNullValue(true).setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
        //这里用的是stringRedisTemplate,序列化为string，所以传入的Map里的所有数据都得是string类型
        stringRedisTemplate.opsForHash().putAll("login:user::"+token,stringObjectMap);
        //设置有效时间30分钟
        stringRedisTemplate.expire("login:user::"+token,30,TimeUnit.MINUTES);

        return Result.ok(token);
    }

    @Override
    public Result me(HttpServletRequest request) {
        UserDTO userDTO = UserHolder.getUser();
        if(userDTO == null){
            return Result.fail("未登录");
        }
        return Result.ok(userDTO);
    }
}
