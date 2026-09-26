package com.yuelin.service;

import com.yuelin.entity.VoucherOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import javax.annotation.Resource;
import java.util.List;
import java.util.Map;

/** Serialize cancellation against the database order transaction, including late deliveries. */
@Service
public class OrderResolutionService {
    @Resource private JdbcTemplate jdbcTemplate;

    public static class Cancelled extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public Cancelled() { super("Reservation was cancelled"); }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void authorize(VoucherOrder order) {
        if ("CANCELLED".equals(lock(order).get("state"))) { throw new Cancelled(); }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void committed(VoucherOrder order) {
        jdbcTemplate.update("UPDATE tb_order_resolution SET state='COMMITTED' WHERE order_id=?", order.getId());
    }

    @Transactional
    public boolean cancel(VoucherOrder order) {
        lock(order);
        List<Map<String,Object>> existing = jdbcTemplate.queryForList(
                "SELECT user_id,voucher_id FROM tb_voucher_order WHERE id=? FOR UPDATE", order.getId());
        if (!existing.isEmpty()) {
            validateIdentity(existing.get(0), order);
            jdbcTemplate.update("UPDATE tb_order_resolution SET state='COMMITTED' WHERE order_id=?", order.getId());
            return false;
        }
        jdbcTemplate.update("UPDATE tb_order_resolution SET state='CANCELLED' WHERE order_id=?", order.getId());
        return true;
    }

    private Map<String,Object> lock(VoucherOrder order) {
        if (order == null || order.getId() == null || order.getId() <= 0 || order.getUserId() == null
                || order.getUserId() <= 0 || order.getVoucherId() == null || order.getVoucherId() <= 0) {
            throw new IllegalArgumentException("Invalid order identity");
        }
        jdbcTemplate.update("INSERT IGNORE INTO tb_order_resolution (order_id,user_id,voucher_id,state) VALUES (?,?,?,'PENDING')",
                order.getId(), order.getUserId(), order.getVoucherId());
        Map<String,Object> row = jdbcTemplate.queryForMap("SELECT user_id,voucher_id,state FROM tb_order_resolution WHERE order_id=? FOR UPDATE", order.getId());
        validateIdentity(row, order);
        return row;
    }

    private void validateIdentity(Map<String,Object> row, VoucherOrder order) {
        if (((Number)row.get("user_id")).longValue() != order.getUserId()
                || ((Number)row.get("voucher_id")).longValue() != order.getVoucherId()) {
            throw new IllegalArgumentException("Order identity mismatch");
        }
    }
}
