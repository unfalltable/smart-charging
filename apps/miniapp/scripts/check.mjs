import { fileURLToPath } from 'node:url'
import { readdirSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import vm from 'node:vm'

function walk(directory) {
  return readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const path = join(directory, entry.name)
    return entry.isDirectory() ? walk(path) : [path]
  })
}

const projectRoot = join(dirname(fileURLToPath(import.meta.url)), '..')
const sourceRoot = join(projectRoot, 'src')
const files = walk(sourceRoot)
for (const file of files.filter((path) => path.endsWith('.js'))) {
  new vm.Script(readFileSync(file, 'utf8'), { filename: file })
}
for (const file of files.filter((path) => path.endsWith('.json'))) {
  JSON.parse(readFileSync(file, 'utf8'))
}

const project = JSON.parse(readFileSync(join(projectRoot, 'project.config.json'), 'utf8'))
if (project.setting?.urlCheck !== true) throw new Error('project.config.json must keep URL validation enabled')
if ('appid' in project) throw new Error('The real AppID must be generated in project.private.config.json, not committed')

const app = JSON.parse(readFileSync(join(sourceRoot, 'app.json'), 'utf8'))
if (!Array.isArray(app.pages) || app.pages.length === 0) throw new Error('app.json must declare pages')
for (const page of app.pages) {
  for (const extension of ['js', 'json', 'wxml', 'wxss']) {
    const path = join(sourceRoot, `${page}.${extension}`)
    if (!files.includes(path)) throw new Error(`Page file is missing: ${page}.${extension}`)
  }
}

for (const file of files.filter((path) => path.endsWith('.wxml'))) {
  const content = readFileSync(file, 'utf8')
  if (/[⚡✅❌🚗🔋💰]/u.test(content)) throw new Error(`Structural emoji is not allowed in ${file}`)
  if (/<(?:strong|small)\b/.test(content)) throw new Error(`Unsupported HTML-like WXML element in ${file}`)
  if (/<view\b[^>]*\bbindtap=/.test(content)) throw new Error(`Use an accessible button for tap actions in ${file}`)
}

const runtimeConfig = readFileSync(join(sourceRoot, 'config.js'), 'utf8')
if (!runtimeConfig.includes('/api\\/v1')) throw new Error('Runtime configuration must enforce the /api/v1 HTTPS base')

console.log(`Validated ${files.length} mini-program source files and ${app.pages.length} deployable pages.`)
