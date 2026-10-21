<template>
  <svg
    class="i"
    :style="{ width: size + 'px', height: size + 'px', flex: '0 0 ' + size + 'px' }"
    aria-hidden="true"
  >
    <use :href="'#i-' + name" />
  </svg>
</template>

<script setup lang="ts">
withDefaults(defineProps<{ name: string; size?: number }>(), { size: 16 })
</script>
