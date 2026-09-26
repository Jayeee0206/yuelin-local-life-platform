#!/usr/bin/env node
/**
 * 评论区真实渲染验证（headless Chrome）。
 *
 * 与 frontend-static-check.mjs 的区别：那个只做文本层面的规则校验，
 * 从不执行 JavaScript，因此 Vue 模板是否真能渲染、v-if 分支是否正确、
 * 事件是否绑定成功，它一概不知道。
 *
 * 本脚本使用 Playwright Chromium 和确定性 axios 桩件，
 * 不连接本机浏览器、后端、数据库或 Redis，可在隔离 CI 中稳定运行。
 *
 * 用法：node scripts/frontend-render-check.mjs
 */
import { chromium } from 'playwright';
import { createStaticServer } from './lib/render-server.mjs';

const ROOT = 'frontend';
const PORT = 4173;
function serve() {
  return createStaticServer(ROOT).listen(PORT, '127.0.0.1');
}

/* ---------- 断言框架 ---------- */
const results = [];
let failed = 0;
function check(name, condition, detail = '') {
  if (condition) {
    results.push(`  ✓ ${name}`);
  } else {
    failed++;
    results.push(`  ✗ ${name}${detail ? `\n      ${detail}` : ''}`);
  }
}

/**
 * 桩接口数据：覆盖正常评论、被屏蔽评论、带回复的评论三种形态。
 *
 * 采用【网络层拦截】而非替换 window.axios，理由是页面自带的 common.js 会在
 * 加载后重新配置 axios（baseURL=/api、拦截器、参数序列化），任何预先注入的
 * 桩件都会被它覆盖。拦截 HTTP 响应可以让真实的 axios 与拦截器链完整执行，
 * 更接近线上行为。
 */
const BLOG = {
  id: 1, title: '测试探店笔记', content: '正文内容', images: '/imgs/blogs/placeholder.svg',
  userId: 501, nickName: '作者本人', icon: '', liked: 3, isLike: false, comments: 3,
  createTime: '2026-09-20T10:00:00', shopId: 1, updateTime: '2026-09-20T10:00:00',
};

const COMMENTS = [
  { id: 11, userId: 501, nickName: '作者本人', icon: '', content: '这是一条正常评论',
    liked: 2, isLike: false, status: 0, createTime: '2026-09-21T09:00:00',
    parentId: 0, answerId: 0, replyCount: 2, replies: [
      { id: 21, userId: 502, nickName: '邻居A', answerNickName: '作者本人', parentId: 11,
        content: '这是第一条回复', liked: 0, isLike: false, status: 0,
        createTime: '2026-09-21T09:05:00' },
      { id: 22, userId: 503, nickName: '邻居B', answerNickName: '作者本人', parentId: 11,
        content: '这是第二条回复', liked: 1, isLike: false, status: 0,
        createTime: '2026-09-21T09:06:00' },
    ] },
  { id: 12, userId: 501, nickName: '作者本人', icon: '', content: '这条被管理员屏蔽了',
    liked: 0, isLike: false, status: 2, createTime: '2026-09-21T10:00:00',
    parentId: 0, answerId: 0, replyCount: 0, replies: [] },
  { id: 13, userId: 502, nickName: '邻居A', icon: '', content: '他人的评论',
    liked: 5, isLike: true, status: 0, createTime: '2026-09-21T11:00:00',
    parentId: 0, answerId: 0, replyCount: 0, replies: [] },
];

const VIEWER = { id: 501, nickName: '作者本人', icon: '' };

/** 记录后端被调用的次数，用于判断按钮是不是「假入口」。 */
const apiCalls = [];

function stubResponse(pathname) {
  if (pathname.startsWith('/api/blog-comments/of/blog/')) {
    return { success: true, data: COMMENTS, total: COMMENTS.length };
  }
  if (pathname.startsWith('/api/blog-comments/replies/')) {
    return { success: true, data: [], total: 0 };
  }
  if (pathname.startsWith('/api/blog-comments/like/')) {
    return { success: true, data: { id: 11, liked: 3, isLike: true } };
  }
  if (pathname.startsWith('/api/blog/')) return { success: true, data: BLOG };
  if (pathname.startsWith('/api/shop/')) {
    return { success: true, data: { id: 1, name: '测试商铺', images: '/imgs/shops/1.jpg', area: '测试区' } };
  }
  if (pathname.startsWith('/api/follow/or/not/')) return { success: true, data: false };
  if (pathname.startsWith('/api/user/me')) return { success: true, data: VIEWER };
  return { success: true, data: [] };
}

const server = serve();
const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1280, height: 900 } });

const pageErrors = [];
page.on('pageerror', e => pageErrors.push(e.message));
page.on('console', m => { if (m.type() === 'error') pageErrors.push(m.text()); });

