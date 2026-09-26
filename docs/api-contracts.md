# API 契约

Nginx 前缀 `/api`；以下为控制器路径。受保护接口使用 `authorization` 请求头中的登录 token。业务拒绝通常为 HTTP 200 + `success:false,errorMsg`，调用方必须同时检查业务字段；未登录 401，非管理员访问 `/blog-comment-report/admin/**` 为 403。不要仅用 HTTP 200 判定成功。

## 用户与文件

- `POST /user/code?phone=...` 请求验证码；`POST /user/login` 发送 `{phone,code}`。验证码一次消费，有发送频率限制。dev 交付仅开发环境，不是生产短信。
- `GET /user/me` 为服务端当前身份；`POST /user/logout` 撤销服务端 token，浏览器清除 sessionStorage。
- `PUT /user/profile` 提交资料；托管头像必须是本人 READY 上传。资料与头像状态同一事务，更换头像释放此前已知归属的头像；未登记文件不据此授权删除。
- `POST /upload/blog` 为 multipart 字段 file，成功返回相对图片路径；`DELETE /upload/blog?name=...` 删除本人未绑定草稿。重复已完成删除可重试，已引用、他人及未知归属文件拒绝。
- `POST /blog` 发布笔记，后端使用当前登录人、归零初始计数、原子绑定图片；不能伪造作者或初始点赞数。

## 评论与审核

- `GET /blog-comments/of/blog/{id}?current=1` 返回访问者可见一级评论；`total` 是相同可见口径根评论数。
- 每个根评论 replies 最多3条，replyCount 为当前可见回复总数；展开用 `GET /blog-comments/replies/{id}?current=1`。根楼不可见时，不能通过回复接口绕过。
- `POST /blog-comments` 发布；`PUT /blog-comments/like/{id}` 点赞切换；`DELETE /blog-comments/{id}` 作者删除。删除根楼会事务删除其回复及相关计数/关系。
- `POST /blog-comment-report/{id}`，JSON `{ "reason": "具体理由" }`，当前登录用户只能举报可见的他人评论。
- `GET /blog-comment-report/admin/pending?current=1` 返回待办、原文（已删除则为空）和举报次数。每页10条，total为待办条目数。
- `PUT /blog-comment-report/admin/block/{id}`、`restore/{id}` 修改状态并关闭当前待办；同事务记录 actor、动作、前后状态和时间。
- `PUT /blog-comment-report/admin/dismiss/{id}` 关闭待办、不删除历史举报；适用于已删除目标。没有待办返回业务拒绝，不伪造成功记录。
- `GET /blog-comment-report/admin/history?current=1` 分页10条，字段保持审计表 snake_case（comment_id、actor_id、previous_status、next_status、create_time）。
- `tb_blog.comments` 是存续评论记录总量，含回复与屏蔽记录；不是上面的可见根评论 total。

## 秒杀与一致性

`POST /voucher-order/seckill/{voucherId}` 成功返回 `data` 为**十进制字符串订单ID**，避免浏览器超过 Number 安全整数范围。成功代表预约已受理，不是支付完成或订单必已持久化。异步消费者/对账器可完成或取消预约；取消围栏防止迟到消息落单。

数据关系：用户/优惠券唯一订单约束；订单ID→tb_order_resolution 同身份围栏；评论→举报多记录、评论→审核日志多记录；托管图片→归属用户及可选绑定笔记。Redis 为预约/会话/缓存，不能据一个库存计数恢复全部业务关系。
