import type { Config } from 'tailwindcss'

export default {
  content: ['./index.html', './src/**/*.{vue,ts,tsx}'],
  theme: {
    extend: {
      colors: {
        cathay: {
          primary: '#00A160',
          primaryHover: '#00C474',
          primarySoft: '#E6F7EE',
        },
        neutral: {
          900: '#1A1A1A',
          700: '#374151',
          500: '#707070',
          300: '#D1D5DB',
          100: '#F5F6F7',
        },
        semantic: {
          success: '#00A160',
          warning: '#F59E0B',
          error: '#DC2626',
          info: '#0EA5E9',
        },
      },
      fontSize: {
        h1: ['40px', { lineHeight: '52px', fontWeight: '700' }],
        h2: ['32px', { lineHeight: '42px', fontWeight: '700' }],
        h3: ['24px', { lineHeight: '34px', fontWeight: '600' }],
        h4: ['20px', { lineHeight: '30px', fontWeight: '600' }],
        h5: ['18px', { lineHeight: '28px', fontWeight: '600' }],
        h6: ['16px', { lineHeight: '24px', fontWeight: '600' }],
        body: ['16px', { lineHeight: '26px', fontWeight: '400' }],
        caption: ['14px', { lineHeight: '22px', fontWeight: '400' }],
      },
      spacing: {
        1: '4px',
        2: '8px',
        3: '12px',
        4: '16px',
        6: '24px',
        8: '32px',
        12: '48px',
        16: '64px',
      },
      borderRadius: {
        sm: '8px',
        md: '12px',
        lg: '16px',
      },
      boxShadow: {
        card: '0 4px 16px rgba(0, 0, 0, 0.06)',
        dialog: '0 12px 32px rgba(0, 0, 0, 0.12)',
        dropdown: '0 8px 20px rgba(0, 0, 0, 0.10)',
      },
      fontFamily: {
        sans: ['Noto Sans TC', 'PingFang TC', 'Microsoft JhengHei', 'sans-serif'],
        display: ['Avenir Next', 'Noto Sans TC', 'sans-serif'],
      },
      screens: {
        tablet: '768px',
        desktop: '1280px',
      },
    },
  },
} satisfies Config
