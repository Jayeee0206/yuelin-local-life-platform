-- SYNTHETIC_PHONE_FIXTURES_ONLY: contact-shaped seed values are deterministic test data, not real contacts.
SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- Table structure for tb_blog
-- ----------------------------
DROP TABLE IF EXISTS `tb_blog`;
CREATE TABLE `tb_blog`  (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `shop_id` bigint(20) NOT NULL COMMENT '商户id',
  `user_id` bigint(20) UNSIGNED NOT NULL COMMENT '用户id',
  `title` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '标题',
  `images` varchar(2048) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '探店的照片，最多9张，多张以\",\"隔开',
  `content` varchar(2048) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '探店的文字描述',
  `liked` int(8) UNSIGNED NOT NULL DEFAULT 0 COMMENT '点赞数量',
  `comments` int(8) UNSIGNED NOT NULL DEFAULT 0 COMMENT '评论数量',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_blog_user_id` (`user_id`, `id`) USING BTREE,
  KEY `idx_blog_liked_id` (`liked`, `id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 8 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Compact;

-- ----------------------------
-- Records of tb_blog
-- ----------------------------
INSERT INTO `tb_blog` VALUES (4, 4, 2, '演示探店笔记 4', '/imgs/blogs/placeholder.svg', '这是用于界面和接口测试的合成内容，不描述真实人物、消费或地点。', 0, 0, '2026-01-28 19:50:01', '2026-04-10 14:26:34');
INSERT INTO `tb_blog` VALUES (5, 1, 2, '演示探店笔记 5', '/imgs/blogs/placeholder.svg', '这是用于界面和接口测试的合成内容，不描述真实人物、消费或地点。', 0, 0, '2026-01-28 20:57:49', '2026-04-10 09:21:39');
INSERT INTO `tb_blog` VALUES (6, 10, 1, '演示探店笔记 6', '/imgs/blogs/placeholder.svg', '这是用于界面和接口测试的合成内容，不描述真实人物、消费或地点。', 0, 0, '2026-02-11 16:05:47', '2026-04-10 09:21:41');
INSERT INTO `tb_blog` VALUES (7, 10, 1, '演示探店笔记 7', '/imgs/blogs/placeholder.svg', '这是用于界面和接口测试的合成内容，不描述真实人物、消费或地点。', 0, 0, '2026-02-11 16:05:47', '2026-04-10 09:21:42');

-- ----------------------------
-- Table structure for tb_blog_comments
-- ----------------------------
DROP TABLE IF EXISTS `tb_blog_comments`;
CREATE TABLE `tb_blog_comments`  (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` bigint(20) UNSIGNED NOT NULL COMMENT '用户id',
  `blog_id` bigint(20) UNSIGNED NOT NULL COMMENT '探店id',
  `parent_id` bigint(20) UNSIGNED NOT NULL COMMENT '关联的1级评论id，如果是一级评论，则值为0',
  `answer_id` bigint(20) UNSIGNED NOT NULL COMMENT '回复的评论id',
  `content` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '回复的内容',
  `liked` int(8) UNSIGNED NOT NULL DEFAULT 0 COMMENT '点赞数',
  `status` tinyint(4) UNSIGNED NOT NULL DEFAULT 0 COMMENT '状态，0：正常，1：被举报，2：禁止查看',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_blog_comment_blog_parent_time` (`blog_id`, `parent_id`, `create_time`) USING BTREE,
  KEY `idx_blog_comment_parent_time` (`parent_id`, `create_time`) USING BTREE,
  KEY `idx_blog_comment_user` (`user_id`) USING BTREE,
  KEY `idx_blog_comment_blog_status` (`blog_id`, `status`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Compact;

-- ----------------------------
-- Records of tb_blog_comments
-- ----------------------------

DROP TABLE IF EXISTS `tb_blog_comment_report`;
CREATE TABLE `tb_blog_comment_report` (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `comment_id` bigint(20) UNSIGNED NOT NULL COMMENT '被举报的评论id',
  `user_id` bigint(20) UNSIGNED NOT NULL COMMENT '举报人id',
  `reason` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '举报理由',
  `handled` tinyint(1) UNSIGNED NOT NULL DEFAULT 0 COMMENT '是否已被管理员处理，0：待处理，1：已处理',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_comment_report_comment_user` (`comment_id`, `user_id`) USING BTREE,
  KEY `idx_comment_report_handled_time` (`handled`, `create_time`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '评论举报记录表';

-- ----------------------------
-- Table structure for tb_follow
-- ----------------------------
DROP TABLE IF EXISTS `tb_follow`;
CREATE TABLE `tb_follow`  (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` bigint(20) UNSIGNED NOT NULL COMMENT '用户id',
  `follow_user_id` bigint(20) UNSIGNED NOT NULL COMMENT '关联的用户id',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_follow_user_target` (`user_id`, `follow_user_id`) USING BTREE,
  KEY `idx_follow_target_user` (`follow_user_id`, `user_id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Compact;

-- ----------------------------
-- Records of tb_follow
-- ----------------------------

-- ----------------------------
-- Table structure for tb_seckill_voucher
-- ----------------------------
DROP TABLE IF EXISTS `tb_seckill_voucher`;
CREATE TABLE `tb_seckill_voucher`  (
  `voucher_id` bigint(20) UNSIGNED NOT NULL COMMENT '关联的优惠券的id',
  `stock` int(8) NOT NULL COMMENT '库存',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `begin_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '生效时间',
  `end_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '失效时间',
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`voucher_id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '秒杀优惠券表，与优惠券是一对一关系' ROW_FORMAT = Compact;

-- ----------------------------
-- Records of tb_seckill_voucher
-- ----------------------------

-- ----------------------------
-- Table structure for tb_shop
-- ----------------------------
DROP TABLE IF EXISTS `tb_shop`;
CREATE TABLE `tb_shop`  (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '商铺名称',
  `type_id` bigint(20) UNSIGNED NOT NULL COMMENT '商铺类型的id',
  `images` varchar(1024) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '商铺图片，多个图片以\',\'隔开',
  `area` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '商圈，例如陆家嘴',
  `address` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '地址',
  `x` double UNSIGNED NOT NULL COMMENT '经度',
  `y` double UNSIGNED NOT NULL COMMENT '维度',
  `avg_price` bigint(10) UNSIGNED NULL DEFAULT NULL COMMENT '均价，取整数',
  `sold` int(10) UNSIGNED ZEROFILL NOT NULL COMMENT '销量',
  `comments` int(10) UNSIGNED ZEROFILL NOT NULL COMMENT '评论数量',
  `score` int(2) UNSIGNED ZEROFILL NOT NULL COMMENT '评分，1~5分，乘10保存，避免小数',
  `open_hours` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '营业时间，例如 10:00-22:00',
  `create_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `foreign_key_type`(`type_id`) USING BTREE,
  KEY `idx_shop_type_comments` (`type_id`, `comments`, `id`) USING BTREE,
  KEY `idx_shop_type_score` (`type_id`, `score`, `id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 15 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Compact;

-- ----------------------------
-- Records of tb_shop
-- ----------------------------
INSERT INTO `tb_shop` VALUES (1, '演示商户01', 1, '/imgs/blogs/placeholder.svg', '演示商圈01', '演示地址01（非真实地点）', 0.010010, 0.020010, 80, 0000004215, 0000003035, 37, '10:00-22:00', '2026-01-22 18:10:39', '2026-02-13 17:32:19');
INSERT INTO `tb_shop` VALUES (2, '演示商户02', 1, '/imgs/blogs/placeholder.svg', '演示商圈02', '演示地址02（非真实地点）', 0.010020, 0.020020, 85, 0000002160, 0000001460, 46, '11:30-03:00', '2026-01-22 19:00:13', '2026-02-11 16:12:26');
INSERT INTO `tb_shop` VALUES (3, '演示商户03', 1, '/imgs/blogs/placeholder.svg', '演示商圈03', '演示地址03（非真实地点）', 0.010030, 0.020030, 61, 0000012035, 0000008045, 47, '10:30-21:00', '2026-01-22 19:10:05', '2026-02-11 16:12:42');
INSERT INTO `tb_shop` VALUES (4, '演示商户04', 1, '/imgs/blogs/placeholder.svg', '演示商圈04', '演示地址04（非真实地点）', 0.010040, 0.020040, 290, 0000013519, 0000009529, 49, '11:00-22:00', '2026-01-22 19:17:15', '2026-02-11 16:12:51');
INSERT INTO `tb_shop` VALUES (5, '演示商户05', 1, '/imgs/blogs/placeholder.svg', '演示商圈01', '演示地址05（非真实地点）', 0.010050, 0.020050, 104, 0000004125, 0000002764, 49, '10:00-07:00', '2026-01-22 19:20:58', '2026-02-11 16:13:01');
INSERT INTO `tb_shop` VALUES (6, '演示商户06', 1, '/imgs/blogs/placeholder.svg', '演示商圈02', '演示地址06（非真实地点）', 0.010060, 0.020060, 130, 0000009531, 0000007324, 46, '11:00-13:50,17:00-20:50', '2026-01-22 19:24:53', '2026-02-11 16:13:09');
INSERT INTO `tb_shop` VALUES (7, '演示商户07', 1, '/imgs/blogs/placeholder.svg', '演示商圈03', '演示地址07（非真实地点）', 0.010070, 0.020070, 85, 0000002631, 0000001320, 47, '00:00-24:00', '2026-01-22 19:40:52', '2026-02-11 16:13:19');
INSERT INTO `tb_shop` VALUES (8, '演示商户08', 1, '/imgs/blogs/placeholder.svg', '演示商圈04', '演示地址08（非真实地点）', 0.010080, 0.020080, 88, 0000002406, 0000001206, 46, ' 11:00-21:30', '2026-01-22 19:51:06', '2026-02-11 16:13:25');
INSERT INTO `tb_shop` VALUES (9, '演示商户09', 1, '/imgs/blogs/placeholder.svg', '演示商圈01', '演示地址09（非真实地点）', 0.010090, 0.020090, 101, 0000002763, 0000001363, 44, '11:00-21:30', '2026-01-22 19:53:59', '2026-02-11 16:13:34');
INSERT INTO `tb_shop` VALUES (10, '演示商户10', 2, '/imgs/blogs/placeholder.svg', '演示商圈02', '演示地址10（非真实地点）', 0.010100, 0.020100, 67, 0000026891, 0000000902, 37, '00:00-24:00', '2026-01-22 20:25:16', '2026-01-22 20:25:16');
INSERT INTO `tb_shop` VALUES (11, '演示商户11', 2, '/imgs/blogs/placeholder.svg', '演示商圈03', '演示地址11（非真实地点）', 0.010110, 0.020110, 75, 0000035977, 0000005684, 47, '11:30-06:00', '2026-01-22 20:29:02', '2026-01-22 20:39:00');
INSERT INTO `tb_shop` VALUES (12, '演示商户12', 2, '/imgs/blogs/placeholder.svg', '演示商圈04', '演示地址12（非真实地点）', 0.010120, 0.020120, 88, 0000006444, 0000000235, 46, '10:00-02:00', '2026-01-22 20:34:34', '2026-01-22 20:34:34');
INSERT INTO `tb_shop` VALUES (13, '演示商户13', 2, '/imgs/blogs/placeholder.svg', '演示商圈01', '演示地址13（非真实地点）', 0.010130, 0.020130, 58, 0000018997, 0000001857, 41, '12:00-02:00', '2026-01-22 20:38:54', '2026-01-22 20:40:04');
INSERT INTO `tb_shop` VALUES (14, '演示商户14', 2, '/imgs/blogs/placeholder.svg', '演示商圈02', '演示地址14（非真实地点）', 0.010140, 0.020140, 60, 0000017771, 0000000685, 47, '10:00-22:00', '2026-01-22 20:48:54', '2026-01-22 20:48:54');

-- ----------------------------
-- Table structure for tb_shop_type
-- ----------------------------
DROP TABLE IF EXISTS `tb_shop_type`;
CREATE TABLE `tb_shop_type`  (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `name` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '类型名称',
  `icon` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '图标',
  `sort` int(3) UNSIGNED NULL DEFAULT NULL COMMENT '顺序',
  `create_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 11 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Compact;

-- ----------------------------
-- Records of tb_shop_type
-- ----------------------------
INSERT INTO `tb_shop_type` VALUES (1, '美食', '', 1, '2026-01-22 20:17:47', '2026-01-23 11:24:31');
INSERT INTO `tb_shop_type` VALUES (2, 'KTV', '', 2, '2026-01-22 20:18:27', '2026-01-23 11:24:31');
INSERT INTO `tb_shop_type` VALUES (3, '丽人·美发', '', 3, '2026-01-22 20:18:48', '2026-01-23 11:24:31');
INSERT INTO `tb_shop_type` VALUES (4, '健身运动', '', 10, '2026-01-22 20:19:04', '2026-01-23 11:24:31');
INSERT INTO `tb_shop_type` VALUES (5, '按摩·足疗', '', 5, '2026-01-22 20:19:27', '2026-01-23 11:24:31');
INSERT INTO `tb_shop_type` VALUES (6, '美容SPA', '', 6, '2026-01-22 20:19:35', '2026-01-23 11:24:31');
INSERT INTO `tb_shop_type` VALUES (7, '亲子游乐', '', 7, '2026-01-22 20:19:53', '2026-01-23 11:24:31');
INSERT INTO `tb_shop_type` VALUES (8, '酒吧', '', 8, '2026-01-22 20:20:02', '2026-01-23 11:24:31');
INSERT INTO `tb_shop_type` VALUES (9, '轰趴馆', '', 9, '2026-01-22 20:20:08', '2026-01-23 11:24:31');
INSERT INTO `tb_shop_type` VALUES (10, '美睫·美甲', '', 4, '2026-01-22 20:21:46', '2026-01-23 11:24:31');

-- ----------------------------
-- Table structure for tb_sign
-- ----------------------------
DROP TABLE IF EXISTS `tb_sign`;
CREATE TABLE `tb_sign`  (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` bigint(20) UNSIGNED NOT NULL COMMENT '用户id',
  `year` year NOT NULL COMMENT '签到的年',
  `month` tinyint(2) NOT NULL COMMENT '签到的月',
  `date` date NOT NULL COMMENT '签到的日期',
  `is_backup` tinyint(1) UNSIGNED NULL DEFAULT NULL COMMENT '是否补签',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Compact;

-- ----------------------------
-- Records of tb_sign
-- ----------------------------

-- ----------------------------
-- Table structure for tb_user
-- ----------------------------
DROP TABLE IF EXISTS `tb_user`;
CREATE TABLE `tb_user`  (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `phone` varchar(11) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '手机号码',
  `password` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT '' COMMENT '密码，加密存储',
  `nick_name` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT '' COMMENT '昵称，默认是用户id',
  `icon` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT '' COMMENT '人物头像',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uniqe_key_phone`(`phone`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 11 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Compact;

-- ----------------------------
-- Records of tb_user
-- ----------------------------
INSERT INTO `tb_user` VALUES (1, '00000000001', '', 'demo_user_0001', '/imgs/icons/default-icon.svg', '2026-01-24 10:27:19', '2026-02-11 16:04:00');
INSERT INTO `tb_user` VALUES (2, '00000000002', '', 'demo_user_0002', '/imgs/icons/default-icon.svg', '2026-01-24 15:14:39', '2026-01-28 19:58:04');
INSERT INTO `tb_user` VALUES (4, '00000000004', '', 'demo_user_0004', '', '2026-02-07 12:07:53', '2026-02-07 12:07:53');
INSERT INTO `tb_user` VALUES (5, '00000000005', '', 'demo_user_0005', '/imgs/icons/default-icon.svg', '2026-02-07 16:11:33', '2026-04-11 09:09:20');
INSERT INTO `tb_user` VALUES (6, '00000000006', '', 'demo_user_0006', '', '2026-03-07 17:54:10', '2026-03-07 17:54:10');
INSERT INTO `tb_user` VALUES (10, '00000000010', '', 'demo_user_0010', '', '2026-03-28 10:50:47', '2026-03-28 10:50:47');

-- ----------------------------
-- Table structure for tb_user_info
-- ----------------------------
DROP TABLE IF EXISTS `tb_user_info`;
CREATE TABLE `tb_user_info`  (
  `user_id` bigint(20) UNSIGNED NOT NULL COMMENT '主键，用户id',
  `city` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT '' COMMENT '城市名称',
  `introduce` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '个人介绍，不要超过128个字符',
  `fans` int(8) UNSIGNED NULL DEFAULT 0 COMMENT '粉丝数量',
  `followee` int(8) UNSIGNED NULL DEFAULT 0 COMMENT '关注的人的数量',
  `gender` tinyint(1) UNSIGNED NULL DEFAULT 0 COMMENT '性别，0：男，1：女',
  `birthday` date NULL DEFAULT NULL COMMENT '生日',
  `credits` int(8) UNSIGNED NULL DEFAULT 0 COMMENT '积分',
  `level` tinyint(1) UNSIGNED NULL DEFAULT 0 COMMENT '会员级别，0~9级,0代表未开通会员',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Compact;

-- ----------------------------
-- Records of tb_user_info
-- ----------------------------

-- ----------------------------
-- Table structure for tb_voucher
-- ----------------------------
DROP TABLE IF EXISTS `tb_voucher`;
CREATE TABLE `tb_voucher`  (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `shop_id` bigint(20) UNSIGNED NULL DEFAULT NULL COMMENT '商铺id',
  `title` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '代金券标题',
  `sub_title` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '副标题',
  `rules` varchar(1024) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '使用规则',
  `pay_value` bigint(10) UNSIGNED NOT NULL COMMENT '支付金额，单位是分。例如200代表2元',
  `actual_value` bigint(10) NOT NULL COMMENT '抵扣金额，单位是分。例如200代表2元',
  `type` tinyint(1) UNSIGNED NOT NULL DEFAULT 0 COMMENT '0,普通券；1,秒杀券',
  `status` tinyint(1) UNSIGNED NOT NULL DEFAULT 1 COMMENT '1,上架; 2,下架; 3,过期',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_voucher_shop_status` (`shop_id`, `status`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 2 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Compact;

-- ----------------------------
-- Records of tb_voucher
-- ----------------------------
INSERT INTO `tb_voucher` VALUES (1, 1, '50元代金券', '周一至周日均可使用', '全场通用\\n无需预约\\n可无限叠加\\不兑现、不找零\\n仅限堂食', 4750, 5000, 0, 1, '2026-02-04 09:42:39', '2026-02-04 09:43:31');

-- ----------------------------
-- Table structure for tb_voucher_order
-- ----------------------------
DROP TABLE IF EXISTS `tb_voucher_order`;
CREATE TABLE `tb_voucher_order`  (
  `id` bigint(20) NOT NULL COMMENT '主键',
  `user_id` bigint(20) UNSIGNED NOT NULL COMMENT '下单的用户id',
  `voucher_id` bigint(20) UNSIGNED NOT NULL COMMENT '购买的代金券id',
  `pay_type` tinyint(1) UNSIGNED NOT NULL DEFAULT 1 COMMENT '支付方式 1：余额支付；2：支付宝；3：微信',
  `status` tinyint(1) UNSIGNED NOT NULL DEFAULT 1 COMMENT '订单状态，1：未支付；2：已支付；3：已核销；4：已取消；5：退款中；6：已退款',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下单时间',
  `pay_time` timestamp NULL DEFAULT NULL COMMENT '支付时间',
  `use_time` timestamp NULL DEFAULT NULL COMMENT '核销时间',
  `refund_time` timestamp NULL DEFAULT NULL COMMENT '退款时间',
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_voucher_order_user_voucher` (`user_id`, `voucher_id`) USING BTREE,
  KEY `idx_voucher_order_voucher` (`voucher_id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Compact;

-- ----------------------------
-- Records of tb_voucher_order
-- ----------------------------

SET FOREIGN_KEY_CHECKS = 1;

DROP TABLE IF EXISTS tb_content_like;
CREATE TABLE tb_content_like (
    target_type varchar(16) NOT NULL,
    target_id bigint unsigned NOT NULL,
    user_id bigint unsigned NOT NULL,
    liked_at bigint NOT NULL,
    PRIMARY KEY (target_type, target_id, user_id),
    KEY idx_content_like_order (target_type, target_id, liked_at, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
DROP TABLE IF EXISTS tb_shop_cache_version;
CREATE TABLE tb_shop_cache_version (
    shop_id bigint unsigned NOT NULL,
    version bigint unsigned NOT NULL DEFAULT 0,
    PRIMARY KEY (shop_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Upload permissions are derived from registered ownership.
DROP TABLE IF EXISTS tb_upload_asset;
CREATE TABLE tb_upload_asset (
    path varchar(160) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_id bigint unsigned NOT NULL,
    state varchar(16) NOT NULL,
    blog_id bigint unsigned NULL,
    create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (path),
    KEY idx_upload_owner_state_time (user_id,state,update_time),
    KEY idx_upload_blog (blog_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

DROP TABLE IF EXISTS tb_comment_moderation_audit;
CREATE TABLE tb_comment_moderation_audit (
 id bigint unsigned NOT NULL AUTO_INCREMENT PRIMARY KEY,
 comment_id bigint unsigned NOT NULL,
 actor_id bigint unsigned NOT NULL,
 action varchar(16) NOT NULL,
 previous_status int NULL,
 next_status int NULL,
 create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
 KEY idx_moderation_comment (comment_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
DROP TABLE IF EXISTS tb_order_resolution;
CREATE TABLE tb_order_resolution (
 order_id bigint unsigned NOT NULL PRIMARY KEY,
 user_id bigint unsigned NOT NULL,
 voucher_id bigint unsigned NOT NULL,
 state varchar(16) NOT NULL DEFAULT 'PENDING',
 update_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