// 必须在页面脚本执行前注入，才能抢在 axios.min.js 之后覆盖
// 拦截所有后端调用，返回桩数据；前端与 axios 链路保持真实
await page.route('**/api/**', async route => {
  const pathname = new URL(route.request().url()).pathname;
  apiCalls.push(pathname);
  await route.fulfill({
    status: 200,
    contentType: 'application/json; charset=utf-8',
    body: JSON.stringify(stubResponse(pathname)),
  });
});

// 页面从 sessionStorage 读取当前笔记与登录用户
await page.addInitScript(([blog, user]) => {
  sessionStorage.setItem('blog', JSON.stringify(blog));
  sessionStorage.setItem('user', JSON.stringify(user));
  localStorage.setItem('user', JSON.stringify(user));
  sessionStorage.setItem('token', 'test-token');
}, [BLOG, VIEWER]);

await page.goto(`http://127.0.0.1:${PORT}/blog-detail.html?id=1`, { waitUntil: 'networkidle' });
await page.waitForSelector('.comment-section', { timeout: 15000 }).catch(() => {});
await page.waitForTimeout(800);

console.log('\n评论区渲染验证\n');

/* 1. 页面基础 */
check('页面无 JavaScript 运行时错误', pageErrors.length === 0,
  pageErrors.slice(0, 3).join(' | '));
check('Vue 实例挂载成功', await page.locator('#app').count() > 0);
check('评论区容器已渲染', await page.locator('.comment-section').count() > 0);

/* 2. 评论列表 —— 静态检查完全覆盖不到的部分 */
const items = await page.locator('.comment-item').count();
check('三条评论全部渲染为列表项', items === 3, `实际渲染 ${items} 条`);

const contents = await page.locator('.comment-content').allTextContents();
check('评论正文正确显示', contents.some(t => t.includes('这是一条正常评论')),
  `实际内容: ${JSON.stringify(contents.slice(0, 3))}`);

const total = (await page.locator('.comment-total').textContent() || '').trim();
check('评论总数正确绑定', total.includes('3'), `实际显示: ${total}`);

/* 3. 楼中楼 */
const replies = await page.locator('.comment-reply').count();
check('两条回复渲染在父评论下', replies === 2, `实际渲染 ${replies} 条回复`);
const replyText = await page.locator('.comment-reply .comment-content').allTextContents();
check('回复正文正确显示', replyText.some(t => t.includes('第一条回复')),
  `实际: ${JSON.stringify(replyText)}`);
const answerTo = await page.locator('.comment-answer').allTextContents();
check('回复显示「回复 某人」关系', answerTo.some(t => t.includes('作者本人')),
  `实际: ${JSON.stringify(answerTo)}`);

/* 4. 屏蔽提示 —— 对应 SEC-01 的前端表现 */
const blocked = await page.locator('.comment-blocked').count();
check('被屏蔽评论显示提示条且仅一条', blocked === 1, `实际 ${blocked} 条提示`);
const blockedText = await page.locator('.comment-blocked').first().textContent() || '';
check('提示文案说明仅本人可见', blockedText.includes('仅你本人可见'),
  `实际文案: ${blockedText.trim()}`);

/* 5. 操作按钮的条件渲染 */
const del = await page.locator('.comment-action-danger').count();
check('删除按钮只对自己的评论出现', del === 2,
  `桩数据中 userId=501 的评论有 2 条，实际出现 ${del} 个删除按钮`);

const likeLabels = await page.locator('.comment-like').allTextContents();
check('点赞数正确渲染', likeLabels.some(t => t.includes('2')),
  `实际: ${JSON.stringify(likeLabels)}`);

/* 6. 交互真实可用 —— 这是「假入口」的唯一可靠检测方式 */
const beforeCalls = apiCalls.length;
await page.locator('.comment-like').first().click();
await page.waitForTimeout(600);
const afterCalls = apiCalls.length;
check('点赞按钮确实触发了后端请求（非假入口）', afterCalls > beforeCalls,
  `点击前 ${beforeCalls} 次调用，点击后 ${afterCalls} 次`);

await page.locator('.comment-action', { hasText: '回复' }).first().click();
await page.waitForTimeout(300);
const hint = await page.locator('.comment-reply-hint').count();
check('点击回复后出现回复提示', hint > 0);

/* 7. 移动端不横向溢出 */
await page.setViewportSize({ width: 390, height: 844 });
await page.waitForTimeout(400);
const overflow = await page.evaluate(
  () => document.documentElement.scrollWidth - window.innerWidth);
check('移动端视口无横向溢出', overflow <= 1, `溢出 ${overflow}px`);

console.log(results.join('\n'));

await browser.close();
server.close();

if (failed > 0) {
  console.error(`\n渲染验证失败：${failed} 项未通过\n`);
  process.exit(1);
}
console.log(`\n渲染验证通过：${results.length} 项全部满足\n`);
