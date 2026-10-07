import { EditorView, basicSetup } from "codemirror";
import { MergeView } from "@codemirror/merge";
import { Decoration, MatchDecorator, ViewPlugin } from "@codemirror/view";
import { EditorState } from "@codemirror/state";

// Highlight {variable}, {#if x}, {#else}, {/if} in prompt templates.
const variableMatcher = new MatchDecorator({
  regexp: /\{[#/]?[A-Za-z][^{}]*\}/g,
  decoration: Decoration.mark({ class: "cm-prompt-var" }),
});
const variables = ViewPlugin.define((view) => ({
  decorations: variableMatcher.createDeco(view),
  update(u) { this.decorations = variableMatcher.updateDeco(u, this.decorations); },
}), { decorations: (v) => v.decorations });
const theme = EditorView.theme({
  "&": { fontSize: "13px", border: "1px solid #C9D1DB", borderRadius: "10px", backgroundColor: "#fff" },
  ".cm-content": { fontFamily: "'IBM Plex Mono', monospace" },
  ".cm-prompt-var": { color: "#1F5F8B", backgroundColor: "#EEF4FB", borderRadius: "3px" },
});

// Editor: replaces the textarea visually but keeps it as the submitted field.
document.querySelectorAll("textarea[data-prompt-editor]").forEach((textarea) => {
  const view = new EditorView({
    doc: textarea.value,
    extensions: [basicSetup, variables, theme, EditorView.lineWrapping,
      EditorView.updateListener.of((u) => { if (u.docChanged) textarea.value = u.state.doc.toString(); })],
  });
  textarea.after(view.dom);
  textarea.hidden = true;
  textarea.form?.addEventListener("submit", () => { textarea.value = view.state.doc.toString(); });
  document.querySelectorAll("[data-insert-var]").forEach((button) => button.addEventListener("click", () => {
    const text = button.dataset.insertVar;
    view.dispatch(view.state.replaceSelection(text));
    view.focus();
  }));
});

// Diff: replaces the server-rendered unified diff with a side-by-side merge view.
document.querySelectorAll("[data-prompt-diff]").forEach((box) => {
  const a = box.querySelector("template[data-side='a']")?.content.textContent ?? "";
  const b = box.querySelector("template[data-side='b']")?.content.textContent ?? "";
  const readOnly = [basicSetup, variables, theme, EditorView.editable.of(false), EditorState.readOnly.of(true), EditorView.lineWrapping];
  box.replaceChildren();
  new MergeView({ a: { doc: a, extensions: readOnly }, b: { doc: b, extensions: readOnly }, parent: box });
});
