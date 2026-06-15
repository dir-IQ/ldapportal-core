// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Per-account dashboard layout customization. Stores the JSON shape produced
 * by the frontend layout store ({ version, metricCards, columns, panelsHidden })
 * as an opaque JSONB blob — the frontend owns the schema, the server just
 * persists it keyed on account id.
 */
@Entity
@Table(name = "dashboard_layouts")
@Getter
@Setter
@NoArgsConstructor
public class DashboardLayout {

    @Id
    @Column(name = "account_id")
    private UUID accountId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "layout", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> layout;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
