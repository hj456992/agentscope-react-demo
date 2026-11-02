import { existsSync } from "node:fs";
import { readFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { marked } from "marked";
import puppeteer from "puppeteer-core";

const here = path.dirname(fileURLToPath(import.meta.url));
const projectRoot = path.resolve(here, "../../..");

const input = path.resolve(process.argv[2] ?? path.join(projectRoot, "docs/项目问题及解法.md"));
const output = path.resolve(process.argv[3] ?? input.replace(/\.md$/i, ".pdf"));

if (!existsSync(input)) {
    console.error(`找不到 Markdown 文件：${input}`);
    process.exit(1);
}

const chromeCandidates = [
    process.env.CHROME_PATH,
    "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
    "/Applications/Chromium.app/Contents/MacOS/Chromium",
    "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge",
].filter(Boolean);
const executablePath = chromeCandidates.find((candidate) => existsSync(candidate));
if (!executablePath) {
    console.error("找不到 Chrome。请安装 Google Chrome，或用 CHROME_PATH 指定可执行文件。");
    process.exit(1);
}

const markdown = await readFile(input, "utf8");
const title = markdown.match(/^#\s+(.+)$/m)?.[1]?.trim() ?? path.basename(input, ".md");

// 把 ```mermaid 代码块改成 mermaid 能直接识别的容器；代码块内的实体字符交给浏览器解码。
let body = marked.parse(markdown, { gfm: true });
body = body.replace(
    /<pre><code class="language-mermaid">([\s\S]*?)<\/code><\/pre>/g,
    '<div class="mermaid">$1</div>',
);

const mermaidBundle = (await readFile(path.join(here, "node_modules/mermaid/dist/mermaid.min.js"), "utf8"))
    .replace(/<\/script/gi, "<\\/script");

const html = `<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<title>${title}</title>
<style>
    :root {
        --text: #1f2328;
        --muted: #59636e;
        --border: #d1d9e0;
        --surface: #f6f8fa;
        --accent: #0757ba;
    }
    * { box-sizing: border-box; }
    body {
        margin: 0;
        color: var(--text);
        font-family: "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei", "Helvetica Neue", Arial, sans-serif;
        font-size: 10.5pt;
        line-height: 1.65;
        -webkit-print-color-adjust: exact;
        print-color-adjust: exact;
    }
    h1, h2, h3, h4, h5, h6 {
        line-height: 1.35;
        margin: 1.3em 0 0.55em;
        break-after: avoid;
    }
    h1 { font-size: 21pt; border-bottom: 2px solid var(--border); padding-bottom: 0.3em; }
    h2 { font-size: 15.5pt; border-bottom: 1px solid var(--border); padding-bottom: 0.25em; }
    h3 { font-size: 12.5pt; color: var(--accent); }
    h4 { font-size: 11pt; }
    p { margin: 0.55em 0; }
    a { color: var(--accent); text-decoration: none; }
    code {
        font-family: "SFMono-Regular", Menlo, Consolas, "Liberation Mono", monospace;
        font-size: 0.88em;
        background: var(--surface);
        padding: 0.1em 0.35em;
        border-radius: 4px;
        word-break: break-word;
    }
    pre {
        background: var(--surface);
        border: 1px solid var(--border);
        border-radius: 6px;
        padding: 0.7em 0.9em;
        overflow-wrap: anywhere;
        white-space: pre-wrap;
        break-inside: avoid;
    }
    pre code { background: none; padding: 0; }
    blockquote {
        margin: 0.8em 0;
        padding: 0.3em 1em;
        color: var(--muted);
        border-left: 3px solid var(--border);
        background: #fbfcfd;
    }
    table {
        width: 100%;
        border-collapse: collapse;
        margin: 0.8em 0 1.1em;
        font-size: 8.8pt;
        line-height: 1.5;
    }
    th, td {
        border: 1px solid var(--border);
        padding: 4px 6px;
        vertical-align: top;
        overflow-wrap: anywhere;
    }
    th { background: var(--surface); font-weight: 600; text-align: left; }
    thead { display: table-header-group; }
    tr { break-inside: auto; }
    ul, ol { margin: 0.5em 0; padding-left: 1.6em; }
    li { margin: 0.2em 0; }
    hr { border: 0; border-top: 1px solid var(--border); margin: 1.2em 0; }
    .mermaid {
        margin: 1em 0;
        text-align: center;
        break-inside: avoid;
    }
    .mermaid svg { max-width: 100%; max-height: 240mm; height: auto; }
</style>
</head>
<body>
${body}
<script>${mermaidBundle}</script>
<script>
    (async () => {
        try {
            mermaid.initialize({
                startOnLoad: false,
                theme: "neutral",
                securityLevel: "loose",
                flowchart: { useMaxWidth: true, htmlLabels: true }
            });
            await mermaid.run({ querySelector: ".mermaid" });
        } catch (error) {
            console.error("Mermaid 渲染失败：" + error);
        }
        await document.fonts.ready;
        window.__ready = true;
    })();
</script>
</body>
</html>`;

const browser = await puppeteer.launch({
    executablePath,
    headless: true,
    args: ["--no-sandbox", "--disable-gpu", "--font-render-hinting=none"],
});

try {
    const page = await browser.newPage();
    page.on("console", (message) => {
        if (message.type() === "error") {
            console.error(message.text());
        }
    });
    await page.setContent(html, { waitUntil: "load" });
    await page.waitForFunction("window.__ready === true", { timeout: 30000 });
    await page.pdf({
        path: output,
        format: "A4",
        printBackground: true,
        displayHeaderFooter: true,
        headerTemplate: "<span></span>",
        footerTemplate:
            '<div style="width:100%;font-size:8px;color:#8b949e;text-align:center;">' +
            '第 <span class="pageNumber"></span> 页 / 共 <span class="totalPages"></span> 页</div>',
        margin: { top: "16mm", bottom: "16mm", left: "14mm", right: "14mm" },
    });
} finally {
    await browser.close();
}

console.log(`已生成：${output}`);
