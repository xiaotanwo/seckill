package com.foxandgrapes.seckill.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.foxandgrapes.seckill.mapper.OrderMapper;
import com.foxandgrapes.seckill.pojo.Goods;
import com.foxandgrapes.seckill.pojo.Order;
import com.foxandgrapes.seckill.pojo.SeckillGoods;
import com.foxandgrapes.seckill.service.IOrderService;
import com.foxandgrapes.seckill.service.ITimeController;
import com.foxandgrapes.seckill.vo.RespBean;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author tsk
 * @since 2021-01-06
 */
@Service
public class OrderServiceImpl extends ServiceImpl<OrderMapper, Order> implements IOrderService {

    @Autowired
    private RedisTemplate redisTemplate;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private ITimeController timeController;
    @Autowired
    private AmqpTemplate amqpTemplate;
    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private ZooKeeper zkClient;

    private static ConcurrentHashMap<Long, Boolean> productSoldOutMap = new ConcurrentHashMap<>();

    public static ConcurrentHashMap<Long, Boolean> getProductSoldOutMap() { return productSoldOutMap; }

    @Override
    public RespBean createOrder(Long seckillGoodsId, Long userId) {

        if (seckillGoodsId == null || userId == null) {
            return RespBean.error("商品ID或用户ID不能为空！");
        }

        // 从redis中取出秒杀商品的数量
        SeckillGoods seckillGoods = (SeckillGoods) redisTemplate.opsForValue().get("SECKILL_GOODS_" + seckillGoodsId);

        if (seckillGoods == null) {
            return RespBean.error("活动已结束！");
        }

        // 获取当前时间，判断是否为活动时间
        Date now = null;
        try {
            SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm");
            now = simpleDateFormat.parse(timeController.getTime());
            // 没到秒杀时间或已过期
            if (seckillGoods.getStartDate().getTime() > now.getTime() ||
                    now.getTime() > seckillGoods.getEndDate().getTime()) {
                return RespBean.error("活动还没开始或已结束！");
            }
        } catch (ParseException e) {
            e.printStackTrace();
            return RespBean.error("时间转换出现错误！");
        }

        // 秒杀开始前直接判断是否还有库存，没有了就直接返回
        if (productSoldOutMap.get(seckillGoodsId) != null) {
            return RespBean.error("很抱歉，已经没有库存了！");
        }

        // 有秒杀商品，以及到活动时间了，开始秒杀！
        // 生成秒杀订单号！
        Long orderId = System.currentTimeMillis();
        // 获取秒杀商品的数量,如果取完后小于0，则说明之前已经没有库存了
        if (stringRedisTemplate.opsForValue().decrement("SECKILL_GOODS_COUNT_" + seckillGoodsId) < 0) {
            // 归还库存
            stringRedisTemplate.opsForValue().increment("SECKILL_GOODS_COUNT_" + seckillGoodsId);
            // 在JVM中直接设置已售完
            productSoldOutMap.put(seckillGoodsId, true);
            // 设置zk的商品售完的标志
            try {
                if (zkClient.exists("/product_sold_out_flag/" + seckillGoodsId, true) == null) {
                    zkClient.create("/product_sold_out_flag/" + seckillGoodsId, "true".getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
                } else {
                    zkClient.setData("/product_sold_out_flag/" + seckillGoodsId, "true".getBytes(), -1);
                }
                // 监听售完标志值的变化
                zkClient.exists("/product_sold_out_flag/" + seckillGoodsId, true);
            } catch (Exception e) {
                e.printStackTrace();
            }
            return RespBean.error("商品已售完！");
        } else {
            try {
                // 从商品信息从redis中取出来
                Goods goods = (Goods) redisTemplate.opsForValue().get("GOODS_" + seckillGoods.getGoodsId());
                // 将订单信息写入到消息队列中待处理
                Order order = new Order();

                order.setId(orderId);
                order.setUserId(userId);
                order.setSeckillGoodsId(seckillGoodsId);
                order.setGoodsName(goods.getGoodsName());
                order.setGoodsCount(1);
                order.setGoodsPrice(seckillGoods.getSeckillPrice());
                order.setStatus(0);
                order.setCreateDate(now);

                // 放入订单队列，后续慢慢处理
                amqpTemplate.convertAndSend("order_queue", order);
            } catch (Exception e) {
                // 归还库存
                stringRedisTemplate.opsForValue().increment("SECKILL_GOODS_COUNT_" + seckillGoodsId);
                // 在JVM中清除已售完标志
                if (productSoldOutMap.get(seckillGoodsId) != null) {
                    productSoldOutMap.remove(seckillGoodsId);
                }
                // 修改zk的商品售完的标志
                try {
                    if (zkClient.exists("/product_sold_out_flag/" + seckillGoodsId, true) != null) {
                        zkClient.setData("/product_sold_out_flag/" + seckillGoodsId, "false".getBytes(), -1);
                    }
                } catch (Exception exception) {
                    exception.printStackTrace();
                }
                return RespBean.error("秒杀期间出现了某种错误！");
            }
        }

        return RespBean.success("秒杀成功！", orderId);
    }

    @Override
    public RespBean insertOrder(Order order) {

        if (order == null){
            return RespBean.error("传入参数有误！");
        }

        int res = orderMapper.insert(order);

        if (res != 1){
            return RespBean.error("订单写入失败！");
        }

        return RespBean.success( "订单成功写入数据库！", null);
    }

    @Override
    public RespBean getOrder(Long orderId) {

        if (orderId == null) {
            return RespBean.error("订单号不能为空！");
        }

        Order order = orderMapper.selectById(orderId);
        if (order == null) {
            return RespBean.error("订单不存在！");
        }

        // 为了前端好展示而弄成列表返回
        List<Order> list = new ArrayList<>();
        list.add(order);
        return RespBean.success( "获取订单成功！", list);
    }

    @Override
    public RespBean payOrder(Long orderId, Long seckillGoodsId) {

        if (orderId == null || seckillGoodsId == null) {
            return RespBean.error("订单有误！");
        }

        Order order = orderMapper.selectById(orderId);

        if (order == null) {
            return RespBean.error("订单不存在！");
        }

        if (order.getStatus() == 1) {
            return RespBean.error("订单已支付！");
        }

        // 设置已支付：1
        order.setStatus(1);
        // 设置支付时间
        Date payDate = new Date();
        order.setPayDate(payDate);
        int res = orderMapper.updateById(order);

        if (res != 1) {
            return RespBean.error("订单更新失败！");
        }

        // 放入库存队列，后续慢慢处理
        amqpTemplate.convertAndSend("stock_queue", seckillGoodsId);

        // 返回时间给前端，可以直接显示（转换格式再返回）
        return RespBean.success("订单更新完成！", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(payDate));
    }
}
