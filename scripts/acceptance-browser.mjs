// SYNTHETIC_PHONE_FIXTURES_ONLY: phone-shaped values below are generated test fixtures.
import {chromium} from 'playwright';
import {execFileSync} from 'node:child_process';
import fs from 'node:fs';
import assert from 'node:assert/strict';
if (process.env.YUELIN_DISPOSABLE_ACCEPTANCE !== '1' || !process.env.COMPOSE_PROJECT_NAME?.startsWith('yuelin-ci-')) throw Error('Disposable CI stack required');
const base = process.env.SITE_URL || 'http://localhost:8080';
const artifacts = 'ci-artifacts'; fs.mkdirSync(artifacts,{recursive:true});
const checks = []; const errors = [];
const sql = text => execFileSync('docker',['compose','exec','-T','mysql','sh','-c','exec mysql --protocol=TCP -h127.0.0.1 -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" -N -s -e "$1" "$MYSQL_DATABASE"','sh',text],{encoding:'utf8',stdio:['pipe','pipe','pipe']}).trim();
const redis = (...args) => execFileSync('docker',['compose','exec','-T','redis','redis-cli','-n','6',...args],{encoding:'utf8'}).trim();
sql("UPDATE tb_user SET phone='13900000001' WHERE id=1; UPDATE tb_user SET phone='13900000002' WHERE id=2;");
const browser = await chromium.launch({headless:true});
const contexts=[];
async function user(phone) {
  const ctx=await browser.newContext({viewport:{width:1280,height:900}}); contexts.push(ctx);
  const page=await ctx.newPage(); page.setDefaultTimeout(20000);page.on('pageerror',e=>errors.push(e.message));page.on('dialog',d=>d.accept());
  await page.goto(base+'/login.html?redirect=/info.html');
  await page.locator('#phone').fill(phone);
  await business(page,'/user/code','POST',()=>page.getByRole('button',{name:'发送验证码'}).click());
  const code=redis('GET','login:code:'+phone); assert.match(code,/^\d{6}$/);
  await page.locator('#code').fill(code); await page.locator('input[type=checkbox]').check();
  await navigationRequest(page,'/user/login','POST',()=>page.locator('.login-submit').click());
  await page.waitForURL(url=>url.pathname==='/info.html');
  const authenticated=await api(page,'/user/me');
  assert.equal(authenticated.status,200);assert.equal(authenticated.body.success,true);
  assert.equal(authenticated.body.data.id,String(Number(phone.slice(-2))));
  checks.push('Real browser verification-code login '+phone.slice(-2)+' (dev delivery read from isolated Redis, not seeded token)');
  return page;
}
// Immediate navigation can discard Chromium's response body. Verify the transition and
// authoritative server state instead (authenticated identity / revoked token below).
async function navigationRequest(page,path,method,action) {
  const waiting=page.waitForResponse(r=>new URL(r.url()).pathname==='/api'+path && r.request().method()===method);
  const [response]=await Promise.all([waiting,action()]);assert.equal(response.status(),200);
}
async function business(page,path,method,action) {
  // Start reading on the response event, before click() finishes waiting for navigation.
  const waiting=page.waitForResponse(r=>new URL(r.url()).pathname=== '/api'+path && r.request().method()===method)
    .then(async response=>({status:response.status(),data:await response.json()}));
  const [result]=await Promise.all([waiting,action()]);
  assert.equal(result.data.success,true,JSON.stringify({path,...result})); return result.data;
}
async function api(page,path,method='GET',body) {
  return page.evaluate(async ({path,method,body})=>{
    const r=await fetch('/api'+path,{method,headers:{authorization:sessionStorage.getItem('token')||'','Content-Type':'application/json'},body:body===undefined?undefined:JSON.stringify(body)});
    return {status:r.status,body:await r.json().catch(()=>null)};
  },{path,method,body});
}
const png={name:'acceptance.png',mimeType:'image/png',buffer:Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+j5l0AAAAASUVORK5CYII=','base64')};
try {
  const author=await user('13900000002');
  await author.goto(base+'/info-edit.html');
  await author.waitForFunction(()=>document.querySelector('.profile-fields .el-input__inner')?.value.length>0);
  const avatar=await business(author,'/upload/blog','POST',()=>author.locator('input[type=file]').setInputFiles(png));
  await author.locator('.profile-fields .el-input__inner').first().fill('验收作者');
  await business(author,'/user/profile','PUT',()=>author.locator('.profile-save').click()); await author.waitForURL(url=>url.pathname==='/info.html');
  assert.equal((await api(author,'/upload/blog?name='+encodeURIComponent(avatar.data),'DELETE')).body.success,false);
  checks.push('Profile/avatar save and referenced-avatar deletion protection');
  await author.goto(base+'/blog-edit.html');
  await business(author,'/upload/blog','POST',()=>author.locator('input[type=file]').setInputFiles(png));
  await author.locator('.pic-delete').waitFor();
  await business(author,'/upload/blog','DELETE',()=>author.locator('.pic-delete').click());
  await author.waitForFunction(()=>document.querySelectorAll('.pic-box').length===0);
  await business(author,'/upload/blog','POST',()=>author.locator('input[type=file]').setInputFiles(png));
  await author.locator('.blog-title input').fill('完整链路验收笔记');
  await author.locator('.blog-content textarea').fill('真实浏览器上传发布，不拦截或伪造 API。');
  await author.locator('.blog-shop').click(); await author.locator('.shop-item').first().click();
  const published=await business(author,'/blog','POST',()=>author.locator('.header-commit-btn').click());
  const blogId=published.data; await author.waitForURL(url=>url.pathname==='/info.html');
  checks.push('Browser upload, draft deletion, shop selection and blog publication');
  await author.goto(base+'/blog-detail.html?id='+blogId);
  await author.locator('.comment-editor textarea').fill('验收评论：保留原文与审计记录');
  const comment=await business(author,'/blog-comments','POST',()=>author.locator('.comment-submit').click());
  const commentId=comment.data.id; await author.locator('.comment-content').filter({hasText:'验收评论'}).waitFor();
  const admin=await user('13900000001'); await admin.goto(base+'/blog-detail.html?id='+blogId);
  await business(admin,'/blog/like/'+blogId,'PUT',()=>admin.locator('.foot-like').click());
  await admin.getByRole('button',{name:'关注',exact:true}).click();
  await admin.getByRole('button',{name:'取消关注',exact:true}).waitFor();
  await business(admin,'/blog-comments/like/'+commentId,'PUT',()=>admin.locator('.comment-like').first().click());
  await admin.getByRole('button',{name:'回复',exact:true}).first().click();
  await admin.locator('.comment-editor textarea').fill('验收回复');
  await business(admin,'/blog-comments','POST',()=>admin.locator('.comment-submit').click());
  checks.push('Real blog/comment likes, follow, comment creation and reply');
  await admin.getByRole('button',{name:'举报',exact:true}).first().click();
  await admin.locator('.el-message-box__input input').fill('验收举报理由');
  await business(admin,'/blog-comment-report/'+commentId,'POST',()=>admin.locator('.el-message-box__btns .el-button--primary').click());
  await admin.goto(base+'/moderation.html');
  const card=admin.locator('article[data-comment-id="'+commentId+'"]');await card.waitFor();
  await business(admin,'/blog-comment-report/admin/block/'+commentId,'PUT',()=>card.getByRole('button',{name:'屏蔽',exact:true}).click());
  const guest=await browser.newContext();contexts.push(guest);const publicPage=await guest.newPage();
  await publicPage.goto(base+'/blog-detail.html?id='+blogId);
  assert.equal((await api(publicPage,'/blog-comments/of/blog/'+blogId)).body.data.some(x=>x.id===commentId),false);
  assert.equal((await api(publicPage,'/blog-comments/replies/'+commentId)).body.success,false);
  await author.reload();await author.locator('.comment-blocked').waitFor();
  await admin.locator('#restore-id').fill(String(commentId));
  await business(admin,'/blog-comment-report/admin/restore/'+commentId,'PUT',()=>admin.getByRole('button',{name:'恢复指定评论'}).click());
  assert.equal((await api(publicPage,'/blog-comments/of/blog/'+blogId)).body.data.some(x=>x.id===commentId),true);
  await admin.getByRole('button',{name:'操作记录',exact:true}).click();
  await admin.locator('article').first().waitFor();
  await admin.screenshot({path:artifacts+'/moderation-desktop.png',fullPage:true});
  await admin.setViewportSize({width:390,height:844});
  assert.ok(await admin.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1));
  await admin.screenshot({path:artifacts+'/moderation-mobile.png',fullPage:true});
  checks.push('Report/block/restore/audit UI, hidden-root isolation and author exception; mobile layout');
  await author.goto(base+'/moderation.html'); await author.getByRole('alert').filter({hasText:'无管理权限'}).waitFor();
  checks.push('Non-admin management denial surfaced in UI');
  await author.goto(base+'/blog-detail.html?id='+blogId);await author.locator('.comment-action-danger').first().click();
  await business(author,'/blog-comments/'+commentId,'DELETE',()=>author.locator('.el-message-box__btns .el-button--primary').click());
  assert.equal((await api(publicPage,'/blog-comments/of/blog/'+blogId)).body.data.some(x=>x.id===commentId),false);
  await author.goto(base+'/info.html');const oldToken=await author.evaluate(()=>sessionStorage.getItem('token'));
  await navigationRequest(author,'/user/logout','POST',()=>author.locator('.logout-btn').click());await author.waitForURL(base+'/');
  assert.equal(await author.evaluate(()=>sessionStorage.getItem('token')),null);
  assert.equal((await author.request.get(base+'/api/user/me',{headers:{authorization:oldToken}})).status(),401);
  checks.push('Author deletion cascades replies; browser logout revokes server token');
  assert.deepEqual(errors,[]);
  fs.writeFileSync(artifacts+'/browser-acceptance.json',JSON.stringify({sha:process.env.GITHUB_SHA,browser:browser.version(),checks,errors,apiMocks:false},null,2));
  console.log('Real browser acceptance passed:',checks.length,'scenarios; API mocks disabled');
} catch(error) {
  fs.writeFileSync(artifacts+'/browser-failure.txt',String(error.stack));throw error;
} finally {for(const ctx of contexts)await ctx.close();await browser.close();}
