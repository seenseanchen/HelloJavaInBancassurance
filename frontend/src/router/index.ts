import { createRouter, createWebHistory } from 'vue-router'
import { pinia } from '../stores'
import { useAuthStore } from '../stores/auth'
import LoginView from '../views/LoginView.vue'
import HomeView from '../views/HomeView.vue'
import UnderwritingCasesView from '../views/UnderwritingCasesView.vue'
import UnderwritingCaseCreateView from '../views/UnderwritingCaseCreateView.vue'
import UnderwritingCaseDetailView from '../views/UnderwritingCaseDetailView.vue'
import UnderwritingCaseEventsView from '../views/UnderwritingCaseEventsView.vue'
import PolicyListView from '../views/PolicyListView.vue'
import PolicyDetailView from '../views/PolicyDetailView.vue'
import PolicyAddressChangeView from '../views/PolicyAddressChangeView.vue'
import PolicyBeneficiariesChangeView from '../views/PolicyBeneficiariesChangeView.vue'
import PolicyPaymentMethodChangeView from '../views/PolicyPaymentMethodChangeView.vue'
import PolicyChangesView from '../views/PolicyChangesView.vue'

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
      path: '/underwriting/cases/new',
      name: 'underwriting-case-create',
      component: UnderwritingCaseCreateView,
      meta: { requiresAuth: true },
    },
    {
      path: '/underwriting/cases/:id',
      name: 'underwriting-case-detail',
      component: UnderwritingCaseDetailView,
      meta: { requiresAuth: true },
    },
    {
      path: '/underwriting/cases/:id/events',
      name: 'underwriting-case-events',
      component: UnderwritingCaseEventsView,
      meta: { requiresAuth: true },
    },
    {
      path: '/policies',
      name: 'policies',
      component: PolicyListView,
      meta: { requiresAuth: true },
    },
    {
      path: '/policies/:id',
      name: 'policy-detail',
      component: PolicyDetailView,
      meta: { requiresAuth: true },
    },
    {
      path: '/policies/:id/change/address',
      name: 'policy-change-address',
      component: PolicyAddressChangeView,
      meta: { requiresAuth: true },
    },
    {
      path: '/policies/:id/change/beneficiaries',
      name: 'policy-change-beneficiaries',
      component: PolicyBeneficiariesChangeView,
      meta: { requiresAuth: true },
    },
    {
      path: '/policies/:id/change/payment-method',
      name: 'policy-change-payment-method',
      component: PolicyPaymentMethodChangeView,
      meta: { requiresAuth: true },
    },
    {
      path: '/policies/:id/changes',
      name: 'policy-changes',
      component: PolicyChangesView,
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
