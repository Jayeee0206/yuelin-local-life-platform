#!/usr/bin/env node
import fs from 'node:fs';
import path from 'node:path';
import {execFileSync} from 'node:child_process';

const tracked = execFileSync('git', ['ls-files', '-z'], {encoding: 'buffer'})
  .toString('utf8')
  .split('\0')
  .filter(Boolean);
const errors = [];
const report = (file, line, label) => errors.push(`${file}:${line}: ${label}`);

const forbiddenPaths = [
  [/^PROJECT_WORKSPACE\.md$/, 'local workspace handover file is tracked'],
  [/^\.githooks\//, 'local Git hook is tracked'],
  [/^nginx-[^/]+\//, 'downloaded Nginx runtime is tracked'],
  [/^docs\/mcp-[^/]+\.md$/, 'agent/MCP handover document is tracked'],
  [/^docs\/test-reports(?:\/|$)/, 'generated test report is tracked'],
  [/^\.env(?:\.|$)/, 'local environment file is tracked'],
  [/(^|\/)(?:id_(?:rsa|dsa|ecdsa|ed25519)|[^/]+\.(?:pem|p12|pfx|jks|keystore))$/i,
    'credential or private-key file is tracked']
];

for (const file of tracked) {
  if (file === '.env.example') continue;
  for (const [pattern, message] of forbiddenPaths) {
    if (pattern.test(file)) report(file, 1, message);
  }
}

const secretPatterns = [
  ['GitHub token', /(?:github_pat_[A-Za-z0-9_]{20,}|gh[pousr]_[A-Za-z0-9]{20,})/g],
  ['AWS access key', /(?:AKIA|ASIA)[0-9A-Z]{16}/g],
  ['Google API key', /AIza[0-9A-Za-z_-]{30,}/g],
  ['OpenAI API key', /\bsk-(?:proj-)?[A-Za-z0-9_-]{20,}/g],
  ['Slack token', /xox[baprs]-[A-Za-z0-9-]{10,}/g],
  ['GitLab token', /\bglpat-[A-Za-z0-9_-]{20,}/g],
  ['npm token', /\bnpm_[A-Za-z0-9]{20,}/g],
  ['private key', /-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----/g],
  ['JWT', /\beyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\b/g],
  ['credential-bearing URL', /https?:\/\/[^\s/:@]+:[^\s/@]+@[^\s/]+/g]
];
const emailPattern = /\b[A-Z0-9._%+-]+@([A-Z0-9.-]+\.[A-Z]{2,})\b/gi;
const allowedEmailDomains = new Set([
  'example.com', 'example.org', 'example.net', 'users.noreply.github.com'
]);
const mobilePattern = /(?<!\d)1[3-9]\d{9}(?!\d)/g;
const idPattern = /(?<!\d)\d{17}[0-9Xx](?!\d)/g;
const windowsPathPattern = /(?<![A-Za-z0-9])[A-Za-z]:[\\/](?=[^\\/\s'"`<>|*?$])[^\s'"`<>|]+/g;
const unixHomePattern = /\/(?:home|Users)\/[A-Za-z0-9._-]+(?:\/[^\s'"`<>|]*)?/g;
const unsafeSecretDefaultPattern = /\$\{(?:DB_PASSWORD|MYSQL_ROOT_PASSWORD|RABBITMQ_PASSWORD):-(?![?}])[^}]+\}/g;
const unsafeSpringCredentialDefaultPattern = /(?:username|password):\s*\$\{[^}:]+:(?:guest|root|admin|password|changeme)\}/gi;

for (const file of tracked) {
  let buffer;
  try {
    buffer = fs.readFileSync(path.resolve(file));
  } catch {
    continue;
  }
  if (buffer.includes(0)) continue;
  const text = buffer.toString('utf8');
  const syntheticPhonesOnly = text.includes('SYNTHETIC_PHONE_FIXTURES_ONLY') &&
    (file === 'scripts/acceptance-browser.mjs' ||
      file.startsWith('src/test/'));
  for (const [index, line] of text.split(/\r?\n/).entries()) {
    for (const [label, pattern] of secretPatterns) {
      pattern.lastIndex = 0;
      if (pattern.test(line)) report(file, index + 1, label);
    }
    windowsPathPattern.lastIndex = 0;
    unixHomePattern.lastIndex = 0;
    idPattern.lastIndex = 0;
    mobilePattern.lastIndex = 0;
    if (windowsPathPattern.test(line) || unixHomePattern.test(line)) {
      report(file, index + 1, 'absolute local path');
    }
    if (idPattern.test(line)) report(file, index + 1, 'Chinese ID-number-shaped value');
    unsafeSecretDefaultPattern.lastIndex = 0;
    unsafeSpringCredentialDefaultPattern.lastIndex = 0;
    if (unsafeSecretDefaultPattern.test(line) || unsafeSpringCredentialDefaultPattern.test(line)) {
      report(file, index + 1, 'unsafe default credential');
    }
    if (mobilePattern.test(line) && !syntheticPhonesOnly) {
      report(file, index + 1, 'unmarked Chinese mobile-number-shaped value');
    }
    emailPattern.lastIndex = 0;
    let email;
    while ((email = emailPattern.exec(line))) {
      if (!allowedEmailDomains.has(email[1].toLowerCase())) {
        report(file, index + 1, 'non-reserved email address');
      }
    }
  }
}


const envExample = fs.readFileSync('.env.example', 'utf8');
const envLines = envExample.split(/\r?\n/);
for (const name of ['DB_PASSWORD', 'MYSQL_ROOT_PASSWORD', 'RABBITMQ_PASSWORD']) {
  const line = envLines.find((candidate) => candidate.startsWith(name + '='));
  if (line === undefined || line.slice(name.length + 1).trim()) {
    report('.env.example', 1, name + ' must be present and empty');
  }
}

if (errors.length) {
  console.error(errors.join('\n'));
  process.exit(1);
}
console.log(`Repository hygiene checks passed for ${tracked.length} tracked files.`);
