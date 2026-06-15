// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entity.enums;

public enum InputType {
    TEXT,
    TEXTAREA,
    PASSWORD,
    BOOLEAN,
    DATE,
    DATETIME,
    MULTI_VALUE,
    /** DN value chosen via the DN picker (browse the directory tree). */
    DN_LOOKUP,
    /** DN value the operator types in directly; same DN-syntax validation as {@link #DN_LOOKUP}. */
    DN,
    SELECT,
    HIDDEN_FIXED
}
