package com.hmdp.utils;


import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_TTL;

public class RefreshTokenInterceptor implements HandlerInterceptor {

    private StringRedisTemplate stringRedisTemplate;

    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //获取token
//        HttpSession session = request.getSession();
        String token = request.getHeader("authorization");
        if(token==null){
            return true;
        }
        //判断用户是否存在
        String key=LOGIN_USER_KEY + token;
//        Object user = session.getAttribute("user");
        Map<Object, Object> map = stringRedisTemplate.opsForHash().entries(key);
        if(map.isEmpty()){
            return true;
        }
        //若存在则保存用户到ThreadLocal
        UserDTO userDTO = BeanUtil.mapToBean(map, UserDTO.class, false);
        UserHolder.saveUser(userDTO);
        //重新刷新token有效期
        stringRedisTemplate.expire(key,LOGIN_USER_TTL, TimeUnit.MINUTES);
        //放行
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        UserHolder.removeUser();
    }
}