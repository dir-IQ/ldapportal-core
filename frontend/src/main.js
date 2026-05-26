// SPDX-License-Identifier: Apache-2.0
import './assets/main.css'
// Importing useDensity for its module-load side-effect: reading the
// persisted density value from localStorage and applying the data-density
// attribute to <html> before first paint. Without this, the page would
// flash "comfortable" for a frame before resolving to the user's choice.
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
