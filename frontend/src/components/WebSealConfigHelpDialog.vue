<!-- SPDX-License-Identifier: Apache-2.0 -->
<script setup>
import AppModal from './AppModal.vue'

defineProps({
  modelValue: { type: Boolean, required: true },
})
defineEmits(['update:modelValue'])
</script>

<template>
  <AppModal :model-value="modelValue" @update:model-value="$emit('update:modelValue', $event)"
            title="WebSEAL (IBM Verify Identity Access) Configuration Guide" size="xl" fixed-height="80vh">
    <div class="webseal-help space-y-6 text-sm text-gray-700 leading-relaxed">

      <!-- Table of contents -->
      <nav class="border border-gray-200 rounded-lg p-3 bg-gray-50 text-xs">
        <p class="font-semibold text-gray-600 uppercase tracking-wider mb-2">Contents</p>
        <ol class="space-y-1 list-decimal list-inside">
          <li><a href="#overview" class="text-blue-600 hover:underline">Overview</a></li>
          <li><a href="#trust-model" class="text-blue-600 hover:underline">Trust model — read this first</a></li>
          <li><a href="#step-webseal" class="text-blue-600 hover:underline">Configure the WebSEAL junction</a></li>
          <li><a href="#step-network" class="text-blue-600 hover:underline">Lock down the network path</a></li>
          <li><a href="#step-form" class="text-blue-600 hover:underline">Fill in this form</a></li>
          <li><a href="#step-enable" class="text-blue-600 hover:underline">Enable WEBSEAL as a login method</a></li>
          <li><a href="#step-accounts" class="text-blue-600 hover:underline">Pre-provision admin accounts</a></li>
          <li><a href="#testing" class="text-blue-600 hover:underline">Testing</a></li>
          <li><a href="#troubleshooting" class="text-blue-600 hover:underline">Troubleshooting</a></li>
        </ol>
      </nav>

      <!-- 1. Overview -->
      <section id="overview">
        <h3 class="text-base font-semibold text-gray-900 mb-2">1. Overview</h3>
        <p>
          IBM Verify Identity Access (formerly ISAM / Tivoli Access Manager) uses
          <strong>WebSEAL</strong> as its reverse-proxy enforcement point. WebSEAL terminates
          the user's authentication (forms, SPNEGO, certificate, federated SAML, OIDC, etc.)
          and forwards the authenticated request to the backend with HTTP headers identifying
          the principal: <code>iv-user</code> carries the authenticated username;
          <code>iv-groups</code> carries the user's group memberships (logged here but not
          used for authorization — see below).
        </p>
        <p class="mt-2">
          This app trusts those headers only when the request's immediate peer IP is in a
          configured CIDR allow-list. Pre-provisioning is required: a local admin account
          must already exist with <strong>Auth Type = WEBSEAL</strong> and a username matching
          the exact value WebSEAL will send.
        </p>
      </section>

      <!-- 2. Trust model -->
      <section id="trust-model">
        <h3 class="text-base font-semibold text-gray-900 mb-2">2. Trust model — read this first</h3>
        <div class="bg-red-50 border border-red-200 rounded p-3 text-red-900">
          <p class="font-semibold text-xs uppercase tracking-wider mb-1">Critical</p>
          <p>
            Header-based SSO is <strong>only</strong> safe when the network path to this app
            is constrained. A client able to reach the app directly (bypassing WebSEAL) can
            simply send <code>iv-user: root</code> and impersonate anyone.
          </p>
          <p class="mt-2">
            Before enabling this feature, make sure one of the following is true:
          </p>
          <ul class="list-disc list-inside space-y-0.5 mt-1">
            <li>The backend is bound to a private network reachable only by WebSEAL.</li>
            <li>A firewall / security group restricts inbound traffic to WebSEAL's IP(s).</li>
            <li>Any fronting proxy strips <code>iv-*</code> headers on non-WebSEAL paths.</li>
          </ul>
          <p class="mt-2">
            The <strong>Trusted Proxies</strong> field is a second line of defence — the app
            rejects the pre-auth path when the request peer isn't in your CIDR list — but it
            is not a substitute for network-level controls.
          </p>
        </div>
        <p class="mt-3">
          The trust check uses <code>HttpServletRequest.getRemoteAddr()</code>, i.e. the
          immediate TCP peer. <code>X-Forwarded-For</code> is ignored — it's set
          <em>by</em> proxies and is trivially spoofable.
        </p>
        <p class="mt-2">
          <code>iv-groups</code> is parsed and logged for audit visibility but is <strong>never</strong>
          consulted when deciding what an admin can do — profile roles on the local
          <code>Account</code> row are the sole source of authorization truth. Pre-provisioning
          gives you full control of which IdP users get admin access.
        </p>
      </section>

      <!-- 3. Configure WebSEAL junction -->
      <section id="step-webseal">
        <h3 class="text-base font-semibold text-gray-900 mb-2">3. Configure the WebSEAL junction</h3>
        <p>
          Create or edit the junction that fronts this app with explicit iv-header injection:
        </p>
        <pre class="bg-gray-900 text-gray-100 rounded p-3 text-xs overflow-x-auto mt-2">pdadmin> server task default-webseald-HOST create \
    -t tcp \
    -h backend-host -p 8080 \
    -c iv-user,iv-groups \
    -j \
    /ldap-portal</pre>
        <ul class="list-disc list-inside space-y-1 mt-2">
          <li><code>-c iv-user,iv-groups</code> — tells WebSEAL to inject these headers on
            every forwarded request. Both are required.</li>
          <li><code>-j</code> — junction cookies, so WebSEAL can rewrite relative URLs and
            cookies correctly.</li>
          <li><code>-t tcp</code> — plain HTTP; use <code>-t ssl</code> with a proper cert
            chain in production.</li>
        </ul>
        <p class="mt-2">
          Consult your WebSEAL documentation for the full list of flags relevant to your
          deployment (ACLs, auth policy, session sharing, etc.).
        </p>
      </section>

      <!-- 4. Network path -->
      <section id="step-network">
        <h3 class="text-base font-semibold text-gray-900 mb-2">4. Lock down the network path</h3>
        <ol class="list-decimal list-inside space-y-1">
          <li>
            Identify the IP(s) WebSEAL will use to reach this app. These are the addresses
            you'll list under <strong>Trusted Proxies</strong> below. In most Kubernetes
            deployments this is the WebSEAL pod's service CIDR; in classic VM deployments it's
            the WebSEAL host's IP.
          </li>
          <li>
            Ensure the app is not reachable from outside that path — firewall, security group,
            NetworkPolicy, or binding to <code>0.0.0.0</code> on a private interface only.
          </li>
          <li>
            If any other proxy (ingress controller, load balancer) can reach the app, confirm
            it strips inbound <code>iv-*</code> headers. Nginx:
            <code>proxy_set_header iv-user "";</code>; HAProxy:
            <code>http-request del-header iv-user</code>; etc.
          </li>
        </ol>
      </section>

      <!-- 5. Fill in form -->
      <section id="step-form">
        <h3 class="text-base font-semibold text-gray-900 mb-2">5. Fill in the form below</h3>
        <dl class="space-y-2">
          <div>
            <dt class="font-semibold text-gray-900">Trusted Proxies</dt>
            <dd>Newline-separated CIDRs. Only requests whose peer IP is in this list will be
              trusted. Comments start with <code>#</code>. Example:
              <pre class="bg-gray-100 rounded p-2 text-xs mt-1">10.20.0.0/16
