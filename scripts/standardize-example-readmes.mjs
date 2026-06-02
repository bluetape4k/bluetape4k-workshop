#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";

const root = process.cwd();
const imageDir = "docs/images/readme-diagrams";
const skippedDirs = new Set([".git", ".gradle", "build", ".worktrees", ".omx", ".omc", "buildSrc"]);

function walk(dir, out = []) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    if (skippedDirs.has(entry.name)) continue;
    const file = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      walk(file, out);
    } else if (entry.name === "build.gradle.kts") {
      out.push(path.dirname(file));
    }
  }
  return out;
}

function walkReadmes(dir, out = []) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    if (skippedDirs.has(entry.name) || entry.name === "docs") continue;
    const file = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      walkReadmes(file, out);
    } else if (entry.name === "README.md") {
      out.push(path.dirname(file));
    }
  }
  return out;
}

function readIfExists(file) {
  return fs.existsSync(file) ? fs.readFileSync(file, "utf8") : "";
}

function writeIfChanged(file, text) {
  const previous = readIfExists(file);
  if (previous !== text) {
    fs.writeFileSync(file, text);
    return true;
  }
  return false;
}

function stripBom(text) {
  return text.replace(/^\uFEFF/, "");
}

function titleFrom(text, rel) {
  const first = stripBom(text).match(/^#\s+(.+?)\s*$/m)?.[1];
  return first || rel.split("/").at(-1).replace(/[-_]/g, " ");
}

function humanTitle(rel) {
  return rel
    .split("/")
    .map((part) => part.replace(/[-_]/g, " ").replace(/\b\w/g, (m) => m.toUpperCase()))
    .join(" / ");
}

function moduleName(rel) {
  return rel.replaceAll("/", "-");
}

function diagramSlug(rel) {
  return rel
    .replace(/^\.$/, "root-readme")
    .replace(/[^A-Za-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "")
    .toLowerCase();
}

function removeTopLanguageSwitch(text) {
  const lines = stripBom(text).split(/\r?\n/);
  const kept = [];
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    const isTop = i <= 8;
    const isSwitch = /README\.ko\.md|README_KO\.md|README\.md|한국어|English|Locales?|Language|언어/.test(line);
    if (isTop && isSwitch && /\[|>|English|한국어|Locales?|Language|언어/.test(line)) continue;
    kept.push(line);
  }
  return kept.join("\n").replace(/\n{3,}/g, "\n\n").trimEnd() + "\n";
}

function ensureTitleAndSwitch(text, title, lang) {
  let body = removeTopLanguageSwitch(text);
  body = body.replace(/^#\s+.+?\s*\n+/, "");
  const switchLine = lang === "en" ? "[한국어](README.ko.md) | English" : "[English](README.md) | 한국어";
  return `# ${title}\n\n${switchLine}\n\n${body.trimStart()}`;
}

function imagesFor(rel) {
  const slug = moduleName(rel);
  const graphvizSlug = diagramSlug(rel);
  const files = fs.readdirSync(path.join(root, imageDir)).filter((file) => file.endsWith(".png"));
  const matching = files.filter((file) => file.startsWith(`${slug}-`));
  const exact = (needle) => matching.find((file) => file.includes(needle));
  const readmeArchitecture = `${graphvizSlug}-readme-architecture-01.png`;
  return {
    scenario: exact("scenario"),
    architecture: files.includes(readmeArchitecture) ? readmeArchitecture : exact("architecture") || exact("diagram"),
    flow: exact("flow") || exact("diagram"),
    sequence: exact("sequence"),
  };
}

function imageMarkdown(rel, file, alt) {
  if (!file) return "";
  const target = path.posix.relative(rel, `${imageDir}/${file}`);
  return `\n![${alt}](${target})\n`;
}

const domainText = {
  aws: ["AWS integration", "AWS 서비스와 로컬 테스트 인프라"],
  docker: ["Docker Compose test infrastructure", "Docker Compose 기반 테스트 인프라"],
  exposed: ["Exposed data access", "Exposed 데이터 접근"],
  gateway: ["gateway and downstream service coordination", "게이트웨이와 하위 서비스 연동"],
  gatling: ["load-test execution", "부하 테스트 실행"],
  graph: ["graph-domain modeling", "그래프 도메인 모델링"],
  "image-processing": ["image-processing workflow", "이미지 처리 워크플로우"],
  io: ["I/O utility workflow", "I/O 유틸리티 워크플로우"],
  json: ["JSON serialization workflow", "JSON 직렬화 워크플로우"],
  kotlin: ["Kotlin language and coroutine patterns", "Kotlin 언어와 코루틴 패턴"],
  ktor: ["Ktor coroutine REST service", "Ktor 코루틴 REST 서비스"],
  leader: ["leader-election coordination", "리더 선출 조정"],
  messaging: ["message-driven workflow", "메시지 기반 워크플로우"],
  observability: ["metrics, tracing, and observation", "메트릭, 트레이싱, 관측"],
  ratelimit: ["rate limiting", "요청 속도 제한"],
  redis: ["Redis-backed coordination", "Redis 기반 조정"],
  shared: ["shared workshop utilities", "공유 워크샵 유틸리티"],
  "spring-boot": ["Spring Boot application feature", "Spring Boot 애플리케이션 기능"],
  "spring-cloud": ["Spring Cloud integration", "Spring Cloud 연동"],
  "spring-data": ["Spring Data persistence", "Spring Data 영속성"],
  "spring-modulith": ["Spring Modulith event boundary", "Spring Modulith 이벤트 경계"],
  "spring-security": ["Spring Security request protection", "Spring Security 요청 보호"],
  vertx: ["Vert.x reactive service", "Vert.x 리액티브 서비스"],
  virtualthreads: ["virtual-thread execution", "가상 스레드 실행"],
};

function domainOf(rel) {
  if (rel.startsWith("spring-security/")) return "spring-security";
  return rel.split("/")[0];
}

function enSections(rel, title, images) {
  const [domain] = domainText[domainOf(rel)] || ["workshop example"];
  const name = buildDirs.has(rel) ? moduleName(rel) : title;
  return {
    scenario: `## Example Scenario\n\nThis example exercises **${title}** as a runnable ${domain} workshop slice. It focuses on the path a developer would inspect first: configure the module, run the sample or tests, and observe the library or framework APIs that remove repetitive infrastructure code.\n${imageMarkdown(rel, images.scenario, `${title} scenario diagram`)}`,
    architecture: `## Architecture Diagram\n\nThe module is organized around the sample entry point or test fixture, the bluetape4k extension layer, and the runtime dependency used by the example. Keep the package under \`io.bluetape4k.workshop.${domainOf(rel).replace(/-/g, "")}\` as the source of truth when comparing this README with the code.\n${imageMarkdown(rel, images.architecture, `${title} architecture diagram`)}`,
    flow: `## Flow Diagram\n\n1. Prepare the local runtime required by \`${name}\`.\n2. Execute the application, controller, service, or test fixture that owns the example scenario.\n3. Delegate repetitive infrastructure work to bluetape4k utilities or Spring/Kotlin integrations.\n4. Assert the visible result through the sample output, HTTP response, repository state, metric, trace, or test expectation.\n${imageMarkdown(rel, images.flow && images.flow !== images.architecture ? images.flow : undefined, `${title} flow diagram`)}`,
    sequence: `## Sequence Diagram\n\nThe core sequence is: caller or test fixture -> workshop adapter -> bluetape4k helper/API -> external runtime or in-memory backend -> assertion/response. When this module has a dedicated sequence asset, the image below shows that interaction order; otherwise the source tests are the authoritative executable sequence.\n${imageMarkdown(rel, images.sequence && images.sequence !== images.flow ? images.sequence : undefined, `${title} sequence diagram`)}`,
  };
}

function koSections(rel, title, images) {
  const [, domain] = domainText[domainOf(rel)] || ["", "워크샵 예제"];
  const name = buildDirs.has(rel) ? moduleName(rel) : title;
  return {
    scenario: `## 예제 시나리오\n\n이 예제는 **${title}** 모듈을 실행 가능한 ${domain} 예제로 보여줍니다. 개발자가 먼저 확인할 경로인 모듈 설정, 샘플 또는 테스트 실행, 반복적인 인프라 코드를 줄이는 라이브러리 또는 프레임워크 API 사용 방식을 중심으로 설명합니다.\n${imageMarkdown(rel, images.scenario, `${title} 시나리오 다이어그램`)}`,
    architecture: `## 아키텍처 다이어그램\n\n모듈은 샘플 진입점 또는 테스트 픽스처, bluetape4k 확장 계층, 예제가 사용하는 런타임 의존성으로 구성됩니다. README와 코드를 비교할 때는 \`io.bluetape4k.workshop.${domainOf(rel).replace(/-/g, "")}\` 패키지 아래의 구현을 기준으로 삼습니다.\n${imageMarkdown(rel, images.architecture, `${title} 아키텍처 다이어그램`)}`,
    flow: `## 흐름 다이어그램\n\n1. \`${name}\` 예제에 필요한 로컬 런타임을 준비합니다.\n2. 예제 시나리오를 담당하는 애플리케이션, 컨트롤러, 서비스 또는 테스트 픽스처를 실행합니다.\n3. 반복적인 인프라 처리는 bluetape4k 유틸리티 또는 Spring/Kotlin 통합 기능에 위임합니다.\n4. 샘플 출력, HTTP 응답, 저장소 상태, metric, trace 또는 테스트 기대값으로 결과를 검증합니다.\n${imageMarkdown(rel, images.flow && images.flow !== images.architecture ? images.flow : undefined, `${title} 흐름 다이어그램`)}`,
    sequence: `## 시퀀스 다이어그램\n\n핵심 시퀀스는 호출자 또는 테스트 픽스처 -> 워크샵 어댑터 -> bluetape4k 헬퍼/API -> 외부 런타임 또는 인메모리 백엔드 -> 검증/응답 순서입니다. 전용 시퀀스 이미지가 있는 모듈은 아래 이미지가 상호작용 순서를 보여주며, 없는 경우 소스 테스트가 실행 가능한 시퀀스의 기준입니다.\n${imageMarkdown(rel, images.sequence && images.sequence !== images.flow ? images.sequence : undefined, `${title} 시퀀스 다이어그램`)}`,
  };
}

function hasSection(text, lang, key) {
  const patterns = {
    en: {
      scenario: /^##\s+.*Scenario/im,
      architecture: /^##\s+.*Architecture/im,
      flow: /^##\s+.*Flow/im,
      sequence: /^##\s+.*Sequence/im,
    },
    ko: {
      scenario: /^##\s+.*시나리오/im,
      architecture: /^##\s+.*아키텍처/im,
      flow: /^##\s+.*흐름/im,
      sequence: /^##\s+.*시퀀스/im,
    },
  };
  return patterns[lang][key].test(text);
}

function insertMissingSections(text, sections, lang) {
  const missing = ["scenario", "architecture", "flow", "sequence"].filter((key) => !hasSection(text, lang, key));
  if (missing.length === 0) return text;
  const block = missing.map((key) => sections[key]).join("\n");
  const lines = text.split(/\r?\n/);
  const insertAt = Math.min(lines.length, 4);
  lines.splice(insertAt, 0, block);
  return lines.join("\n").replace(/\n{3,}/g, "\n\n").trimEnd() + "\n";
}

function ensureArchitectureImage(text, rel, title, images, lang) {
  const image = images.architecture;
  if (!image || !image.includes("readme-architecture-01")) return text;
  if (text.includes(image)) return text;
  const heading = lang === "en"
    ? /^(##\s+.*Architecture.*)$/im
    : /^(##\s+.*아키텍처.*)$/im;
  const alt = lang === "en" ? `${title} Graphviz architecture diagram` : `${title} Graphviz 아키텍처 다이어그램`;
  const markdown = imageMarkdown(rel, image, alt).trimEnd();
  return text.replace(heading, `$1\n\n${markdown}`);
}

function summarizeExistingEn(enText) {
  const headings = [...enText.matchAll(/^##\s+(.+?)\s*$/gm)].map((m) => m[1]).slice(0, 8);
  if (headings.length === 0) return "";
  return `\n## 원문 상세 항목\n\n영어 README에는 다음 상세 항목이 포함되어 있습니다. 한국어 요약은 위의 시나리오/아키텍처/흐름을 기준으로 읽고, 코드 예제와 설정 세부사항은 영어 README의 같은 모듈 설명을 함께 참고하세요.\n\n${headings.map((h) => `- ${h}`).join("\n")}\n`;
}

let changed = 0;
const buildDirs = new Set(
  walk(root)
    .filter((dir) => dir !== root && !dir.includes(`${path.sep}buildSrc${path.sep}`))
    .map((dir) => path.relative(root, dir).replaceAll(path.sep, "/")),
);

const modules = [...new Set([
  ...walk(root)
    .filter((dir) => dir !== root && !dir.includes(`${path.sep}buildSrc${path.sep}`)),
  ...walkReadmes(root)
    .filter((dir) => dir !== root),
])]
  .filter((dir) => dir !== root && !dir.includes(`${path.sep}buildSrc${path.sep}`))
  .sort();

for (const dir of modules) {
  const rel = path.relative(root, dir).replaceAll(path.sep, "/");
  const enFile = path.join(dir, "README.md");
  const koFile = path.join(dir, "README.ko.md");
  if (!fs.existsSync(enFile)) continue;

  const images = imagesFor(rel);
  const originalEn = readIfExists(enFile);
  const title = titleFrom(originalEn, rel);
  let en = ensureTitleAndSwitch(originalEn, title, "en");
  en = insertMissingSections(en, enSections(rel, title, images), "en");
  en = ensureArchitectureImage(en, rel, title, images, "en");
  if (writeIfChanged(enFile, en)) changed++;

  const koTitle = fs.existsSync(koFile) ? titleFrom(readIfExists(koFile), rel) : title;
  let ko;
  if (fs.existsSync(koFile)) {
    ko = ensureTitleAndSwitch(readIfExists(koFile), koTitle, "ko");
  } else {
    ko = `# ${koTitle}\n\n[English](README.md) | 한국어\n\n`;
  }
  ko = insertMissingSections(ko, koSections(rel, koTitle, images), "ko");
  ko = ensureArchitectureImage(ko, rel, koTitle, images, "ko");
  if (!fs.existsSync(koFile)) {
    ko += summarizeExistingEn(originalEn);
    if (buildDirs.has(rel)) {
      ko += `\n## 실행\n\n\`\`\`bash\n./gradlew :${moduleName(rel)}:test\n\`\`\`\n`;
    }
  }
  if (writeIfChanged(koFile, ko)) changed++;
}

console.log(`standardized=${modules.length} changed=${changed}`);
