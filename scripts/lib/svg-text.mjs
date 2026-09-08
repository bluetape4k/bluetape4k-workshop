const XML_ENTITY_VALUES = new Map([
  ["&amp;", "&"],
  ["&lt;", "<"],
  ["&gt;", ">"],
  ["&quot;", '"'],
  ["&#39;", "'"],
]);

export function decodeXmlEntitiesOnce(value) {
  return String(value).replace(/&(amp|lt|gt|quot|#39);/g, (entity) => XML_ENTITY_VALUES.get(entity) ?? entity);
}

export function stripXmlTags(value) {
  let result = "";
  let insideTag = false;

  for (const character of String(value)) {
    if (insideTag) {
      if (character === ">") insideTag = false;
      continue;
    }
    if (character === "<") {
      insideTag = true;
      continue;
    }
    result += character;
  }

  return result;
}

export function stripXmlComments(value) {
  const source = String(value);
  let result = "";
  let cursor = 0;

  while (cursor < source.length) {
    const commentStart = source.indexOf("<!--", cursor);
    if (commentStart < 0) {
      result += source.slice(cursor);
      break;
    }

    result += source.slice(cursor, commentStart);
    const commentEnd = source.indexOf("-->", commentStart + 4);
    if (commentEnd < 0) break;
    cursor = commentEnd + 3;
  }

  return result;
}
