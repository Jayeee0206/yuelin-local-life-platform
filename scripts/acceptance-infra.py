#!/usr/bin/env python3
"""Destructive fault/benchmark fixtures ONLY for the disposable CI Compose project."""
import concurrent.futures
import base64
import hashlib
import http.client
import json
import os
from pathlib import Path
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request

if os.environ.get('YUELIN_DISPOSABLE_ACCEPTANCE') != '1' or not os.environ.get('COMPOSE_PROJECT_NAME', '').startswith('yuelin-ci-'):
    raise SystemExit('Disposable acceptance guard refused this environment')
BASE = os.environ.get('BASE_URL', 'http://localhost:8080/api')
ART = Path('ci-artifacts'); ART.mkdir(exist_ok=True)
checks = []
results = {'checks': checks, 'sha': os.environ.get('GITHUB_SHA'), 'environment': 'isolated shared GitHub runner; not production capacity'}

def docker(*args, input=None):
    return subprocess.run(['docker', 'compose', *args], input=input, capture_output=True, text=True, timeout=150, check=True).stdout.strip()


def sql(statement):
    return docker('exec', '-T', 'mysql', 'sh', '-c',
                  'exec mysql --protocol=TCP -h127.0.0.1 -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" -N -s "$MYSQL_DATABASE"', input=statement)


def redis(*args):
    return docker('exec', '-T', 'redis', 'redis-cli', '-n', '6', *map(str,args))


def api(path, token=None):
    request=urllib.request.Request(BASE+path, data=b'', method='POST', headers={'authorization':token or ''})
    try:
        with urllib.request.urlopen(request,timeout=40) as response: return response.status,json.load(response)
    except urllib.error.HTTPError as error:
        return error.code,json.loads(error.read() or '{}')


def broker(method,path,data=None):
    password=os.environ.get('RABBITMQ_PASSWORD','yuelin_dev')
    auth=base64.b64encode(('yuelin:'+password).encode()).decode()
    request=urllib.request.Request('http://localhost:15672/api/'+path,method=method,
         data=None if data is None else json.dumps(data).encode(),headers={'Authorization':'Basic '+auth,'Content-Type':'application/json'})
    with urllib.request.urlopen(request,timeout=20) as response:
        raw=response.read();return json.loads(raw) if raw else None


def wait(predicate,label,seconds=100):
    deadline=time.monotonic()+seconds;last=None
    while time.monotonic()<deadline:
        try:
            if predicate(): return
        except (subprocess.SubprocessError,OSError,http.client.HTTPException,ValueError) as error: last=type(error).__name__
        time.sleep(1)
    raise AssertionError('Timed out: '+label+' last='+str(last))


def log(label):
    checks.append(label); print('PASS:',label,flush=True)
    (ART/'infrastructure-acceptance.json').write_text(json.dumps(results,indent=2,ensure_ascii=False))


def fixture(voucher, stock=10):
    sql(f"INSERT INTO tb_voucher (id,shop_id,title,sub_title,rules,pay_value,actual_value,type,status) VALUES ({voucher},1,'acceptance fixture','','',1,100,1,1); "
        f"INSERT INTO tb_seckill_voucher (voucher_id,stock,begin_time,end_time) VALUES ({voucher},{stock},NOW()-INTERVAL 1 HOUR,NOW()+INTERVAL 1 HOUR);")
    redis('SET','seckill:stock:'+str(voucher),stock)


def token(user):
    value='acceptance-'+str(user)
    redis('HSET','login:token:'+value,'id',user,'nickName','fixture','icon','')
    redis('EXPIRE','login:token:'+value,1800)
    return value


def reserve(voucher,user,oid):
    assert redis('EVAL',Path('src/main/resources/seckill.lua').read_text(),0,voucher,user,oid,int(time.time()*1000))=='0'
    return {'id':oid,'userId':user,'voucherId':voucher}

