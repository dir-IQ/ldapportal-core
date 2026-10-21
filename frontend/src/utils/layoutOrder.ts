// SPDX-License-Identifier: Apache-2.0
/**
 * The self-registration and self-service form layouts are ordered independently
 * of the admin form, each by its own saved display order.
 */
export type LayoutOrderKey = 'registrationDisplayOrder' | 'selfServiceDisplayOrder'

/**
 * Comparator ordering attribute configs by a layout's saved order. Fields
 * without one (layout never customised, or newly enabled since) sort after the
 * ordered ones and — Array.prototype.sort being stable — keep their incoming
 * (admin) order among themselves.
 */
export function byLayoutOrder(key: LayoutOrderKey) {
  return (a: Partial<Record<LayoutOrderKey, number | null>>, b: Partial<Record<LayoutOrderKey, number | null>>) =>
    (a[key] ?? Number.MAX_SAFE_INTEGER) - (b[key] ?? Number.MAX_SAFE_INTEGER)
}
