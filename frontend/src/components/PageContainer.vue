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

defineProps({
  variant: {
    type: String,
    default: 'list',
    validator: v => ['list', 'form', 'wizard'].includes(v),
  },
})
</script>

<template>
  <div class="p-6" :class="{
    'max-w-3xl mx-auto': variant === 'form',
    'max-w-5xl mx-auto': variant === 'wizard',
  }">
    <slot />
  </div>
</template>
