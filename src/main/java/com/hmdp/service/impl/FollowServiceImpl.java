package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private IUserService userService;

    @Override
    public Result follow(Long id, boolean isFollow) {
        //得到用户id
        Long userId = UserHolder.getUser().getId();

        String key="follow:"+userId;
        //判断是否需要关注
        if(isFollow){
            //未关注，可以关注
            Follow follow=new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(id);
            boolean ifSuccess = save(follow);
            if(ifSuccess){
                stringRedisTemplate.opsForSet().add(key,id.toString());
            }
        }else {
            //已关注，取消关注
            boolean ifSuccess = remove(new QueryWrapper<Follow>().eq("user_id", userId).eq("follow_user_id", id));
            if(ifSuccess){
                stringRedisTemplate.opsForSet().remove(key,id.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result isFollow(Long id) {
        //得到用户id
        Long userId = UserHolder.getUser().getId();
        //判断用户有没有关注
//        Follow follow = query().eq("follow_user_id", id).eq("user_id",userId).one();
        Integer count = query().eq("follow_user_id", id).eq("user_id", userId).count();
        return Result.ok(count>0);
    }

    @Override
    public Result queryCommon(Long followUserId) {
        Long userId = UserHolder.getUser().getId();
        String key1="follow:"+userId;

        String key2="follow:"+followUserId;
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key1, key2);
        if(intersect==null||intersect.isEmpty()){
            return Result.fail("没有共同关注！");
        }

        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        List<UserDTO> userDTOList = userService.listByIds(ids)
                .stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());

        return Result.ok(userDTOList);

    }
}
