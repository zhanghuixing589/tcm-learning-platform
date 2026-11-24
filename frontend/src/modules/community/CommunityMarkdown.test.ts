import { mount } from "@vue/test-utils";
import { expect, it } from "vitest";
import CommunityMarkdown from "./CommunityMarkdown.vue";
import { testPolicy } from "./testFixtures";

it("keeps historical plain text literal, including markup and whitespace", () => {
  const body = "  # 标题\n\n <img src=x> [1]\n";
  const wrapper = mount(CommunityMarkdown, { props: { body, sourceFormat: "PLAIN_TEXT", policy: testPolicy } });
  expect(wrapper.element.textContent).toBe(body);
  expect(wrapper.find("h1, img, a").exists()).toBe(false);
});
