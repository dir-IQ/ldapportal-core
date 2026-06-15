// SPDX-License-Identifier: Apache-2.0
import './assets/main.css'
// Importing useTheme/useDensity for their module-load side-effects: reading the
// pre-paint "prefs-hint" cookie and applying the data-theme / data-density
// attributes to <html>. The inline script in index.html already does this
// before the bundle loads; these imports keep the reactive refs authoritative
// and register the system-theme listener. The authoritative preference lives in
// the server-side preferences document, never in localStorage.
import './composables/useTheme'
import './composables/useDensity'

import { createApp } from 'vue'
import { createPinia } from 'pinia'

import App from './App.vue'
import router from './router'
import { vDialogA11y } from './directives/dialogA11y'

const app = createApp(App)

app.use(createPinia())
app.use(router)
app.directive('dialog-a11y', vDialogA11y)

app.mount('#app')
