import { flushPromises, mount } from "@vue/test-utils";
import { expect, it } from "vitest";
import CommunityMarkdown from "./CommunityMarkdown.vue";
import { testPolicy } from "./testFixtures";

it("keeps historical plain text literal, including markup and whitespace", () => {
  const body = "  # 标题\n\n <img src=x> [1]\n";
  const wrapper = mount(CommunityMarkdown, { props: { body, sourceFormat: "PLAIN_TEXT", policy: testPolicy } });
  expect(wrapper.element.textContent).toBe(body);
  expect(wrapper.find("h1, img, a").exists()).toBe(false);
});

it("builds unique outline targets from rendered headings and clears them for plain text", async () => {
  const wrapper = mount(CommunityMarkdown, { props: { body: '## 重复标题\n\n正文\n\n### 重复标题', sourceFormat: 'MARKDOWN', policy: testPolicy, outline: true } });
  await flushPromises();
  const nodes = wrapper.findAll('h2,h3');
  expect(nodes).toHaveLength(2);
  expect(nodes[0].attributes('id')).not.toBe(nodes[1].attributes('id'));
  const events = wrapper.emitted('outline')!;
  expect(events[events.length - 1][0]).toEqual(nodes.map((node, index) => ({ id: node.attributes('id'), title: '重复标题', level: index + 2 })));
  await wrapper.setProps({ sourceFormat: 'PLAIN_TEXT' }); await flushPromises();
  expect(wrapper.find('h2').exists()).toBe(false);
  const latest = wrapper.emitted('outline')!;
  expect(latest[latest.length - 1][0]).toEqual([]);
  wrapper.unmount();
});

it("does not restore headings forbidden by the active policy or expose unsafe markup", async () => {
  const wrapper = mount(CommunityMarkdown, { props: { body: '## 标题 <script>alert(1)</script>\n\n![图片](https://example.org/a.png)', sourceFormat: 'MARKDOWN', policy: { ...testPolicy, config: { ...testPolicy.config, allowedSyntax: ['paragraph'] } }, outline: true } });
  await flushPromises();
  expect(wrapper.find('script,img,h2').exists()).toBe(false);
  const events = wrapper.emitted('outline')!;
  expect(events[events.length - 1][0]).toEqual([]);
  wrapper.unmount();
});
