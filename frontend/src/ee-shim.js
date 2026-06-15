// SPDX-License-Identifier: Apache-2.0
// Community-edition stub for the `@/ee` seam.
//
// In a community build, vite.config.js aliases every `@/ee` / `@/ee/*` import
// to this file, so NO commercial source under `src/ee/` enters the bundle.
// It must export the same names as `src/ee/index.js`. Everything here is inert:
// empty route lists, a render-nothing panel, and resolved-empty API calls.
// Core only invokes these behind entitlement guards (compliance/alerting),
// which are always off in community, so the stubs are belt-and-suspenders.

export const eeAppShellRoutes = [];
export const eeTopLevelRoutes = [];

export const CampaignProgressPanel = {
  name: 'CampaignProgressPanelStub',
  render: () => null,
};

// Rest params so callers that pass (dirId, params) typecheck against the stub
// (the real src/ee fns take args); id is undefined (not null) so it's assignable
// to the callers' `string | undefined` targets.
export const listCampaigns = async (..._args) => ({ data: { totalElements: 0, content: [] } });
export const createCampaign = async (..._args) => ({ data: { id: undefined } });
export const getAlertSummary = async (..._args) => ({ data: { criticalCount: 0, highCount: 0 } });