192.168.10.5
# disaster-recovery webseal
10.21.30.0/24</pre>
              Leaving this field empty <strong>disables the feature at runtime</strong> — a
              safety net against enabling WEBSEAL and forgetting to set the allow-list.</dd>
          </div>
          <div>
            <dt class="font-semibold text-gray-900">User Header</dt>
            <dd>Default <code>iv-user</code>. Only override if you've customized the header
              name in WebSEAL config.</dd>
          </div>
          <div>
            <dt class="font-semibold text-gray-900">Groups Header</dt>
            <dd>Default <code>iv-groups</code>. Audit logs include the parsed groups on each
              sign-in; they never drive role assignment in pre-provisioning mode.</dd>
          </div>
          <div>
            <dt class="font-semibold text-gray-900">Logout URL</dt>
            <dd>Default <code>/pkmslogout</code> (WebSEAL's standard sign-off path). When an
              admin clicks Logout, the frontend redirects here after clearing its JWT cookie so
              WebSEAL also terminates its session — without this, the next page load silently
              re-authenticates via WebSEAL's still-valid session cookie.</dd>
          </div>
        </dl>
      </section>

      <!-- 6. Enable WEBSEAL -->
      <section id="step-enable">
        <h3 class="text-base font-semibold text-gray-900 mb-2">6. Enable WEBSEAL as a login method</h3>
        <p>
          Tick <strong>WEBSEAL</strong> in the <em>Enabled login methods</em> list at the top
          of this section and save. This app's login page will then probe
          <code>/auth/webseal/authorize</code> on every load and auto-sign-in the user when
          the request carries a trusted <code>iv-user</code> header.
        </p>
        <p class="mt-2">
          Leave <strong>LOCAL</strong> ticked for emergency access — the UI will prompt before
          letting you untick it.
        </p>
      </section>

      <!-- 7. Pre-provision accounts -->
      <section id="step-accounts">
        <h3 class="text-base font-semibold text-gray-900 mb-2">7. Pre-provision admin accounts</h3>
        <ol class="list-decimal list-inside space-y-1">
          <li>Go to <strong>Admin Users</strong> (sidebar, superadmin only).</li>
          <li>Click <strong>New Admin</strong> (or edit an existing one).</li>
          <li>Set <strong>Auth Type</strong> to <strong>WEBSEAL</strong>.</li>
          <li>
            Set the account's <strong>username</strong> to the exact value WebSEAL will send in
            the <code>iv-user</code> header. Typically this is the short LDAP username or the
            IdP's <code>preferred_username</code>. If you're not sure, capture the header with
            a curl-through-WebSEAL test before creating accounts.
          </li>
          <li>Assign profile roles the same way you do for LOCAL/LDAP/OIDC admins. Save.</li>
        </ol>
        <p class="mt-2">
          An admin who reaches this app via WebSEAL <em>without</em> a matching pre-provisioned
          account will be rejected with a 401 — no auto-provisioning happens. That's the
          deliberate behaviour of this integration mode.
        </p>
      </section>

      <!-- 8. Testing -->
      <section id="testing">
        <h3 class="text-base font-semibold text-gray-900 mb-2">8. Testing</h3>
        <ol class="list-decimal list-inside space-y-1">
          <li>In an incognito window, navigate to the app through WebSEAL.</li>
          <li>Authenticate at the WebSEAL login page.</li>
          <li>
            On arrival at this app's <code>/login</code>, you should be redirected immediately
            to the dashboard. If you see the normal login form instead, something in the chain
            failed — check the Troubleshooting section.
          </li>
          <li>
            Click <strong>Logout</strong>. The browser should navigate to
            <code>/pkmslogout</code>, which terminates the WebSEAL session.
          </li>
        </ol>
      </section>

      <!-- 9. Troubleshooting -->
      <section id="troubleshooting">
        <h3 class="text-base font-semibold text-gray-900 mb-2">9. Troubleshooting</h3>
        <dl class="space-y-3">
          <div>
            <dt class="font-semibold text-red-700">Login page keeps appearing instead of auto-sign-in</dt>
            <dd>Feature gate failed. Check, in order: (1) WEBSEAL is in the enabled login
              methods; (2) Trusted Proxies contains the actual peer IP — look at the backend
              access log to see what IP is landing on the server; (3) the
              <code>iv-user</code> header is being forwarded (dump headers with a test
              endpoint).</dd>
          </div>
          <div>
            <dt class="font-semibold text-red-700">"No active WebSEAL-linked admin account matches iv-user=X"</dt>
            <dd>Pre-provisioning mismatch. Either the Account doesn't exist, is marked
              inactive, or has a different <code>authType</code>. Also check that the username
              matches byte-for-byte — WebSEAL may send the short form while your account uses
              the DN (or vice versa).</dd>
          </div>
          <div>
            <dt class="font-semibold text-red-700">"Trust boundary not configured" banner won't go away</dt>
            <dd>Your <code>Trusted Proxies</code> field is empty or contains only comments/
              whitespace. Add at least one CIDR. A single-IP entry is fine
              (<code>192.168.1.5</code> is treated as <code>/32</code>).</dd>
          </div>
          <div>
            <dt class="font-semibold text-red-700">Works in dev, fails in production</dt>
            <dd>Usually a proxy layer between WebSEAL and the app: the peer IP the backend
              sees is that intermediate proxy's, not WebSEAL's. Either add the intermediate
              proxy's IP to the trusted list (with the caveat that it weakens the model), or
              collapse the hop.</dd>
          </div>
          <div>
            <dt class="font-semibold text-red-700">Different admin appears as authenticated</dt>
            <dd>Browser replay. WebSEAL session cookies persist across tabs; close all
              non-incognito windows. Also check that you're not behind a shared outbound
              proxy rewriting Host or forwarding to the wrong backend.</dd>
          </div>
          <div>
            <dt class="font-semibold text-red-700">iv-groups log line looks wrong</dt>
            <dd>WebSEAL can emit either <code>groupA,groupB</code> or
              <code>"cn=admins,ou=g,dc=corp"</code>. The parser handles both; if you're
              seeing unexpected output, check the <code>iv-groups-format</code> setting on
              the junction.</dd>
          </div>
        </dl>
      </section>

    </div>
  </AppModal>
</template>

<style scoped>
@reference "tailwindcss";
.webseal-help code {
  @apply bg-gray-100 text-gray-800 px-1 py-0.5 rounded text-xs;
}
.webseal-help section {
  @apply scroll-mt-4;
}
</style>
