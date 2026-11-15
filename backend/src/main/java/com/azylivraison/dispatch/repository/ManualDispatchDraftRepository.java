package com.azylivraison.dispatch.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.azylivraison.dispatch.domain.ManualDispatchDraft;
import com.azylivraison.dispatch.service.DispatchConflictException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class ManualDispatchDraftRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public ManualDispatchDraftRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public Optional<ManualDispatchDraft> active(LocalDate date) {
        return read("active_service_date = ?", date);
    }

    public Optional<ManualDispatchDraft> find(UUID id, boolean lock) {
        return read("id = ?" + (lock ? " FOR UPDATE" : ""), id);
    }

    public Optional<ManualDispatchDraft> forPlan(UUID id) {
        return read("final_plan_id = ?", id);
    }

    public Optional<ManualDispatchDraft> replay(UUID actor, String key, String hash) {
        List<ManualDispatchDraft> rows = jdbc.query("""
                SELECT snapshot_json, request_hash FROM manual_dispatch_draft
                WHERE actor_account_id = ? AND idempotency_key = ?
                """, (rs, n) -> {
            if (!hash.equals(rs.getString("request_hash"))) {
                throw new DispatchConflictException("Cette clé a déjà été utilisée avec une autre sélection.");
            }
            return mapper.readValue(rs.getString("snapshot_json"), ManualDispatchDraft.class);
        }, actor, key);
        return rows.stream().findFirst();
    }

    private Optional<ManualDispatchDraft> read(String where, Object value) {
        return jdbc.query("SELECT snapshot_json FROM manual_dispatch_draft WHERE " + where,
                (rs, n) -> mapper.readValue(rs.getString("snapshot_json"), ManualDispatchDraft.class), value)
                .stream().findFirst();
    }

    public void insert(ManualDispatchDraft draft, UUID actor, String key, String hash) {
        jdbc.update("""
                INSERT INTO manual_dispatch_draft (
                    id, service_date, active_service_date, status, actor_account_id,
                    idempotency_key, request_hash, snapshot_json
                ) VALUES (?, ?, ?, 'DRAFT', ?, ?, ?, ?)
                """, draft.id(), draft.serviceDate(), draft.serviceDate(), actor, key, hash,
                mapper.writeValueAsString(draft));
    }

    public void update(ManualDispatchDraft draft, long expectedVersion) {
        int changed = jdbc.update("""
                UPDATE manual_dispatch_draft SET status = ?, version = ?, active_service_date = ?,
                    snapshot_json = ?, final_plan_id = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND version = ? AND status = 'DRAFT'
                """, draft.status(), draft.version(), "DRAFT".equals(draft.status()) ? draft.serviceDate() : null,
                mapper.writeValueAsString(draft), draft.finalPlanId(), draft.id(), expectedVersion);
        if (changed != 1) throw new DispatchConflictException("Le brouillon a changé. Rechargez-le avant de continuer.");
    }

    public void event(LocalDate date, UUID actor, String type, Object payload) {
        jdbc.update("""
                INSERT INTO delivery_operation_day_event
                    (id, service_date, event_type, actor_account_id, payload_json, created_at)
                VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), date, type, actor, mapper.writeValueAsString(payload));
    }
}
