#!/usr/bin/env sh
set -eu

COMPOSE="docker compose"
BASE_URL="${BASE_URL:-http://localhost:8080/api}"
TOKEN="ci-smoke-${COMPOSE_PROJECT_NAME:-disposable}-$$"
VOUCHER_ID="999"
USER_ID="1"

cleanup() {
  rm -f smoke-1.json smoke-2.json smoke-3.json smoke-4.json smoke-5.json
}
trap cleanup EXIT

mysql_exec() {
  $COMPOSE exec -T mysql sh -c \
    'exec mysql --protocol=TCP -h127.0.0.1 -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$@" "$MYSQL_DATABASE"' \
    sh "$@"
}

$COMPOSE up -d --build

echo "Waiting for the API..."
i=0
until curl -fsS "$BASE_URL/shop-type/list" >/dev/null 2>&1; do
  i=$((i + 1))
  if [ "$i" -ge 60 ]; then
    $COMPOSE logs app
    exit 1
  fi
  sleep 3
done

mysql_exec <<SQL
DELETE FROM tb_voucher_order WHERE voucher_id = $VOUCHER_ID;
DELETE FROM tb_seckill_voucher WHERE voucher_id = $VOUCHER_ID;
DELETE FROM tb_voucher WHERE id = $VOUCHER_ID;
INSERT INTO tb_voucher (id, shop_id, title, sub_title, rules, pay_value, actual_value, type, status)
VALUES ($VOUCHER_ID, 1, '可靠秒杀联调券', '仅用于联调', '测试数据', 1, 100, 1, 1);
INSERT INTO tb_seckill_voucher (voucher_id, stock, begin_time, end_time)
VALUES ($VOUCHER_ID, 10, NOW() - INTERVAL 1 HOUR, NOW() + INTERVAL 1 HOUR);
SQL

$COMPOSE exec -T redis redis-cli -n 6 DEL \
  "seckill:stock:$VOUCHER_ID" \
  "seckill:order:$VOUCHER_ID" \
  "login:token:$TOKEN" >/dev/null
$COMPOSE exec -T redis redis-cli -n 6 SET "seckill:stock:$VOUCHER_ID" 10 >/dev/null
$COMPOSE exec -T redis redis-cli -n 6 HSET "login:token:$TOKEN" id "$USER_ID" nickName smoke icon "" >/dev/null
$COMPOSE exec -T redis redis-cli -n 6 EXPIRE "login:token:$TOKEN" 1800 >/dev/null

pids=""
for n in 1 2 3 4 5; do
  curl -fsS -X POST -H "authorization: $TOKEN" \
    "$BASE_URL/voucher-order/seckill/$VOUCHER_ID" >"smoke-$n.json" &
  pids="$pids $!"
done
for pid in $pids; do wait "$pid"; done

# HTTP 200 alone does not mean that seckill accepted the request.
# Report business rejections immediately instead of timing out with zero orders.
accepted=$(grep -lE '"success"[[:space:]]*:[[:space:]]*true' smoke-[1-5].json | wc -l | tr -d '[:space:]')
if [ "$accepted" != "1" ]; then
  echo "Expected exactly one accepted request, got $accepted"
  for response in smoke-[1-5].json; do
    echo "--- $response ---"
    cat "$response"
    echo
  done
  mysql_exec -N -s -e "SELECT NOW(), @@session.time_zone, begin_time, end_time FROM tb_seckill_voucher WHERE voucher_id=$VOUCHER_ID"
  $COMPOSE exec -T app date
  $COMPOSE logs app rabbitmq
  exit 1
fi

order_count=0
i=0
while [ "$i" -lt 30 ]; do
  order_count=$(mysql_exec -N -s -e \
    "SELECT COUNT(*) FROM tb_voucher_order WHERE user_id=$USER_ID AND voucher_id=$VOUCHER_ID")
  [ "$order_count" = "1" ] && break
  i=$((i + 1))
  sleep 1
done
[ "$order_count" = "1" ] || {
  echo "Expected one order, got $order_count"
  $COMPOSE logs app rabbitmq
  exit 1
}

db_stock=$(mysql_exec -N -s -e \
  "SELECT stock FROM tb_seckill_voucher WHERE voucher_id=$VOUCHER_ID")
redis_stock=$($COMPOSE exec -T redis redis-cli -n 6 GET "seckill:stock:$VOUCHER_ID" | tr -d '\r')
[ "$db_stock" = "9" ] || { echo "Expected DB stock 9, got $db_stock"; exit 1; }
[ "$redis_stock" = "9" ] || { echo "Expected Redis stock 9, got $redis_stock"; exit 1; }

echo "Smoke test passed: one concurrent order, DB stock=$db_stock, Redis stock=$redis_stock"

# Exercise the real upload API, image volume and publication transaction with seeded sessions.
BASE_URL="$BASE_URL" TOKEN="$TOKEN" python3 scripts/upload-smoke-test.py
