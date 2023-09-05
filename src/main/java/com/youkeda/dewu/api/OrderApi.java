package com.youkeda.dewu.api;

import com.youkeda.dewu.model.Order;
import com.youkeda.dewu.model.Paging;
import com.youkeda.dewu.model.Product;
import com.youkeda.dewu.model.ProductDetail;
import com.youkeda.dewu.model.Result;
import com.youkeda.dewu.model.User;
import com.youkeda.dewu.param.QueryOrderParam;
import com.youkeda.dewu.service.OrderService;
import com.youkeda.dewu.service.ProductDetailService;
import com.youkeda.dewu.service.ProductService;
import com.youkeda.dewu.service.UserService;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import javax.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Controller
@RequestMapping(path = "/api/order")
public class OrderApi {
    private static final Logger logger = LoggerFactory.getLogger(OrderApi.class);

    // 通知消息格式
    private static final String MAG_TMPL = "%s 下单 %s";

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private OrderService orderService;

    @Autowired
    private UserService userService;

    @Autowired
    private ProductDetailService productDetailService;

    @Autowired
    private ProductService productService;

    /**
     * 生成订单Api 下单服务
     *
     * @return Result
     */
    @PostMapping(path = "/add")
    @ResponseBody
    public Result<Order> payOrder(@RequestBody Order order, HttpServletRequest request) {
        Result result = new Result();
        result.setSuccess(true);
        if (order == null) {
            result.setSuccess(false);
            result.setMessage("order is null");
            return result;
        }

        if (StringUtils.isEmpty(order.getProductDetailId())) {
            logger.error("输入参数错误，必须输入 productDetailId ");
            result.setSuccess(false);
            result.setMessage("productDetailId is null");
            return result;
        }

        Long userId = (Long)request.getSession().getAttribute("userId");
        if (userId == null) {
            result.setSuccess(false);
            result.setMessage("没有获取登录信息");
            return result;
        }
        order.setUserId(userId.toString());
        Order orderResult = orderService.add(order);

        CompletableFuture.supplyAsync(
                ()->sendNotify(userId, order.getProductDetailId())
        ).thenAccept(res->{
            if(res){
                logger.error("发送消息失败");
            }
        });

        result.setData(orderResult);
        return result;
    }

    private boolean sendNotify(Long userId, String productDetailId) {
        List<Long> userIds = new ArrayList<>();
        userIds.add(userId);
        List<User> users = userService.queryUser(userIds);

        User buyer = null;
        for (User user : users) {
            if (userId.equals(user.getId())) {
                buyer = user;
                break;
            }
        }

        if (buyer == null) {
            logger.error("买家 userId 不存在。userId=" + userId);
            return false;
        }

        ProductDetail pd = productDetailService.get(productDetailId);

        if (pd == null) {
            logger.error("商品详情不存在。productDetailId=" + productDetailId);
            return false;
        }

        Product product = productService.get(pd.getProductId());

        if (product == null) {
            logger.error("商品不存在。productDetailId=" + productDetailId + ",productId=" + pd.getProductId());
            return false;
        }

        String msg = String.format(MAG_TMPL, buyer.getNickName(), product.getName());
        kafkaTemplate.send("dewuNotify", msg);
        return true;
    }

    /**
     * 查询支付成功订单APi
     *
     * @return Result
     */
    @GetMapping(path = "/queryrecentpaysuccess")
    @ResponseBody
    public Result<Paging<Order>> queryRecentPaySuccess(@RequestBody QueryOrderParam queryOrderParam) {
        Result<Paging<Order>> result = new Result();
        result.setSuccess(true);
        if (queryOrderParam == null) {
            result.setSuccess(false);
            result.setMessage("queryOrderParam is null");
            return result;
        }

        result.setData(orderService.queryRecentPaySuccess(queryOrderParam));
        return result;
    }
}
