import { createRouter, createWebHistory } from 'vue-router'
import { pinia } from '../stores'
import { useAuthStore } from '../stores/auth'
import LoginView from '../views/LoginView.vue'
import HomeView from '../views/HomeView.vue'
import UnderwritingCasesView from '../views/UnderwritingCasesView.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/',
      redirect: '/home',
    },
    {
      path: '/login',
      name: 'login',
      component: LoginView,
    },
    {
      path: '/home',
      name: 'home',
      component: HomeView,
      meta: { requiresAuth: true },
    },
    {
      path: '/underwriting/cases',
      name: 'underwriting-cases',
      component: UnderwritingCasesView,
      meta: { requiresAuth: true },
    },
    {
      path: '/dashboard',
      redirect: '/home',
    },
  ],
})

router.beforeEach((to) => {
  const authStore = useAuthStore(pinia)
  authStore.hydrate()

  if (to.meta.requiresAuth && !authStore.isAuthenticated) {
    return {
      path: '/login',
      query: { redirect: to.fullPath },
    }
  }

  if (to.path === '/login' && authStore.isAuthenticated) {
    return { path: '/home' }
  }

  return true
})

export default router
