import { definePreset } from '@primeuix/themes'
import Aura from '@primeuix/themes/aura'

/**
 * Theme PrimeVue cho CamHub: dựa trên Aura nhưng lấy màu từ biến CSS trong tokens.css,
 * để component PrimeVue và CSS riêng của dự án luôn cùng một bảng màu ở cả nền tối và nền sáng.
 */
const scheme = {
  primary: {
    color: 'var(--primary)',
    contrastColor: '#ffffff',
    hoverColor: 'var(--primary-hover)',
    activeColor: 'var(--primary-hover)',
  },
  highlight: {
    background: 'var(--primary-weak)',
    focusBackground: 'var(--primary-weak)',
    color: 'var(--primary)',
    focusColor: 'var(--primary)',
  },
  formField: {
    background: 'var(--surface-2)',
    borderColor: 'var(--border)',
    hoverBorderColor: 'var(--text-3)',
    focusBorderColor: 'var(--primary)',
    color: 'var(--text)',
    placeholderColor: 'var(--text-3)',
  },
  content: {
    background: 'var(--surface)',
    hoverBackground: 'var(--surface-2)',
    borderColor: 'var(--border)',
    color: 'var(--text)',
    hoverColor: 'var(--text)',
  },
  text: {
    color: 'var(--text)',
    hoverColor: 'var(--text)',
    mutedColor: 'var(--text-2)',
    hoverMutedColor: 'var(--text)',
  },
}

export const CamHubPreset = definePreset(Aura, {
  semantic: {
    primary: {
      50: '{blue.50}',
      100: '{blue.100}',
      200: '{blue.200}',
      300: '{blue.300}',
      400: '{blue.400}',
      500: '{blue.500}',
      600: '{blue.600}',
      700: '{blue.700}',
      800: '{blue.800}',
      900: '{blue.900}',
      950: '{blue.950}',
    },
    colorScheme: { light: scheme, dark: scheme },
  },
})

export const DARK_MODE_SELECTOR = '[data-theme="dark"]'
