<script setup>
import { computed, onMounted, ref } from 'vue'
import { withBase } from 'vitepress'

const items = ref([])
const q = ref('')
const activeCat = ref('全部')
const error = ref('')

onMounted(async () => {
  try {
    const resp = await fetch(withBase('/index.json'))
    items.value = await resp.json()
  } catch (e) {
    error.value = '索引加载失败：' + e.message
  }
})

const cats = computed(() => {
  const set = new Set(items.value.map((i) => i.c))
  return ['全部', ...set]
})

/** 空格分词，每个词都要命中（title / keyword / summary / category 任一即可）。 */
const results = computed(() => {
  const terms = q.value.trim().toLowerCase().split(/\s+/).filter(Boolean)
  return items.value.filter((i) => {
    if (activeCat.value !== '全部' && i.c !== activeCat.value) return false
    if (!terms.length) return true
    const hay = `${i.t} ${i.k} ${i.s} ${i.c}`.toLowerCase()
    return terms.every((t) => hay.includes(t))
  })
})
</script>

<template>
  <div class="pf">
    <div class="pf-bar">
      <input
        v-model="q"
        class="pf-input"
        type="search"
        placeholder="粘贴报错里的关键词，比如 LinkageError、NoSuchBeanDefinition、乱码…"
        aria-label="搜索踩坑记录"
      />
      <span class="pf-count">{{ results.length }} / {{ items.length }}</span>
    </div>

    <div class="pf-chips">
      <button
        v-for="c in cats"
        :key="c"
        class="pf-chip"
        :class="{ active: activeCat === c }"
        @click="activeCat = c"
      >
        {{ c }}
      </button>
    </div>

    <p v-if="error" class="pf-error">{{ error }}</p>

    <ul class="pf-list">
      <li v-for="i in results" :key="i.u" class="pf-item">
        <a :href="withBase(i.u)" class="pf-title">{{ i.t }}</a>
        <div v-if="i.k" class="pf-kw"><code>{{ i.k }}</code></div>
        <div class="pf-sum">{{ i.s }}</div>
        <div class="pf-cat">{{ i.c }}</div>
      </li>
    </ul>

    <p v-if="!results.length && !error" class="pf-empty">
      没找到。把报错原文贴到
      <a href="https://github.com/pgy763/llm-dev-pitfalls/issues">Issue</a>
      里，我来补一条。
    </p>
  </div>
</template>

<style scoped>
.pf { margin-top: 1rem; }
.pf-bar { display: flex; gap: 0.75rem; align-items: center; }
.pf-input {
  flex: 1;
  padding: 0.6rem 0.8rem;
  font-size: 0.95rem;
  border: 1px solid var(--vp-c-divider);
  border-radius: 8px;
  background: var(--vp-c-bg-soft);
  color: var(--vp-c-text-1);
}
.pf-input:focus { outline: 2px solid var(--vp-c-brand-1); border-color: transparent; }
.pf-count { font-size: 0.85rem; color: var(--vp-c-text-2); white-space: nowrap; }
.pf-chips { display: flex; flex-wrap: wrap; gap: 0.4rem; margin: 0.75rem 0; }
.pf-chip {
  padding: 0.25rem 0.7rem;
  font-size: 0.8rem;
  border: 1px solid var(--vp-c-divider);
  border-radius: 999px;
  background: transparent;
  color: var(--vp-c-text-2);
  cursor: pointer;
}
.pf-chip.active { background: var(--vp-c-brand-1); border-color: var(--vp-c-brand-1); color: #fff; }
.pf-list { list-style: none; padding: 0; }
.pf-item {
  padding: 0.85rem 0;
  border-bottom: 1px solid var(--vp-c-divider);
}
.pf-title { font-weight: 600; font-size: 0.95rem; }
.pf-kw { margin: 0.3rem 0; font-size: 0.8rem; color: var(--vp-c-text-2); }
.pf-kw code { white-space: pre-wrap; word-break: break-all; }
.pf-sum { font-size: 0.85rem; color: var(--vp-c-text-2); }
.pf-cat { margin-top: 0.3rem; font-size: 0.75rem; color: var(--vp-c-brand-1); }
.pf-empty, .pf-error { margin-top: 1rem; color: var(--vp-c-text-2); font-size: 0.9rem; }
</style>