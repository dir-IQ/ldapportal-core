// SPDX-License-Identifier: Apache-2.0
// Stable panel identifiers. NEVER rename — stored in layout JSON.
export const PANEL_IDS = Object.freeze({
  APPROVAL_AGING:    'approval-aging',
  DIRECTORIES:       'directories',
  RECENT_ACTIVITY:   'recent-activity',
  REPORT_JOBS:       'report-jobs',
  CAMPAIGN_PROGRESS: 'campaign-progress',
  AWARENESS:         'awareness',
  ACTION_REQUIRED:   'action-required',
  SUGGESTED_CONFIG:  'suggested-config',
})

export const METRIC_IDS = Object.freeze({
  ALERTS:    'alerts',
  SOD:       'sod',
  CAMPAIGN:  'campaign',
  APPROVALS: 'approvals',
  OVERDUE:   'overdue',
  USERS:     'users',
  GROUPS:    'groups',
})

// Panels + metric cards that only make sense when Compliance is enabled.
// These get filtered out automatically regardless of user layout.
export const COMPLIANCE_PANELS = new Set([
  PANEL_IDS.CAMPAIGN_PROGRESS,
])
export const COMPLIANCE_METRICS = new Set([
  METRIC_IDS.SOD,
  METRIC_IDS.CAMPAIGN,
  METRIC_IDS.OVERDUE,
])
// Metric cards that only render when Compliance is disabled — they stand in
// for the compliance-only cards above so the top row always has useful
// content in both modes.
export const NON_COMPLIANCE_METRICS = new Set([
  METRIC_IDS.USERS,
  METRIC_IDS.GROUPS,
])

// Human labels (used for the edit-mode visibility toggle tooltip).
export const PANEL_LABELS = Object.freeze({
  [PANEL_IDS.APPROVAL_AGING]:    'Approval Aging',
  [PANEL_IDS.DIRECTORIES]:       'Directories',
  [PANEL_IDS.RECENT_ACTIVITY]:   'Recent Activity',
  [PANEL_IDS.REPORT_JOBS]:       'Report Jobs',
  [PANEL_IDS.CAMPAIGN_PROGRESS]: 'Active Campaign Progress',
  [PANEL_IDS.AWARENESS]:         'Awareness',
  [PANEL_IDS.ACTION_REQUIRED]:   'Action Required',
  [PANEL_IDS.SUGGESTED_CONFIG]:  'Suggested Configuration',
})
export const METRIC_LABELS = Object.freeze({
  [METRIC_IDS.ALERTS]:    'Active Alerts',
  [METRIC_IDS.SOD]:       'Open SoD Violations',
  [METRIC_IDS.CAMPAIGN]:  'Campaign Completion',
  [METRIC_IDS.APPROVALS]: 'Pending Approvals',
  [METRIC_IDS.OVERDUE]:   'Overdue Campaigns',
  [METRIC_IDS.USERS]:     'Total Users',
  [METRIC_IDS.GROUPS]:    'Total Groups',
})

export const DEFAULT_LAYOUT = Object.freeze({
  version: 1,
  metricCards: Object.freeze({
    // Order includes both compliance and non-compliance metrics; the view's
    // feature-flag filter hides the ones that don't belong in the current
    // mode. Users / Groups sit at the end so they slot in alongside Alerts
    // and Approvals when compliance is off.
    order: Object.freeze([
      METRIC_IDS.ALERTS,
      METRIC_IDS.SOD,
      METRIC_IDS.CAMPAIGN,
      METRIC_IDS.APPROVALS,
      METRIC_IDS.OVERDUE,
      METRIC_IDS.USERS,
      METRIC_IDS.GROUPS,
    ]),
    hidden: Object.freeze([]),
  }),
  columns: Object.freeze({
    // Directories sits in col2 alongside the compliance-only Campaign Progress
    // panel. That way col2 still has meaningful content (Directories +
    // Awareness) when compliance is disabled and Campaign Progress is
    // flag-hidden.
    col1: Object.freeze([PANEL_IDS.APPROVAL_AGING, PANEL_IDS.RECENT_ACTIVITY, PANEL_IDS.REPORT_JOBS]),
    col2: Object.freeze([PANEL_IDS.DIRECTORIES, PANEL_IDS.CAMPAIGN_PROGRESS, PANEL_IDS.AWARENESS]),
    col3: Object.freeze([PANEL_IDS.ACTION_REQUIRED, PANEL_IDS.SUGGESTED_CONFIG]),
  }),
  panelsHidden: Object.freeze([]),
})

/**
 * Deep-clone the (frozen) default layout into a mutable object.
 */
export function cloneDefaults() {
  // Uses spreads off DEFAULT_LAYOUT so additions there flow through without
  // needing to update this helper.
  return {
    version: 1,
    metricCards: {
      order: [...DEFAULT_LAYOUT.metricCards.order],
      hidden: [...DEFAULT_LAYOUT.metricCards.hidden],
    },
    columns: {
      col1: [...DEFAULT_LAYOUT.columns.col1],
      col2: [...DEFAULT_LAYOUT.columns.col2],
      col3: [...DEFAULT_LAYOUT.columns.col3],
    },
    panelsHidden: [...DEFAULT_LAYOUT.panelsHidden],
  }
}


/**
 * Merge a stored layout with current defaults. Drops unknown IDs (forward
 * compat when panels are removed in a future version) and appends any missing
 * IDs to their default column (forward compat when new panels are added).
 */
export function mergeWithDefaults(stored) {
  if (!stored || typeof stored !== 'object' || stored.version !== 1) {
    return cloneDefaults()
  }
  const knownPanels = new Set(Object.values(PANEL_IDS))
  const knownMetrics = new Set(Object.values(METRIC_IDS))

  const result = {
    version: 1,
    metricCards: {
      order: dedupeAndFilter(stored.metricCards?.order, knownMetrics),
      hidden: (stored.metricCards?.hidden || []).filter(id => knownMetrics.has(id)),
    },
    columns: {
      col1: dedupeAndFilter(stored.columns?.col1, knownPanels),
      col2: dedupeAndFilter(stored.columns?.col2, knownPanels),
      col3: dedupeAndFilter(stored.columns?.col3, knownPanels),
    },
    panelsHidden: (stored.panelsHidden || []).filter(id => knownPanels.has(id)),
  }

  // Append missing metric IDs in default order.
  const metricPresent = new Set(result.metricCards.order)
  for (const id of DEFAULT_LAYOUT.metricCards.order) {
    if (!metricPresent.has(id)) result.metricCards.order.push(id)
  }

  // Append missing panel IDs to their default column.
  const panelPresent = new Set([...result.columns.col1, ...result.columns.col2, ...result.columns.col3])
  for (const [colKey, ids] of Object.entries(DEFAULT_LAYOUT.columns)) {
    for (const id of ids) {
      if (!panelPresent.has(id)) result.columns[colKey].push(id)
    }
  }

  return result
}

function dedupeAndFilter(list, known) {
  if (!Array.isArray(list)) return []
  const seen = new Set()
  const out = []
  for (const id of list) {
    if (typeof id !== 'string' || !known.has(id) || seen.has(id)) continue
    seen.add(id)
    out.push(id)
  }
  return out
}

