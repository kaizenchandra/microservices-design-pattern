package com.synechis.fulfillment.runtime;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
public class QuarantineController {
    private final Database db;

    public QuarantineController(Database db) {
        this.db = db;
    }

    @GetMapping("/admin/quarantine")
    List<Map<String, Object>> list() {
        return db.sql.queryForList("select partition_id,position,reason,approved_skip,created_at from quarantine order by created_at desc limit 100");
    }

    @PostMapping("/admin/quarantine/{partition}/{offset}/skip")
    void skip(@PathVariable int partition, @PathVariable long offset) {
        db.sql.update("update quarantine set approved_skip=true where partition_id=? and position=?", partition, offset);
    }
}