EXCHANGE='yuelin.seckill.exchange'; QUEUE='yuelin.seckill.order.queue'; ROUTE='yuelin.seckill.order'
BIND='bindings/%2F/e/'+EXCHANGE+'/q/'+QUEUE

def binding(): broker('POST',BIND,{'routing_key':ROUTE,'arguments':{}})

def publish(order):
    payload=order if isinstance(order,str) else json.dumps(order)
    assert broker('POST','exchanges/%2F/'+EXCHANGE+'/publish',{'properties':{'content_type':'text/plain','delivery_mode':2},'routing_key':ROUTE,'payload':payload,'payload_encoding':'string'})['routed']


def settled(voucher,expected,stock=10):
    return (sql(f'SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id={voucher};')==str(expected)
        and sql(f'SELECT stock FROM tb_seckill_voucher WHERE voucher_id={voucher};')==str(stock-expected)
        and redis('GET','seckill:stock:'+str(voucher))==str(stock-expected)
        and redis('HGET','seckill:reservation:pending-count',voucher) in ('','0'))


def ready():
    try:
        with urllib.request.urlopen(BASE+'/shop-type/list',timeout=5) as r:return r.status==200
    except urllib.error.URLError:return False


def run():
    # Reconstruct the exact DB-commit / Redis-completion crash window with real stores, then real broker delivery.
    fixture(910001);order=reserve(910001,31001,1910001)
    sql("INSERT INTO tb_voucher_order (id,user_id,voucher_id) VALUES (1910001,31001,910001); UPDATE tb_seckill_voucher SET stock=9 WHERE voucher_id=910001;")
    publish(order);wait(lambda:settled(910001,1),'commit/ack recovery')
    for _ in range(5):publish(order)
    time.sleep(3);assert settled(910001,1)
    log('DB-commit/Redis-ack window and duplicate broker delivery do not refund or deduct twice')
    # Mandatory Return from a real broker, followed by deliberately late redelivery.
    fixture(910002);auth=token(31002)
    for item in broker('GET',BIND):broker('DELETE',BIND+'/'+urllib.parse.quote(item['properties_key'],safe=''))
    try:
        status,response=api('/voucher-order/seckill/910002',auth)
        assert response.get('success'),(status,response)
        oid=int(response['data']);wait(lambda:settled(910002,0),'mandatory return compensation')
    finally:binding()
    publish({'id':oid,'userId':31002,'voucherId':910002});time.sleep(3);assert settled(910002,0)
    assert sql(f"SELECT state FROM tb_order_resolution WHERE order_id={oid};")=='CANCELLED'
    log('Real mandatory Return, compensation fence and rejected late delivery')
    # Broker channel rejection generates a negative confirm / nack in Spring Rabbit.
    fixture(910003);auth=token(31003)
    broker('DELETE','exchanges/%2F/'+EXCHANGE)
    try:
        api('/voucher-order/seckill/910003',auth)
        wait(lambda:settled(910003,0) and sql('SELECT COUNT(*) FROM tb_order_resolution WHERE voucher_id=910003;')=='1','broker nack compensation')
        wait(lambda:'Publisher nack received:' in docker('logs','--no-color','app'),'negative confirm evidence',30)
    finally:
        broker('PUT','exchanges/%2F/'+EXCHANGE,{'type':'topic','durable':True,'auto_delete':False,'internal':False,'arguments':{}});binding()
    log('Real broker negative confirm after exchange removal restores reservation safely')
    # Real timeout exhaustion: broker acks messages into a holding queue with no consumer.
    # This avoids Return/Nack, forcing scheduled retries and eventual cancellation.
    fixture(910008);auth=token(31008);hold='yuelin.acceptance.hold'
    broker('PUT','queues/%2F/'+hold,{'durable':False,'auto_delete':False,'arguments':{}})
    broker('POST','bindings/%2F/e/'+EXCHANGE+'/q/'+hold,{'routing_key':ROUTE,'arguments':{}})
    for item in broker('GET',BIND):broker('DELETE',BIND+'/'+urllib.parse.quote(item['properties_key'],safe=''))
    try:
        status,response=api('/voucher-order/seckill/910008',auth);assert response.get('success'),(status,response)
        oid=int(response['data'])
        wait(lambda:settled(910008,0) and sql(f"SELECT state FROM tb_order_resolution WHERE order_id={oid};")=='CANCELLED','scheduled retry exhaustion',100)
        wait(lambda:broker('GET','queues/%2F/'+hold).get('messages',0)>=4,'original message plus three retries',20)
        results['timeout_compensation']={'held_messages':broker('GET','queues/%2F/'+hold)['messages'],'state':'CANCELLED'}
    finally:
        binding();broker('DELETE','queues/%2F/'+hold)
    publish({'id':oid,'userId':31008,'voucherId':910008});time.sleep(3);assert settled(910008,0)
    log('Real acknowledged-but-unconsumed messages exhaust three scheduled retries; compensation and late-delivery fence converge')
    # Deterministically reserve with the real Lua, then kill the live application before timeout/publish.
    fixture(910004);reserve(910004,31004,1910004);docker('kill','-s','SIGKILL','app')
    docker('start','app');wait(ready,'app restart');wait(lambda:settled(910004,1),'reservation reconciliation after crash')
    log('SIGKILL/restart: unpublished Redis reservation recovered by scheduled reconciler')
    # Durable queue survives broker and consumer restart.
    fixture(910005);docker('stop','app');order=reserve(910005,31005,1910005);publish(order)
    docker('restart','rabbitmq');wait(lambda:broker('GET','overview') is not None,'broker restart')
    docker('start','app');wait(ready,'consumer restart');wait(lambda:settled(910005,1),'durable queued delivery')
    log('Durable broker queue and consumer restart preserve one order and stock')
    # Database failure during consumption, not only at the HTTP validation boundary.
    fixture(910006);order=reserve(910006,31006,1910006);docker('stop','mysql');publish(order);time.sleep(6)
    docker('start','mysql');wait(lambda:sql('SELECT 1;')=='1','database recovery');wait(lambda:settled(910006,1),'database outage recovery',150)
    log('Database stopped during consumption: retry/reconciliation converges after restart')
    dlq='queues/%2F/yuelin.seckill.order.dlq'
    before=broker('GET',dlq).get('messages',0);publish('not-a-valid-order')
    wait(lambda:broker('GET',dlq).get('messages',0)>before,'poison message dead lettering',60)
    log('Poison message retries exhaust into real DLQ; message retained, not silently discarded')
    # A measured multi-user load, with business rejections distinguished from transport failures.
    users=list(range(32000,32048));fixture(910007,20);auths={u:token(u) for u in users}
    def attempt(user):
        start=time.perf_counter();status,value=api('/voucher-order/seckill/910007',auths[user]);return (time.perf_counter()-start)*1000,status,value.get('success',False)
    start=time.perf_counter()
    with concurrent.futures.ThreadPoolExecutor(max_workers=16) as pool: samples=list(pool.map(attempt,users*2))
    elapsed=time.perf_counter()-start;latency=sorted(s[0] for s in samples)
    accepted=sum(1 for _,_,success in samples if success);assert accepted==20,accepted
    assert all(status==200 for _,status,_ in samples),samples
    wait(lambda:settled(910007,20,20),'multi-user final reconciliation')
    assert sql('SELECT COUNT(*) FROM (SELECT user_id FROM tb_voucher_order WHERE voucher_id=910007 GROUP BY user_id HAVING COUNT(*)>1) t;')=='0'
    results['load']={'users':48,'requests':len(samples),'workers':16,'accepted':accepted,'expected_business_rejections':len(samples)-accepted,
                     'transport_errors':0,'elapsed_seconds':elapsed,'request_throughput_per_second':len(samples)/elapsed,
                     'p50_ms':latency[int(len(latency)*.50)],'p95_ms':latency[int(len(latency)*.95)],'p99_ms':latency[min(len(latency)-1,int(len(latency)*.99))],
                     'database_stock':0,'redis_stock':0,'orders':20,'pending':0,'duplicate_user_orders':0}
    log('48-user / 96-request / 16-worker measured load: stock 20, exactly 20 unique orders, no drift')
    (ART/'resources-before-comments.json').write_text(docker('stats','--no-stream','--format','json'))
    # 10,000-reply content fixture, bounded preview and observed request latency.
    blog=920001
    sql(f"INSERT INTO tb_blog (id,shop_id,user_id,title,images,content,liked,comments) VALUES ({blog},1,1,'large reply fixture','','fixture',0,10010);")
    rows=[]
    for parent in range(920100,920110):
        rows.append(f"({parent},{blog},1,0,0,'root',0,0)")
        rows.extend(f"({parent*10000+j},{blog},1,{parent},0,'reply',0,0)" for j in range(1,1001))
    for offset in range(0,len(rows),1000):sql('INSERT INTO tb_blog_comments (id,blog_id,user_id,parent_id,answer_id,content,liked,status) VALUES '+','.join(rows[offset:offset+1000])+';')
    metrics=[]
    for _ in range(20):
        start=time.perf_counter()
        with urllib.request.urlopen(BASE+'/blog-comments/of/blog/'+str(blog),timeout=20) as r:body=r.read();value=json.loads(body)
        assert len(value['data'])==10 and all(len(root['replies'])==3 and root['replyCount']==1000 for root in value['data'])
        metrics.append(((time.perf_counter()-start)*1000,len(body)))
    (ART/'comment-explain.txt').write_text(sql(f'EXPLAIN SELECT id FROM tb_blog_comments WHERE blog_id={blog} AND parent_id=920100 AND (status IS NULL OR status<>2) ORDER BY create_time,id LIMIT 3;'))
    results['large_comments']={'replies':10000,'roots_per_page':10,'previews_per_root':3,'samples':20,'max_response_bytes':max(m[1] for m in metrics),'max_latency_ms':max(m[0] for m in metrics)}
    (ART/'resources-after-comments.json').write_text(docker('stats','--no-stream','--format','json'))
    log('10,000-reply fixture: 30 previews, exact visible counts, measured latency/response size and SQL plan')
    # Rehearse restoring this test-only audit table from a real mysqldump, with row checksum comparison.
    sql("INSERT INTO tb_comment_moderation_audit (comment_id,actor_id,action) VALUES (920001,1,'DISMISS');")
    original=sql('SELECT * FROM tb_comment_moderation_audit ORDER BY id;')
    dump=docker('exec','-T','mysql','sh','-c','exec mysqldump --no-tablespaces --single-transaction -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE" tb_comment_moderation_audit')
    assert original and 'CREATE TABLE' in dump
    sql('DELETE FROM tb_comment_moderation_audit;');sql(dump)
    restored=sql('SELECT * FROM tb_comment_moderation_audit ORDER BY id;');assert original==restored
    results['audit_restore_sha256']=hashlib.sha256(restored.encode()).hexdigest()
    log('Disposable audit-table backup/restore preserves every recorded moderation operation')
    results['queue']=broker('GET','queues/%2F/'+QUEUE)
    results['queue']={key:results['queue'].get(key) for key in ('messages','messages_ready','messages_unacknowledged','consumers')}
    wait(lambda:broker('GET','queues/%2F/'+QUEUE).get('messages',0)==0,'main queue drain')
    assert redis('HLEN','seckill:reservation:pending')=='0'
    log('Final reconciliation: primary queue drained and global pending reservation count zero; DLQ evidence retained')

try:run()
except Exception as error:
    results['failure']=repr(error);(ART/'infrastructure-acceptance.json').write_text(json.dumps(results,indent=2,ensure_ascii=False));raise
