// SPDX-License-Identifier: Apache-2.0
import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { myProfiles } from '@/api/auth'
import { eeAppShellRoutes, eeTopLevelRoutes } from '@/ee'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    // ── Public ─────────────────────────────────────────────────────────────
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/LoginView.vue'),
      meta: { public: true },
    },
    {
      path: '/oidc/callback',
      name: 'oidcCallback',
      component: () => import('@/views/OidcCallbackView.vue'),
      meta: { public: true },
    },

    // ── Setup Wizard (own layout, outside AppLayout) ──────────────────────
    {
      path: '/setup',
      name: 'setup',
      component: () => import('@/views/SetupWizardView.vue'),
    },

    // ── App shell ───────────────────────────────────────────────────────────
    {
      path: '/',
      component: () => import('@/components/AppLayout.vue'),
      children: [
        { path: '', name: 'home', redirect: () => '/dashboard' },
        { path: 'no-access', name: 'noAccess', component: { template: '<div />' } },

        // Unified Dashboard (admin + superadmin; scope resolved server-side)
        {
          path: 'dashboard',
          name: 'adminDashboard',
          component: () => import('@/views/DashboardView.vue'),
        },

        // Notifications
        {
          path: 'notifications',
          name: 'notifications',
          component: () => import('@/views/NotificationsView.vue'),
        },

        // Users
        {
          path: 'directories/:dirId/users',
          name: 'users',
          component: () => import('@/views/users/UserListView.vue'),
        },

        // Groups
        {
          path: 'directories/:dirId/groups',
          name: 'groups',
          component: () => import('@/views/groups/GroupListView.vue'),
        },

        // Audit log
        {
          path: 'directories/:dirId/audit',
          name: 'audit',
          component: () => import('@/views/audit/AuditLogView.vue'),
        },

        // Bulk CSV
        {
          path: 'directories/:dirId/bulk',
          name: 'bulk',
          component: () => import('@/views/bulk/BulkView.vue'),
        },

        // Operational Reports — not compliance-gated. Operational
        // reporting is part of the baseline product surface in both
        // editions, so the route must stay reachable regardless of
        // the GOVERNANCE entitlement. Compliance Reports
        // (/superadmin/audit-reports) remain gated separately.
        {
          path: 'directories/:dirId/reports',
          name: 'reports',
          component: () => import('@/views/reports/ReportJobsView.vue'),
        },

        // Lifecycle Playbooks
        {
          path: 'directories/:dirId/playbooks',
          name: 'playbooks',
          component: () => import('@/views/playbooks/PlaybooksView.vue'),
        },

        // Pending Approvals
        {
          path: 'directories/:dirId/approvals',
          name: 'approvals',
          component: () => import('@/views/approvals/PendingApprovalsView.vue'),
        },

        // Schema Browser (superadmin)
        {
          path: 'superadmin/directory-schema',
          name: 'schema',
          component: () => import('@/views/schema/SchemaBrowserView.vue'),
          meta: { requiresSuperadmin: true },
        },

        // Settings (secondary-sidebar layout; :section? defaults to 'branding')
        {
          path: 'settings/:section?',
          name: 'settings',
          component: () => import('@/views/settings/SettingsView.vue'),
        },

        // Superadmin
        {
          path: 'superadmin',
          redirect: '/superadmin/admins',
        },
        {
          path: 'superadmin/dashboard',
          name: 'dashboard',
          component: () => import('@/views/DashboardView.vue'),
          meta: { requiresSuperadmin: true },
        },
        {
          path: 'superadmin/admins',
          name: 'adminUsers',
          component: () => import('@/views/superadmin/AdminUsersView.vue'),
          meta: { requiresSuperadmin: true },
        },
        // The license view moved into Application Settings as a section
        // (/settings/license). Keep a redirect for anyone with a stale
        // bookmark of the old path.
        {
          path: 'superadmin/license',
          redirect: '/settings/license',
        },
        {
          path: 'superadmin/directories',
          name: 'manageDirectories',
          component: () => import('@/views/superadmin/DirectoriesManageView.vue'),
          meta: { requiresSuperadmin: true },
        },
        {
          path: 'superadmin/directories/:directoryId/discover',
          name: 'discoveryWizard',
          component: () => import('@/views/superadmin/DiscoveryWizardView.vue'),
          meta: { requiresSuperadmin: true },
        },
        {
          // ISVA full-mode integration config — hidden from the
          // directories list when the VENDOR_INTEGRATIONS_ISVA
          // entitlement isn't granted (see the button v-if in
          // DirectoriesManageView). Bookmarked URLs will reach
          // the view but every API call returns 403 from the
          // class-level @Entitled on the controller, which the
          // view surfaces as an error banner.
          path: 'superadmin/directories/:id/isva-config',
          name: 'isvaConfig',
          component: () => import('@/views/superadmin/IsvaConfigView.vue'),
          meta: { requiresSuperadmin: true },
        },
        {
          path: 'superadmin/entra/:directoryId',
          name: 'entraBrowser',
          component: () => import('@/views/superadmin/EntraBrowserView.vue'),
          meta: { requiresSuperadmin: true },
        },
        {
          path: 'superadmin/audit-log',
          name: 'superadminAuditLog',
          component: () => import('@/views/audit/AuditLogView.vue'),
          meta: { requiresSuperadmin: true },
        },
        {
          path: 'superadmin/audit-sources',
          name: 'auditSources',
          component: () => import('@/views/superadmin/AuditSourcesView.vue'),
          meta: { requiresSuperadmin: true },
        },
        {
          path: 'superadmin/profiles',
          name: 'profiles',
          component: () => import('@/views/profiles/SuperadminProfilesView.vue'),
          meta: { requiresSuperadmin: true },
        },
        {
          path: 'superadmin/directory-browser',
          name: 'directoryBrowser',
          component: () => import('@/views/superadmin/DirectoryBrowserView.vue'),
          meta: { requiresSuperadmin: true },
        },
        {
          path: 'superadmin/directory-search',
          name: 'directorySearch',
          component: () => import('@/views/superadmin/DirectorySearchView.vue'),
          meta: { requiresSuperadmin: true },
        },
        {
          path: 'superadmin/integrity',
          name: 'integrityCheck',
          component: () => import('@/views/superadmin/IntegrityCheckView.vue'),
          meta: { requiresSuperadmin: true },
        },

        // Superadmin directory-scoped pages (with inline directory picker)
        {
          path: 'superadmin/approvals',
          name: 'superadminApprovals',
          component: () => import('@/views/approvals/PendingApprovalsView.vue'),
          meta: { requiresSuperadmin: true },
        },
        {
          path: 'superadmin/reports',
          name: 'superadminReports',
          component: () => import('@/views/reports/ReportJobsView.vue'),
          meta: { requiresSuperadmin: true },
        },
        {
          path: 'superadmin/playbooks',
          name: 'superadminPlaybooks',
          component: () => import('@/views/playbooks/PlaybooksView.vue'),
          meta: { requiresSuperadmin: true },
        },

        // ── Commercial (ee) routes — empty in a community build ──────────
        ...eeAppShellRoutes,
      ],
    },

    // ── Self-service portal (authenticated — requires SELF_SERVICE principal) ──
    {
      path: '/self-service',
      component: () => import('@/layouts/SelfServiceLayout.vue'),
      children: [
        {
          path: 'profile',
          name: 'selfServiceProfile',
          component: () => import('@/views/selfservice/SelfServiceProfileView.vue'),
          meta: { requiresSelfService: true },
        },
        {
          path: 'password',
          name: 'selfServicePassword',
          component: () => import('@/views/selfservice/SelfServicePasswordView.vue'),
          meta: { requiresSelfService: true },
        },
        {
          path: 'groups',
          name: 'selfServiceGroups',
          component: () => import('@/views/selfservice/SelfServiceGroupsView.vue'),
          meta: { requiresSelfService: true },
        },
      ],
    },

    // ── Self-service public pages ──────────────────────────────────────────
    {
      path: '/self-service/login',
      name: 'selfServiceLogin',
      component: () => import('@/views/selfservice/SelfServiceLoginView.vue'),
      meta: { public: true },
    },
    {
      path: '/register',
      name: 'register',
      component: () => import('@/views/selfservice/RegisterView.vue'),
      meta: { public: true },
    },
    {
      path: '/register/verify/:token',
      name: 'verifyEmail',
      component: () => import('@/views/selfservice/VerifyEmailView.vue'),
      meta: { public: true },
    },
    {
      path: '/register/status/:requestId',
      name: 'registrationStatus',
      component: () => import('@/views/selfservice/RegistrationStatusView.vue'),
      meta: { public: true },
    },

    // ── Commercial (ee) top-level routes — empty in a community build ──────
    // (the public auditor portal lives here)
    ...eeTopLevelRoutes,

    // Catch-all
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
})

