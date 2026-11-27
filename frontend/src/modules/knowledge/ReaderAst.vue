<script lang="ts">
import { defineComponent, h, type PropType, type VNode } from "vue";
import type { ReaderNode, ReaderBlock } from "./readerApi";
const tags: Record<string, string> = {
  paragraph: "p",
  listItem: "li",
  table: "table",
  row: "tr",
  cell: "td",
  strong: "strong",
  em: "em",
  break: "br",
  blockquote: "blockquote",
  code: "code",
  sup: "sup",
  sub: "sub",
  figure: "figure",
  figcaption: "figcaption",
  footnote: "aside",
};
export default defineComponent({
  props: {
    nodes: { type: Array as PropType<ReaderNode[]>, required: true },
    blocks: { type: Array as PropType<ReaderBlock[]>, required: true },
    assets: {
      type: Object as PropType<Record<string, string>>,
      default: () => ({}),
    },
  },
  setup(props) {
    const render = (node: ReaderNode): VNode => {
      if (node.type === "text") return h("span", node.text || "");
      if (node.type === "image") {
        const src = node.assetRef && props.assets[node.assetRef];
        return src
          ? h("img", { src, alt: node.text || "书内插图", loading: "lazy" })
          : h("span", { class: "muted" }, "（插图暂不可用）");
      }
      const block = props.blocks.find((b) => b.localId === node.id);
      const attrs: Record<string, unknown> = {};
      if (block) {
        attrs["data-block-id"] = block.id;
        attrs.id = `block-${block.id}`;
      }
      let tag = tags[node.type] || "span";
      if (node.type === "heading")
        tag = `h${Math.max(1, Math.min(6, node.level || 2))}`;
      if (node.type === "list") tag = node.ordered ? "ol" : "ul";
      if (node.type === "cell") {
        attrs.colspan = Math.min(100, Math.max(1, node.colspan || 1));
        attrs.rowspan = Math.min(100, Math.max(1, node.rowspan || 1));
      }
      if (node.type === "footnote") attrs.role = "doc-footnote";
      if (node.id && !block) attrs.id = `local-${node.id}`;
      if (node.type === "link") {
        if (node.href?.startsWith("#")) {
          tag = "a";
          const target = node.href.slice(1);
          const targetBlock = props.blocks.find((b) => b.localId === target);
          attrs.href = targetBlock
            ? `#block-${targetBlock.id}`
            : `#local-${target}`;
        } else if (/^https?:\/\//i.test(node.href || "")) {
          tag = "a";
          attrs.href = node.href;
          attrs.target = "_blank";
          attrs.rel = "noopener noreferrer";
        }
      }
      const children = node.children?.length
        ? node.children.map(render)
        : node.text || "";
      return h(tag, attrs, children);
    };
    return () =>
      h(
        "div",
        { class: "source-ast" },
        props.nodes.length
          ? props.nodes.map(render)
          : props.blocks.map((b) =>
              h("p", { "data-block-id": b.id, id: `block-${b.id}` }, b.text),
            ),
      );
  },
});
</script>
