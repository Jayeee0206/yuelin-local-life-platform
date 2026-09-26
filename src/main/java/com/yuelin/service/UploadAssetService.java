package com.yuelin.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.Set;

/** MySQL owns upload permissions; Redis expiry must never erase ownership. */
@Service
public class UploadAssetService {
    public enum DeleteDecision { DENIED, DELETE, ALREADY_DELETED }
    @Resource
    private JdbcTemplate jdbcTemplate;

    public static String canonical(String name) {
        if (name == null) { throw new IllegalArgumentException("错误的文件名称"); }
        String path = name.startsWith("/uploads/") ? name.substring(9)
                : name.startsWith("/") ? name.substring(1) : name;
        if (!path.matches("blogs/(?:[0-9]|1[0-5])/(?:[0-9]|1[0-5])/[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}\\.(?:jpg|jpeg|png|webp|gif)")) {
            throw new IllegalArgumentException("错误的文件名称");
        }
        return path;
    }

    @Transactional
    public void reserve(String path, Long owner) {
        if (owner == null || owner <= 0) { throw new IllegalArgumentException("请先登录"); }
        jdbcTemplate.update("INSERT INTO tb_upload_asset (path,user_id,state) VALUES (?,?,'UPLOADING')", canonical(path), owner);
    }

    @Transactional
    public void ready(String path, Long owner) {
        if (jdbcTemplate.update("UPDATE tb_upload_asset SET state='READY' WHERE path=? AND user_id=? AND state='UPLOADING'",
                canonical(path), owner) != 1) { throw new IllegalStateException("Upload registration failed"); }
    }

    @Transactional
    public void failed(String path, Long owner) {
        jdbcTemplate.update("UPDATE tb_upload_asset SET state='FAILED' WHERE path=? AND user_id=? AND state='UPLOADING'", canonical(path), owner);
    }

    @Transactional
    public DeleteDecision beginDelete(String name, Long owner) {
        String path = canonical(name);
        Map<String, Object> row = lock(path);
        if (row == null || owner == null || ((Number) row.get("user_id")).longValue() != owner) { return DeleteDecision.DENIED; }
        String state = (String) row.get("state");
        if ("DELETED".equals(state)) { return DeleteDecision.ALREADY_DELETED; }
        if (!"READY".equals(state) && !"DELETING".equals(state)) { return DeleteDecision.DENIED; }
        // Defense for verified imports. Published new assets are additionally protected by BOUND.
        if (!jdbcTemplate.queryForList("SELECT id FROM tb_blog WHERE FIND_IN_SET(?,images)>0 "
                + "OR FIND_IN_SET(?,images)>0 OR FIND_IN_SET(?,images)>0 LIMIT 1", Long.class,
                path, "/" + path, "/uploads/" + path).isEmpty()) { return DeleteDecision.DENIED; }
        if (!jdbcTemplate.queryForList("SELECT id FROM tb_user WHERE icon=? OR icon=? LIMIT 1", Long.class,
                "/uploads/" + path, "/" + path).isEmpty()) { return DeleteDecision.DENIED; }
        jdbcTemplate.update("UPDATE tb_upload_asset SET state='DELETING' WHERE path=?", path);
        return DeleteDecision.DELETE;
    }

    @Transactional
    public void finishDelete(String name, Long owner) {
        String path = canonical(name);
        Map<String, Object> row = lock(path);
        if (row == null || owner == null || ((Number) row.get("user_id")).longValue() != owner) {
            throw new IllegalStateException("Upload deletion acknowledgement failed");
        }
        if ("DELETED".equals(row.get("state"))) { return; }
        if (!"DELETING".equals(row.get("state"))) { throw new IllegalStateException("Upload is not being deleted"); }
        jdbcTemplate.update("UPDATE tb_upload_asset SET state='DELETED' WHERE path=?", path);
    }

    /** Must share the blog insert transaction. Stable path order prevents multi-image lock inversion. */
    @Transactional(propagation = Propagation.MANDATORY)
    public String attach(String images, Long owner, Long blogId) {
        if (images == null || images.trim().isEmpty()) { throw new IllegalArgumentException("请先上传图片"); }
        String[] names = images.split(",", -1);
        if (names.length > 9) { throw new IllegalArgumentException("最多上传9张图片"); }
        List<String> ordered = new ArrayList<>();
        Set<String> sorted = new TreeSet<>();
        for (String name : names) {
            String path = canonical(name.trim());
            if (!sorted.add(path)) { throw new IllegalArgumentException("图片不能重复"); }
            ordered.add("/" + path);
        }
        for (String path : sorted) {
            Map<String, Object> row = lock(path);
            if (row == null || owner == null || ((Number) row.get("user_id")).longValue() != owner
                    || !"READY".equals(row.get("state"))) {
                throw new IllegalArgumentException("图片不可用或不属于当前用户，请重新上传");
            }
            jdbcTemplate.update("UPDATE tb_upload_asset SET state='BOUND',blog_id=? WHERE path=?", blogId, path);
        }
        return String.join(",", ordered);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void bindAvatar(String icon, Long owner) {
        List<String> icons = jdbcTemplate.queryForList("SELECT icon FROM tb_user WHERE id=? FOR UPDATE", String.class, owner);
        if (icons.isEmpty()) { throw new IllegalArgumentException("用户不存在"); }
        String old = icons.get(0);
        String next = icon == null ? "" : icon;
        if (next.equals(old)) { return; }
        String newPath = next.startsWith("/uploads/") ? canonical(next) : null;
        String oldPath = null;
        if (old != null && old.startsWith("/uploads/")) {
            try { oldPath = canonical(old); }
            catch (IllegalArgumentException invalidPath) { oldPath = null; /* Unregistered icon stays protected. */ }
        }
        Set<String> paths = new TreeSet<>();
        if (newPath != null) { paths.add(newPath); }
        if (oldPath != null) { paths.add(oldPath); }
        for (String path : paths) {
            Map<String,Object> row = lock(path);
            if (path.equals(newPath)) {
                if (row == null || ((Number)row.get("user_id")).longValue() != owner || !"READY".equals(row.get("state"))) {
                    throw new IllegalArgumentException("头像图片不可用或不属于当前用户");
                }
                jdbcTemplate.update("UPDATE tb_upload_asset SET state='AVATAR' WHERE path=?", path);
            } else if (row != null && ((Number)row.get("user_id")).longValue() == owner && "AVATAR".equals(row.get("state"))) {
                jdbcTemplate.update("UPDATE tb_upload_asset SET state='READY' WHERE path=?", path);
            }
        }
    }

    private Map<String, Object> lock(String path) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT user_id,state,blog_id FROM tb_upload_asset WHERE path=? FOR UPDATE", path);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
