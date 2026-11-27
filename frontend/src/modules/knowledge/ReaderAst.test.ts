import { expect, it } from "vitest";
import { mount } from "@vue/test-utils";
import ReaderAst from "./ReaderAst.vue";
it("renders safe figures, footnotes and text without executing arbitrary node properties", () => {
  const wrapper = mount(ReaderAst, {
    props: {
      blocks: [],
      assets: { ok: "blob:local" },
      nodes: [
        {
          type: "figure",
          children: [
            { type: "image", assetRef: "ok", text: "图示" },
            { type: "figcaption", text: "图注" },
          ],
        },
        { type: "footnote", id: "n", text: "注释" },
        { type: "link", href: "javascript:alert(1)", text: "危险链接" },
        { type: "paragraph", text: "<script>alert(1)</script>" },
      ],
    },
  });
  expect(wrapper.get("figure figcaption").text()).toBe("图注");
  expect(wrapper.get("aside").attributes("role")).toBe("doc-footnote");
  expect(wrapper.find('a[href^="javascript"]').exists()).toBe(false);
  expect(wrapper.find("script").exists()).toBe(false);
  expect(wrapper.text()).toContain("<script>alert(1)</script>");
  wrapper.unmount();
});
