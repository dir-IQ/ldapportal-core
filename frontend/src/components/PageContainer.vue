<!-- SPDX-License-Identifier: Apache-2.0 -->
<script setup>
// Canonical wrapper for page-level content. Picks a consistent width based
// on the kind of content the page holds.
//
//   variant="list"   — default. Full-width; best for data tables and
//                      dense lists where the user wants every pixel.
//   variant="form"   — max-w-3xl centred. For narrow forms where long
//                      inputs would otherwise stretch to uncomfortable
//                      reading widths.
//   variant="wizard" — max-w-5xl centred. For multi-step flows and
//                      mixed content pages (configuration + details).
//
// Every top-level admin view should render its contents inside a
// <PageContainer> so global width/padding changes are a one-file edit.
//
// fill — opt in to "take the remaining vertical space" instead of
//        sizing to content. Uses `flex-1 min-h-0 flex flex-col` (not
//        h-full) so the page fills what's left of <main>'s flex column
//        rather than resolving to 100vh and pushing content below the
//        fold. Pages that use this must give their own tall child a
//        `flex-1 min-h-0` so it stretches to the bottom (see
//        SchemaBrowserView / DirectoryBrowserView).

defineProps({
  variant: {
    type: String,
    default: 'list',
    validator: v => ['list', 'form', 'wizard'].includes(v),
  },
  fill: {
    type: Boolean,
    default: false,
  },
})
</script>

<template>
  <div class="p-6" :class="{
    'max-w-3xl mx-auto': variant === 'form',
    'max-w-5xl mx-auto': variant === 'wizard',
    'flex-1 min-h-0 flex flex-col': fill,
  }">
    <slot />
  </div>
</template>