/**
 * Resolve the default landing path for the current user.
 * Superadmins land on the directories management page;
 * regular admins land on the user list for their first authorized profile's directory.
 */
async function resolveHomePath(auth) {
  if (auth.isSuperadmin) return '/dashboard'
  try {
    const { data } = await myProfiles()
    if (data.length) return '/dashboard'
  } catch (e) { console.warn('Failed to resolve home path:', e) }
  return '/no-access'
}

router.beforeEach(async (to) => {
  const auth = useAuthStore()
  // Restore session from httpOnly cookie on first navigation after page load
  await auth.init()

  // Public routes — no auth needed
  if (to.meta.public || to.matched.some(r => r.meta.public)) return

  // Self-service protected routes
  if (to.meta.requiresSelfService) {
    if (!auth.isLoggedIn) {
      return { name: 'selfServiceLogin', query: { redirect: to.fullPath } }
    }
    if (!auth.isSelfService) {
      return { path: await resolveHomePath(auth) }
    }
    return
  }

  // Admin routes — require logged in non-self-service user
  if (!auth.isLoggedIn) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  if (auth.isSelfService) {
    // Self-service users cannot access admin routes
    return { name: 'selfServiceProfile' }
  }
  if (to.meta.requiresSuperadmin && !auth.isSuperadmin) {
    return { path: await resolveHomePath(auth) }
  }
  if (to.meta.requiresCompliance && !auth.isComplianceEnabled) {
    return { path: '/dashboard' }
  }
  if (to.meta.requiresAlerting && !auth.isAlertingEnabled) {
    return { path: '/dashboard' }
  }
  if (to.meta.requiresHr && !auth.isHrEnabled) {
    return { path: '/dashboard' }
  }
  if (to.meta.requiresHybridAccess) {
    if (!auth.isSuperadmin && !auth.hasFeature('HYBRID')) {
      return { path: await resolveHomePath(auth) }
    }
  }
  // Redirect superadmin to setup wizard if first-run setup is pending
  if (auth.isSuperadmin && auth.setupPending && to.name !== 'setup') {
    return { name: 'setup' }
  }
})

export default router
