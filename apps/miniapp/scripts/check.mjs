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

const sourceRoot = join(dirname(fileURLToPath(import.meta.url)), '..', 'src')
const files = walk(sourceRoot)
for (const file of files.filter((path) => path.endsWith('.js'))) {
  new vm.Script(readFileSync(file, 'utf8'), { filename: file })
}
for (const file of files.filter((path) => path.endsWith('.json'))) {
  JSON.parse(readFileSync(file, 'utf8'))
}
console.log(`Validated ${files.length} mini-program source files.`)
