import type { Theme } from 'vitepress'
import DefaultTheme from 'vitepress/theme'
import PitfallSearch from './components/PitfallSearch.vue'

export default {
  extends: DefaultTheme,
  enhanceApp({ app }) {
    app.component('PitfallSearch', PitfallSearch)
  }
} satisfies Theme