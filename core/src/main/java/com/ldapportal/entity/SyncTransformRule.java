// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entity;

/**
 * One attribute rename / value-template rule, stored as an element of a
 * {@link SyncSet}'s {@code transform_rules} JSON array.
 *
 * <p>{@code valueTemplate} supports a single {@code ${value}} substitution
 * token; null/blank or a bare {@code ${value}} means pass the value through
 * unchanged. No scripting or conditional logic (v1, matching the legacy
 * AttributeMapper semantics).
 *
 * <p>A plain class (not a record) so Jackson/Hibernate JSON can deserialize it
 * from a partial object during config evolution.
 */
public class SyncTransformRule {

    private String sourceAttr;
    private String targetAttr;
    private String valueTemplate;

    public SyncTransformRule() {
    }

    public SyncTransformRule(String sourceAttr, String targetAttr, String valueTemplate) {
        this.sourceAttr = sourceAttr;
        this.targetAttr = targetAttr;
        this.valueTemplate = valueTemplate;
    }

    public String getSourceAttr() {
        return sourceAttr;
    }

    public void setSourceAttr(String sourceAttr) {
        this.sourceAttr = sourceAttr;
    }

    public String getTargetAttr() {
        return targetAttr;
    }

    public void setTargetAttr(String targetAttr) {
        this.targetAttr = targetAttr;
    }

    public String getValueTemplate() {
        return valueTemplate;
    }

    public void setValueTemplate(String valueTemplate) {
        this.valueTemplate = valueTemplate;
    }
}
