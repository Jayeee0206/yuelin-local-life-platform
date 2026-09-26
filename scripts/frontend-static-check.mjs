import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';

const root = path.resolve('frontend');
const errors = [];
const htmls = fs.readdirSync(root).filter(name => name.endsWith('.html')).sort();

const add = (name, message) => errors.push(`${name}: ${message}`);

for (const name of htmls) {
  const file = path.join(root, name);
  const html = fs.readFileSync(file, 'utf8');

  if (!/<html[^>]+lang=["']zh-CN["']/.test(html)) add(name, 'missing zh-CN language declaration');
  if (!/<meta[^>]+name=["']viewport["']/.test(html)) add(name, 'missing viewport meta tag');
  if (/href=["'](?:javascript:void\(0\)|#)["']/.test(html)) add(name, 'contains a fake link target');
  if (/\bv-html=/.test(html)) add(name, 'uses v-html; render user content as text instead');
  if (/class=["'][^"']*header-share/.test(html)) add(name, 'contains an unimplemented share entrance');
  if (/查看全部\s*\d*\s*条评价|el-icon-chat-square/.test(html)) add(name, 'contains a fake comment entrance');

  for (const match of html.matchAll(/v-model=["']([^"']+)["']/g)) {
    if (/[\/+*?]/.test(match[1])) add(name, `invalid v-model expression ${match[1]}`);
  }

  for (const match of html.matchAll(/<button\b([^>]*)>/gi)) {
    const attrs = match[1];
    if (!/\btype=["'](?:button|submit)["']/.test(attrs)) add(name, `native button missing an explicit type: <button${attrs}>`);
    if (!/(?:@click|v-on:click|type=["']submit["'])/.test(attrs)) add(name, `native button has no action: <button${attrs}>`);
  }

  const inlineScripts = Array.from(html.matchAll(/<script(?![^>]*src)[^>]*>([\s\S]*?)<\/script>/g), match => match[1]);
  const inlineCode = inlineScripts.join('\n');
  for (const match of html.matchAll(/@(?:click|change|command|scroll|tab-click|touchstart|touchmove|touchend|keyup)(?:\.[\w-]+)*=["']([^"']+)["']/g)) {
    const expression = match[1].trim();
    const method = expression.match(/^([A-Za-z_$][\w$]*)\s*(?:\(|$)/)?.[1];
    if (!method || method.startsWith('$')) continue;
    const escaped = method.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    if (!new RegExp(`\\b${escaped}\\s*\\(`).test(inlineCode)) add(name, `event handler ${method} is not implemented`);
  }

  for (const match of html.matchAll(/<(?:link|script|img)[^>]+(?:href|src)=["']([^"']+)["']/g)) {
    const url = match[1].split('?')[0];
    if (url.startsWith('./')) {
      const target = path.resolve(root, url);
      if (!fs.existsSync(target)) add(name, `missing asset ${url}`);
    } else if (url.startsWith('/imgs/')) {
      const target = path.resolve(root, '.' + url);
      if (!fs.existsSync(target)) add(name, `missing image ${url}`);
    }
  }

  for (const match of html.matchAll(/(?:href|location\.href\s*=)\s*["'](\/[^"']+\.html)(?:\?[^"']*)?["']/g)) {
    const page = match[1].slice(1);
    if (!fs.existsSync(path.join(root, page))) add(name, `references missing page ${match[1]}`);
  }

  for (const match of html.matchAll(/<script(?![^>]*src)[^>]*>([\s\S]*?)<\/script>/g)) {
    try {
      new vm.Script(match[1], {filename: name});
    } catch (error) {
      add(name, `inline JavaScript syntax: ${error.message}`);
    }
  }
}

for (const js of ['js/common.js', 'js/footer.js']) {
  try {
    new vm.Script(fs.readFileSync(path.join(root, js), 'utf8'), {filename: js});
  } catch (error) {
    add(js, `JavaScript syntax: ${error.message}`);
  }
}

const nginx = 'deploy/nginx/default.conf';
const nginxConfig = fs.readFileSync(path.resolve(nginx), 'utf8');
if (!/\bclient_max_body_size\s+6m\s*;/.test(nginxConfig)) {
  add(nginx, 'must allow the application request limit of 6 MB');
}

const dockerIgnore = fs.readFileSync(path.resolve('.dockerignore'), 'utf8');
for (const required of ['.env', '.env.*', 'node_modules', '**/node_modules', '.arena', 'ci-artifacts']) {
  if (!dockerIgnore.split(/\r?\n/).includes(required)) {
    add('.dockerignore', 'missing required build-context exclusion ' + required);
  }
}

if (errors.length) {
  console.error(errors.join('\n'));
  process.exit(1);
}
console.log(`Frontend static checks passed for ${htmls.length} HTML pages: syntax, assets, routes, native button/Vue event actions and fake-entry guards.`);
