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
            title="OIDC Configuration Guide" size="xl" fixed-height="80vh">
    <div class="oidc-help space-y-6 text-sm text-gray-700 leading-relaxed">

      <!-- Table of contents -->
      <nav class="border border-gray-200 rounded-lg p-3 bg-gray-50 text-xs">
        <p class="font-semibold text-gray-600 uppercase tracking-wider mb-2">Contents</p>
        <ol class="space-y-1 list-decimal list-inside">
          <li><a href="#overview" class="text-blue-600 hover:underline">Overview</a></li>
          <li><a href="#prereqs" class="text-blue-600 hover:underline">Before you start</a></li>
          <li><a href="#step-idp" class="text-blue-600 hover:underline">Register the app at your IdP</a></li>
          <li><a href="#step-redirect" class="text-blue-600 hover:underline">Configure the redirect URI</a></li>
          <li><a href="#step-form" class="text-blue-600 hover:underline">Fill in this form</a></li>
          <li><a href="#step-enable" class="text-blue-600 hover:underline">Enable OIDC login</a></li>
          <li><a href="#step-accounts" class="text-blue-600 hover:underline">Link admin accounts to OIDC</a></li>
          <li><a href="#providers" class="text-blue-600 hover:underline">Provider-specific notes</a></li>
          <li><a href="#testing" class="text-blue-600 hover:underline">Testing</a></li>
          <li><a href="#troubleshooting" class="text-blue-600 hover:underline">Troubleshooting</a></li>
        </ol>
      </nav>

      <!-- 1. Overview -->
      <section id="overview">
        <h3 class="text-base font-semibold text-gray-900 mb-2">1. Overview</h3>
        <p>
          OpenID Connect (OIDC) lets your admins sign in with an identity provider (IdP) such as
          Google Workspace, Microsoft Entra ID (Azure AD), Okta, Keycloak, or Auth0, instead of
          using local passwords. This app implements the OAuth 2.0 Authorization Code flow with
          PKCE, validates the IdP's ID token signature, issues its own short-lived JWT cookie
          after successful sign-in, and — on logout — terminates the session at the IdP via its
          <code>end_session_endpoint</code> and revokes the refresh token (RFC 7009) if one was
          issued.
        </p>
      </section>

      <!-- 2. Prereqs -->
      <section id="prereqs">
        <h3 class="text-base font-semibold text-gray-900 mb-2">2. Before you start</h3>
        <ul class="list-disc list-inside space-y-1">
          <li>Admin access to your OIDC provider's console.</li>
          <li>The public hostname this app is served on — e.g. <code>https://ldap.example.com</code>.
            OIDC requires HTTPS for anything beyond local development.</li>
          <li>A list of admin usernames that exist (or will exist) at the IdP and should have
            access to this app.</li>
        </ul>
      </section>

      <!-- 3. Register app at IdP -->
      <section id="step-idp">
        <h3 class="text-base font-semibold text-gray-900 mb-2">3. Register the app at your IdP</h3>
        <p>Create a new application / client with the following characteristics:</p>
        <ul class="list-disc list-inside space-y-1 mt-2">
          <li><strong>Application type:</strong> Web application / Confidential client.</li>
          <li><strong>Grant type:</strong> Authorization Code.</li>
          <li><strong>PKCE:</strong> Enabled (this app always sends <code>code_challenge_method=S256</code>).</li>
          <li><strong>Signing algorithm:</strong> RS256, RS384, RS512, ES256, ES384, ES512, or PS-family.
            HMAC-signed ID tokens and <code>alg: none</code> are rejected.</li>
          <li><strong>Scopes:</strong> At minimum <code>openid profile email</code>. Add
            <code>offline_access</code> if you want refresh-token-based session revocation on
            logout (required for offboarded-user protection).</li>
          <li><strong>Response type:</strong> <code>code</code> (implicit flow is not supported).</li>
        </ul>
        <p class="mt-2">
          Take note of the three values the IdP gives you back — you'll paste them into this
          form in step 5:
        </p>
        <ul class="list-disc list-inside space-y-1 mt-1">
          <li><strong>Issuer URL</strong> — the base URL where
            <code>/.well-known/openid-configuration</code> resolves.</li>
          <li><strong>Client ID</strong> — public identifier for this app at the IdP.</li>
          <li><strong>Client Secret</strong> — shared secret (treat like a password; this app
            stores it AES-encrypted at rest).</li>
        </ul>
      </section>

      <!-- 4. Redirect URI -->
      <section id="step-redirect">
        <h3 class="text-base font-semibold text-gray-900 mb-2">4. Configure the redirect URI</h3>
        <p>
          The redirect URI is where the IdP sends the user back after they authenticate. It must
          match <strong>exactly</strong> on both sides — one character off and the IdP will
          refuse the callback.
        </p>
        <p class="mt-2">
          Format: <code class="bg-gray-100 px-1.5 py-0.5 rounded">https://YOUR-HOST/oidc/callback</code>
          — e.g. <code>https://ldap.example.com/oidc/callback</code>.
        </p>
        <div class="bg-amber-50 border border-amber-200 rounded p-3 mt-3 text-amber-900">
          <p class="font-semibold text-xs uppercase tracking-wider mb-1">Security note</p>
          <p>
            Paste the same URI into the <strong>Redirect URI</strong> field below. When that
            field is blank, the server falls back to deriving the URI from the inbound HTTP
            <code>Host</code> header, which is spoofable. Set it explicitly in production.
          </p>
        </div>
        <p class="mt-3">
          Register the same URI at the IdP — usually labelled <em>Redirect URI</em>,
          <em>Callback URL</em>, or <em>Sign-in redirect URI</em> depending on the provider.
        </p>
      </section>

      <!-- 5. Fill in form -->
      <section id="step-form">
        <h3 class="text-base font-semibold text-gray-900 mb-2">5. Fill in the form below</h3>
        <dl class="space-y-2">
          <div>
            <dt class="font-semibold text-gray-900">Issuer URL</dt>
            <dd>The base URL. For example <code>https://accounts.google.com</code>,
              <code>https://login.microsoftonline.com/&lt;tenant-id&gt;/v2.0</code>, or
              <code>https://yourco.okta.com/oauth2/default</code>. This app appends
              <code>/.well-known/openid-configuration</code> and reads the discovery document.</dd>
          </div>
          <div>
            <dt class="font-semibold text-gray-900">Client ID</dt>
            <dd>Copy from the IdP's app registration page.</dd>
          </div>
          <div>
            <dt class="font-semibold text-gray-900">Client Secret</dt>
            <dd>Paste once. Leave blank on subsequent edits to keep the existing value; enter a
              single space to clear it.</dd>
          </div>
          <div>
            <dt class="font-semibold text-gray-900">Scopes</dt>
            <dd>Space-separated list. Default <code>openid profile email</code> is fine for most
              IdPs. Add <code>offline_access</code> if you want the app to be able to revoke the
              IdP session on logout (RFC 7009). Google and some others require
              <code>prompt=consent</code> for refresh tokens — if your refresh token never
              arrives, check the IdP's documentation.</dd>
          </div>
          <div>
            <dt class="font-semibold text-gray-900">Username Claim</dt>
            <dd>
              Which ID-token claim this app matches against the local <code>Account.username</code>
              field. Default is <code>preferred_username</code>. Good alternatives:
              <code>email</code> (if your admins are identified by email everywhere),
              <code>sub</code> (IdP-specific stable identifier — most secure but opaque).
              Whatever you pick, the local admin account must have the exact same string as its
              username.
            </dd>
          </div>
          <div>
            <dt class="font-semibold text-gray-900">Redirect URI</dt>
            <dd>The full callback URL from step 4, e.g.
              <code>https://ldap.example.com/oidc/callback</code>. Must exactly match what you
              registered at the IdP.</dd>
          </div>
        </dl>
      </section>

      <!-- 6. Enable OIDC -->
      <section id="step-enable">
        <h3 class="text-base font-semibold text-gray-900 mb-2">6. Enable OIDC as a login method</h3>
        <p>
          At the top of this section, tick the <strong>OIDC</strong> checkbox under
          <em>Enabled login methods</em>. Click <strong>Save</strong> — the OIDC provider config
          you just filled in becomes active.
        </p>
        <p class="mt-2">
          Don't untick <strong>LOCAL</strong> yet — leave at least one password-based method
          enabled until you've confirmed OIDC login works end-to-end, otherwise you can lock
          yourself out.
        </p>
      </section>

      <!-- 7. Link admin accounts -->
      <section id="step-accounts">
        <h3 class="text-base font-semibold text-gray-900 mb-2">7. Link admin accounts to OIDC</h3>
        <p>
          OIDC sign-in only works for accounts that are explicitly marked as OIDC auth type. To
          link or create one:
        </p>
        <ol class="list-decimal list-inside space-y-1 mt-2">
          <li>Go to <strong>Admin Users</strong> (sidebar, superadmin only).</li>
          <li>Click <strong>New Admin</strong> (or edit an existing one).</li>
          <li>Set <strong>Auth Type</strong> to <strong>OIDC</strong>.</li>
          <li>Set the account's <strong>username</strong> to the exact value the IdP will return
            in the username claim you configured. For example, if the claim is
            <code>preferred_username</code> and the IdP issues
            <code>alice@corp.com</code>, the account's username must be
            <code>alice@corp.com</code>.</li>
          <li>Assign profile roles the same way you do for local accounts. Save.</li>
        </ol>
        <p class="mt-2">
          A mismatch between the IdP's claim value and the local account's username is the single
          most common source of OIDC login failures — double-check it.
        </p>
      </section>

      <!-- 8. Provider-specific -->
      <section id="providers">
        <h3 class="text-base font-semibold text-gray-900 mb-2">8. Provider-specific notes</h3>

        <h4 class="font-semibold text-gray-900 mt-3 mb-1">Google Workspace</h4>
        <ul class="list-disc list-inside space-y-1">
          <li>Issuer URL: <code>https://accounts.google.com</code></li>
          <li>Register the app at
            <a href="https://console.cloud.google.com/apis/credentials"
               target="_blank" rel="noopener" class="text-blue-600 hover:underline">console.cloud.google.com/apis/credentials</a>
            as an OAuth 2.0 Client (Web application).</li>
          <li>Refresh tokens only arrive when the authorization request includes
            <code>prompt=consent</code>; Google also returns one the first time a user consents
            but not on subsequent silent re-auths. If session revocation is important for your
            deployment, plan on forcing consent — or accept the limitation.</li>
          <li>Username claim: <code>email</code> is commonly the right choice.</li>
        </ul>

        <h4 class="font-semibold text-gray-900 mt-3 mb-1">Microsoft Entra ID (Azure AD)</h4>
        <ul class="list-disc list-inside space-y-1">
          <li>Issuer URL: <code>https://login.microsoftonline.com/&lt;tenant-id&gt;/v2.0</code>
            (use <code>/v2.0</code> — the v1 endpoints return different claim shapes).</li>
          <li>Register the app at <em>Azure portal → Entra ID → App registrations → New</em>.
            Pick <em>Web</em> as platform.</li>
          <li>Under <em>Certificates &amp; secrets</em>, create a client secret.</li>
          <li>Under <em>API permissions</em>, grant <code>openid profile email</code> from
            <em>Microsoft Graph → delegated</em>.</li>
          <li>Username claim: <code>preferred_username</code> works for most tenants.</li>
        </ul>

        <h4 class="font-semibold text-gray-900 mt-3 mb-1">Okta</h4>
        <ul class="list-disc list-inside space-y-1">
          <li>Issuer URL: <code>https://&lt;tenant&gt;.okta.com/oauth2/default</code> (or a
            custom authorization server).</li>
          <li>Create the app under <em>Applications → Create App Integration → OIDC → Web
            Application</em>.</li>
          <li>Enable <em>Refresh Token</em> under <em>Grant type</em> for revocation-on-logout.</li>
        </ul>

        <h4 class="font-semibold text-gray-900 mt-3 mb-1">Keycloak</h4>
        <ul class="list-disc list-inside space-y-1">
          <li>Issuer URL: <code>https://&lt;host&gt;/realms/&lt;realm-name&gt;</code>.</li>
          <li>Create the client with <em>Client authentication = ON</em> (confidential),
            <em>Standard flow = enabled</em>.</li>
          <li>Username claim: <code>preferred_username</code> is the default.</li>
        </ul>

        <h4 class="font-semibold text-gray-900 mt-3 mb-1">Auth0</h4>
        <ul class="list-disc list-inside space-y-1">
          <li>Issuer URL: <code>https://&lt;tenant&gt;.auth0.com/</code> (trailing slash matters
            on some Auth0 setups — check the discovery document).</li>
          <li>Application type: Regular Web Application.</li>
          <li>Enable <em>Allow Offline Access</em> on the API definition to receive refresh
            tokens.</li>
        </ul>
      </section>

      <!-- 9. Testing -->
      <section id="testing">
        <h3 class="text-base font-semibold text-gray-900 mb-2">9. Testing</h3>
        <ol class="list-decimal list-inside space-y-1">
          <li>Open the login page in a fresh incognito window (avoids cached cookies skewing
            results).</li>
          <li>Click <strong>Sign in with OIDC</strong>. You should be redirected to the IdP.</li>
          <li>Authenticate at the IdP.</li>
          <li>You should land back on the app's dashboard, logged in as the account you linked
            in step 7.</li>
          <li>Click <strong>Logout</strong>. The browser should redirect briefly to the IdP's
            end-session endpoint before returning you to the app — that's the session being
            terminated at the IdP too.</li>
        </ol>
      </section>

      <!-- 10. Troubleshooting -->
      <section id="troubleshooting">
        <h3 class="text-base font-semibold text-gray-900 mb-2">10. Troubleshooting</h3>
        <dl class="space-y-3">
          <div>
            <dt class="font-semibold text-red-700">"No active OIDC account linked to identity: &lt;x&gt;"</dt>
            <dd>The username claim value from the IdP doesn't match any active admin account
              with <code>authType=OIDC</code>. Check the admin's username in <em>Admin Users</em>
              against what the IdP returns (decode the ID token to see the exact claim value).</dd>
          </div>
          <div>
            <dt class="font-semibold text-red-700">"Invalid or expired OIDC state"</dt>
            <dd>The authorize-then-callback round trip took longer than five minutes, or the
              browser lost <code>sessionStorage</code>, or the backend was restarted mid-flow.
              Just start the login again.</dd>
          </div>
          <div>
            <dt class="font-semibold text-red-700">"OIDC token exchange failed: HTTP 401"</dt>
            <dd>Client secret is wrong, or the configured redirect URI doesn't exactly match the
              one registered at the IdP. Copy-paste both sides to compare.</dd>
          </div>
          <div>
            <dt class="font-semibold text-red-700">"Unsupported or missing ID token signing algorithm"</dt>
            <dd>The IdP is signing tokens with an algorithm outside this app's allow-list
              (RS/ES/PS family). HS-family (HMAC) and <code>none</code> are rejected as a
              security policy. Switch the IdP to RS256 — it's the default at almost every
              provider.</dd>
          </div>
          <div>
            <dt class="font-semibold text-red-700">"No JWKS key matches kid=…"</dt>
            <dd>The IdP just rotated its signing keys and our JWKS cache is stale. This app
              auto-retries once, so this error usually means the key rotation happened between
              the two attempts or the IdP removed the key. Try again in a moment.</dd>
          </div>
          <div>
            <dt class="font-semibold text-red-700">"UserInfo sub claim does not match ID token sub"</dt>
            <dd>Should never happen in practice — this is a hard failure flagging a possible
              token-substitution attack. If it persists, capture the network trace and report
              it; do not weaken this check.</dd>
          </div>
          <div>
            <dt class="font-semibold text-red-700">"ID token missing claim: preferred_username"</dt>
            <dd>The IdP isn't returning that claim for this user. Either ask the IdP admin to
              add it to the ID token, or change the <strong>Username Claim</strong> field to
              something the IdP does return (<code>email</code> or <code>sub</code> are safe
              fallbacks — but remember to update the local account's username accordingly).</dd>
          </div>
          <div>
            <dt class="font-semibold text-red-700">"OIDC discovery missing authorization_endpoint" (or similar)</dt>
            <dd>Wrong issuer URL. Check the value resolves when you append
              <code>/.well-known/openid-configuration</code> in a browser — you should get a
              JSON document back.</dd>
          </div>
        </dl>
      </section>

    </div>
  </AppModal>
</template>

<style scoped>
@reference "tailwindcss";
.oidc-help code {
  @apply bg-gray-100 text-gray-800 px-1 py-0.5 rounded text-xs;
}
.oidc-help section {
  @apply scroll-mt-4;
}
</style>
