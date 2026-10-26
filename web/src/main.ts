import { createApp } from 'vue'
import { createPinia } from 'pinia'
import PrimeVue from 'primevue/config'
import App from './App.vue'
import { router } from './router'
import { i18n } from './i18n'
import { CamHubPreset, DARK_MODE_SELECTOR } from './styles/theme'
import './styles/tokens.css'
import './styles/app.css'

createApp(App)
  .use(createPinia())
  .use(router)
  .use(i18n)
  .use(PrimeVue, { theme: { preset: CamHubPreset, options: { darkModeSelector: DARK_MODE_SELECTOR } } })
  .mount('#app')
