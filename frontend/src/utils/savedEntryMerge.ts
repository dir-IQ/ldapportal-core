// SPDX-License-Identifier: Apache-2.0
/**
 * Merge an entry-update read-back into a directory-search row.
 *
 * Two traps this exists to avoid:
 *  - the entry endpoints lower-case attribute keys while search results
 *    keep the server's casing — a naive spread duplicated table columns
 *    (departmentNumber + departmentnumber) and left the stale value under
 *    the old key;
 *  - the read-back is a full "*" entry — adopting every attribute would
 *    grow the table's column set after each save.
 *
 * So: re-key the response case-insensitively to the casing already on
 * screen (the row's keys plus the table's column keys) and accept only
 * attributes that map to one of them. Row attributes the server no longer
 * returns disappear — that's how value deletions reach the table.
 */
export function mergeSavedEntryAttributes(
  columnKeys: Iterable<string>,
  rowAttributes: Record<string, string[]>,
  responseAttributes: Record<string, string[]>,
): Record<string, string[]> {
  const caseMap = new Map<string, string>()
  for (const col of columnKeys) caseMap.set(col.toLowerCase(), col)
  for (const key of Object.keys(rowAttributes ?? {})) caseMap.set(key.toLowerCase(), key)
  const merged: Record<string, string[]> = {}
  for (const [key, values] of Object.entries(responseAttributes ?? {})) {
    const display = caseMap.get(key.toLowerCase())
    if (display) merged[display] = values
  }
  return merged
}
