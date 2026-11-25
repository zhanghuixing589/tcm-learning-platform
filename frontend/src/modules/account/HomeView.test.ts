import { afterEach, expect, it, vi } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import { createPinia, setActivePinia } from "pinia";
import { createMemoryHistory, createRouter } from "vue-router";
import { useSessionStore } from "../../shared/stores/session";
import type { User } from "../../shared/api/types";
import HomeView from "./HomeView.vue";
afterEach(() => vi.restoreAllMocks());
async function setup(permissions: string[] | null) {
  const pinia = createPinia(); setActivePinia(pinia);
  const session = useSessionStore();
  session.user = permissions === null ? null : { id: "1", permissions, roles: [] } as unknown as User;
  const router = createRouter({ history: createMemoryHistory(), routes: ["/", "/knowledge", "/community", "/applications", "/contribute", "/login"].map(path => ({path, component: {template: '<div />'}})) });
  await router.push('/'); await router.isReady();
  const wrapper = mount(HomeView, {global: {plugins: [pinia, router], stubs: {HomeContent: true}}});
  return {wrapper, router};
}
it('submits a trimmed knowledge query through the existing catalog route', async () => {
  const {wrapper,router} = await setup(null);
  await wrapper.get('input[type="search"]').setValue('  菊花  ');
  await wrapper.get('form[role="search"]').trigger('submit'); await flushPromises();
  expect(router.currentRoute.value.path).toBe('/knowledge');
  expect(router.currentRoute.value.query).toEqual({q: '菊花'}); wrapper.unmount();
});
it.each([
  [null, '/login?redirect=/applications', '登录，了解贡献资格'],
  [[], '/applications', '查看贡献资格'],
  [['contribution:submit'], '/contribute', '参与知识共建'],
] as const)('routes the contribution action according to actual permission %j', async (permissions, href, label) => {
  const {wrapper} = await setup(permissions === null ? null : [...permissions]);
  const action = wrapper.get('.home-contribute a');
  expect(action.attributes('href')).toBe(href); expect(action.text()).toContain(label); wrapper.unmount();
});
