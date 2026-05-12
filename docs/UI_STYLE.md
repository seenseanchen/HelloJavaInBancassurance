# UI Style Token — Cathay Life Inspired

> 來源：
> - `/Users/livebreeze/Documents/CathayLifeUIUXStyleGuide-20260509.md`
> - `docs/PLAN.md` (M16 token baseline)
>
> 說明：Style Guide 主要提供風格方向（Clean / Card-based / Mobile First）。本文件將其轉成可程式化 token，供 M16-C 直接套用。

## 1) Color Tokens

### 1.1 Brand / Primary / Secondary

| Token | HEX | 用途 |
|---|---|---|
| `cathay.primary` | `#00A160` | 品牌主色、主要 CTA、重要狀態強調 |
| `cathay.primaryHover` | `#00C474` | Primary hover / active 強化 |
| `cathay.primarySoft` | `#E6F7EE` | 卡片背景淡綠區、提示底色 |
| `cathay.secondary.white` | `#FFFFFF` | 主背景 |
| `cathay.secondary.lightGray` | `#F5F6F7` | 區塊底、分段背景 |
| `cathay.secondary.deepGray` | `#E5E7EB` | 邊框、分隔線 |

### 1.2 Neutral

| Token | HEX | 用途 |
|---|---|---|
| `neutral.900` | `#1A1A1A` | 主文字 |
| `neutral.700` | `#374151` | 次標題 / 重要輔助文字 |
| `neutral.500` | `#707070` | 內文次要文字 |
| `neutral.300` | `#D1D5DB` | 輕量分隔線 |
| `neutral.100` | `#F5F6F7` | 頁面淺底 |

### 1.3 Semantic

| Token | HEX | 用途 |
|---|---|---|
| `semantic.success` | `#00A160` | 成功訊息、完成狀態 |
| `semantic.warning` | `#F59E0B` | 警示、注意事項 |
| `semantic.error` | `#DC2626` | 錯誤、阻擋操作 |
| `semantic.info` | `#0EA5E9` | 提示資訊、補充說明 |

## 2) Typography Tokens

| Token | Size / Line Height | Weight | 用途 |
|---|---|---|---|
| `text.h1` | `40px / 52px` | `700` | Hero 主標題 |
| `text.h2` | `32px / 42px` | `700` | 區塊主標題 |
| `text.h3` | `24px / 34px` | `600` | 卡片標題 |
| `text.h4` | `20px / 30px` | `600` | 子段落標題 |
| `text.h5` | `18px / 28px` | `600` | 次層標題 |
| `text.h6` | `16px / 24px` | `600` | 小標 |
| `text.body` | `16px / 26px` | `400` | 主要內文 |
| `text.caption` | `14px / 22px` | `400` | 次要資訊 / 標籤 |

## 3) Spacing Tokens

| Token | Value | 建議用途 |
|---|---|---|
| `space.1` | `4px` | icon 與文字最小間距 |
| `space.2` | `8px` | 小型按鈕/標籤內距 |
| `space.3` | `12px` | 表單欄位間距 |
| `space.4` | `16px` | 卡片內基本間距 |
| `space.6` | `24px` | 卡片內容區塊間距 |
| `space.8` | `32px` | 區塊內層 padding |
| `space.12` | `48px` | 主要區塊上下留白 |
| `space.16` | `64px` | 頁面 section 間距 |

## 4) Radius Tokens

| Token | Value | 用途 |
|---|---|---|
| `radius.sm` | `8px` | 小型按鈕/輸入框 |
| `radius.md` | `12px` | 一般卡片、tab |
| `radius.lg` | `16px` | Hero card、重點資訊卡 |

## 5) Shadow Tokens

| Token | Value | 用途 |
|---|---|---|
| `shadow.card` | `0 4px 16px rgba(0, 0, 0, 0.06)` | Card-based UI |
| `shadow.dialog` | `0 12px 32px rgba(0, 0, 0, 0.12)` | Modal / Drawer |
| `shadow.dropdown` | `0 8px 20px rgba(0, 0, 0, 0.10)` | 下拉選單 / Popover |

## 6) Breakpoints (Mobile First, 三段)

| Token | Value | 說明 |
|---|---|---|
| `screen.mobile` | `0px+` | 預設樣式（base） |
| `screen.tablet` | `768px` | 平板與小筆電 |
| `screen.desktop` | `1280px` | 桌面寬版 |

## 7) Tailwind Config 摘要 (for M16-C)

```ts
// tailwind.config.ts (summary)
import type { Config } from 'tailwindcss'

export default {
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
      screens: {
        tablet: '768px',
        desktop: '1280px',
      },
    },
  },
} satisfies Config
```
