package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.FEED_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IBlogService blogService;

    @Resource
    private IUserService userService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private IFollowService followService;

    @Override
    public Result queryById(Long id) {
        //查询博客
        Blog blog = getById(id);

        //查询与博客相关的用户
        queryUser(blog);
        //查询当前用户是否点过赞
        isLikedBlog(blog);
        return Result.ok(blog);
    }

    private void isLikedBlog(Blog blog) {
        //1.获取登录用户
        Long userId = UserHolder.getUser().getId();
        if(userId==null){
            //未登录，无需查询点赞数据
            return;
        }
        //2.判断当前登录用户是否已经点赞
        String key="blog:liked:"+blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score!=null);
    }

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = blogService.query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            this.queryUser(blog);
            this.isLikedBlog(blog);
        });
        return Result.ok(records);
    }

    @Override
    public Result likeBlog(Long id) {
        //1.获取登录用户
        Long userId = UserHolder.getUser().getId();
        //2.判断当前登录用户是否已经点赞
        String key=BLOG_LIKED_KEY+id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if(score==null){
            //未点赞，可以点赞
            //数据库点赞数+1
            //保存用户的id到Redis的set集合
            boolean ifSuccess = update().setSql("liked=liked+1").eq("id",id).update();
            if(ifSuccess){
                //数据库点赞成功，保存用户id到redis的set集合
                stringRedisTemplate.opsForZSet().add(key,userId.toString(),System.currentTimeMillis());
            }
        }else {
            //已点赞，不能再点赞
            //数据库点赞数-1
            //移除用户的id到Redis的set集合
            boolean ifSuccess = update().setSql("liked=liked-1").eq("id",id).update();
            if(ifSuccess){
                //数据库点赞成功，保存用户id到redis的set集合
                stringRedisTemplate.opsForZSet().remove(key,userId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result queryTopFive(Long id) {
        String key=BLOG_LIKED_KEY+id;
        //从redis中查询出前五名数据
        Set<String> userIdRange = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if(userIdRange==null||userIdRange.isEmpty()){
            return Result.fail("没有点赞数据！");
        }
        List<Long> longUserIds = userIdRange.stream().map(Long::valueOf).collect(Collectors.toList());
        String ids = StrUtil.join(",", longUserIds);
        //根据id查询用户数据
//        List<User> users = userService.listByIds(longUserIds);
        List<User> users = userService.query().in("id", longUserIds).last("order by field(id," + ids + ")").list();
        List<UserDTO> userDtoList = users.stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class)).collect(Collectors.toList());
        return Result.ok(userDtoList);
    }

    @Override
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        Long userId = UserHolder.getUser().getId();
        blog.setUserId(userId);
        // 保存探店博文
        blogService.save(blog);

        //获取当前用户的所有粉丝
        List<Follow> followList = followService.query().eq("follow_user_id", userId).list();
        for (Follow follow : followList) {
            //将博客发送到粉丝的收件箱当中
            String key=FEED_KEY+follow.getUserId();

            stringRedisTemplate.opsForZSet().add(key,blog.getId().toString(),System.currentTimeMillis());
        }

        // 返回id
        return Result.ok(blog.getId());
    }

    @Override
    public Result scollQuery(Long lastId, Integer offset) {
        //获取当前用户
        Long userId = UserHolder.getUser().getId();
        String key = FEED_KEY+userId;
        //解析收件箱
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet().reverseRangeByScoreWithScores(key,0,lastId,offset,2);

        //非空判断
        if(typedTuples==null||typedTuples.isEmpty()){
            return Result.ok("没有关注任何人");
        }

        List<Long> ids=new ArrayList<>(typedTuples.size());
        long minTime=0;
        int os=1;
        //解析数据：blogId,score(时间戳),offset（偏移量）
        for (ZSetOperations.TypedTuple<String> typedTuple : typedTuples) {
            //解析blogId
            Long id = Long.valueOf(typedTuple.getValue());
            ids.add(id);

            //解析时间戳和偏移量
            long timeMills = typedTuple.getScore().longValue();
            if(minTime==timeMills){
                os++;
            }else {
                minTime=timeMills;
                os=1;
            }
        }

        //新建返回的scrollResult对象
        ScrollResult result=new ScrollResult();
        result.setOffset(os);
        result.setMinTime(minTime);
        String idsString = StrUtil.join(",", ids);

        List<Blog> blogList = blogService.query().in("id", ids).last("order by field(id," + idsString + ")").list();
        for (Blog blog : blogList) {
            //查询与博客相关的用户
            queryUser(blog);
            //查询当前用户是否点过赞
            isLikedBlog(blog);
        }
        result.setList(blogList);

        return Result.ok(result);
    }

    private void queryUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }

}
