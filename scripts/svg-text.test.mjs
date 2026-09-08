import assert from "node:assert/strict";
import test from "node:test";

import {
  decodeXmlEntitiesOnce,
  stripXmlComments,
  stripXmlTags,
} from "./lib/svg-text.mjs";

test("XML 엔터티는 입력 단계마다 한 번만 디코딩한다", () => {
  assert.equal(decodeXmlEntitiesOnce("&amp;lt;script&amp;gt;"), "&lt;script&gt;");
  assert.equal(decodeXmlEntitiesOnce("&amp;&lt;&gt;&quot;&#39;"), "&<>\"'");
});

test("닫히지 않은 XML 태그와 그 뒤의 내용을 제거한다", () => {
  assert.equal(stripXmlTags("safe<tspan> label</tspan><script"), "safe label");
});

test("완전한 XML 주석만 제거하고 주변 내용은 보존한다", () => {
  assert.equal(stripXmlComments("before<!-- hidden -->after"), "beforeafter");
});

test("닫히지 않은 XML 주석과 그 뒤의 내용을 제거한다", () => {
  assert.equal(stripXmlComments("<style>ok</style><!-- font-family:Arial"), "<style>ok</style>");
});
