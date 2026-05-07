package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
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
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1.校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            //2.不符合直接返回
            return Result.fail("手机号不符合");
        }
        //3.生成验证码
        String code = RandomUtil.randomNumbers(6);
        //4.保存验证码到redis
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY+phone,code,LOGIN_CODE_TTL+RandomUtil.randomLong(0,5), TimeUnit.MINUTES);
//        session.setAttribute("code",code);
        //5.发送验证码
        log.debug("发送验证码已成功：{}",code);
        return Result.ok();

    }
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        if (RegexUtils.isPhoneInvalid(loginForm.getPhone())) {
            return Result.fail("手机号错误");
        }
        String s=LOGIN_CODE_KEY+loginForm.getPhone();
        //校验验证码
        String code = stringRedisTemplate.opsForValue().get(s);
//        Object code = session.getAttribute("code");
        if(code==null||!code.equals(loginForm.getCode())){
            return Result.fail("验证码不一致");
        }
        //根据手机号查询用户
        User user = query().eq("phone", loginForm.getPhone()).one();
        //不存在的话创建新用户
        if(user==null){
            user=createNewUser(loginForm.getPhone());
        }
        //保存用户到redis
        UserDTO userDTO=new UserDTO();
        BeanUtil.copyProperties(user,userDTO);
        Map<String, Object> map = BeanUtil.beanToMap(userDTO,new HashMap<>(),CopyOptions.create()
                .setIgnoreNullValue(true)
                .setFieldValueEditor((fieldName,fieldValue)->fieldValue.toString()));
        String token = UUID.randomUUID().toString();
        String key=LOGIN_USER_KEY+token;
        stringRedisTemplate.opsForHash().putAll(key,map);
//        session.setAttribute("user", BeanUtil.copyProperties(user, UserDTO.class));
        //设置token有效期
        stringRedisTemplate.expire(key,LOGIN_USER_TTL+RandomUtil.randomLong(0,5),TimeUnit.MINUTES);

        return Result.ok(token);
    }

    @Override
    public Result sign() {
        //获取用户id
        Long userId = UserHolder.getUser().getId();

        LocalDateTime now = LocalDateTime.now();

        String keySuffix =now.format(DateTimeFormatter.ofPattern(":yyyyMM"));

        //redis中的键名
        String key=USER_SIGN_KEY+userId+keySuffix;

        //获取当前是第几天
        int dayOfMonth = now.getDayOfMonth();
        //在redis中签到
        stringRedisTemplate.opsForValue().setBit(key,dayOfMonth-1,true);
        return Result.ok();
    }

    @Override
    public Result countSign() {

        //获取用户id
        Long userId = UserHolder.getUser().getId();

        LocalDateTime now = LocalDateTime.now();

        String keySuffix =now.format(DateTimeFormatter.ofPattern(":yyyyMM"));

        //redis中的键名
        String key=USER_SIGN_KEY+userId+keySuffix;

        //获取当前是第几天
        int dayOfMonth = now.getDayOfMonth();

        //获取本月截止目前为止所有签到记录，如bitfield bm1 get u6 0
        List<Long> results = stringRedisTemplate.opsForValue().bitField(key, BitFieldSubCommands.create().get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0));

        if (results==null||results.isEmpty()){
            //没有任何签到结果
            return Result.ok(0);
        }

        Long num = results.get(0);

        if(num==null||num==0){
            return Result.ok(0);
        }

        int count = 0;
        //循环遍历
        while (true){
            //让这个数字与1做与运算，得到数字的最后一个bit
            long bit = num & 1;
            //判断这个bit未是否为0
            if(bit==0){
                //如果为0，说明未签到，结束
                break;
            }else {
                //如果不为0,说明已签到，计数器+1
                count++;
            }
            //把数字右移一位，抛弃最后一个bit位，继续下一个bit位
            num=num>>>1;
        }
        return Result.ok(count);

    }

    private User createNewUser(String phone) {
        User user=new User();
        user.setNickName(USER_NICK_NAME_PREFIX+RandomUtil.randomString(6));
        user.setPhone(phone);
        save(user);
        return user;
    }
}
