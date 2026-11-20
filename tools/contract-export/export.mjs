#!/usr/bin/env node
// Exports the DSH Typert Remote contract (all generated `typert.remote-client.js` descriptor tables)
// into a language-neutral JSON file consumed by tools/codegen.
//
// Usage: node tools/contract-export/export.mjs <path-to-deepseek-harness-checkout> <out-dir>
import { readdir, mkdir, writeFile, copyFile, readFile } from 'node:fs/promises'
import { createRequire } from 'node:module'
import { join, relative, dirname } from 'node:path'
import { pathToFileURL } from 'node:url'

const [, , dshRoot, outDir] = process.argv
if (!dshRoot || !outDir) {
  console.error('usage: export.mjs <dsh-root> <out-dir>')
  process.exit(2)
}

async function findContracts(dir, acc = []) {
  for (const entry of await readdir(dir, { withFileTypes: true })) {
    if (entry.name === 'node_modules' || entry.name.startsWith('.')) continue
    const full = join(dir, entry.name)
    if (entry.isDirectory()) await findContracts(full, acc)
    else if (entry.name === 'typert.remote-client.js' && dirname(full).endsWith('/lib')) acc.push(full)
  }
  return acc
}

function toSchema(z, create) {
  try {
    return z.toJSONSchema(create(), { unrepresentable: 'any', io: 'input', cycles: 'ref', reused: 'inline' })
  } catch (error) {
    return { 'x-export-error': String(error?.message ?? error) }
  }
}

const files = (await findContracts(join(dshRoot, 'packages'))).sort()
const methods = []
for (const file of files) {
  const req = createRequire(file)
  const zodPath = req.resolve('zod')
  const zmod = await import(pathToFileURL(zodPath).href)
  const z = zmod.z ?? zmod.default ?? zmod
  const mod = await import(pathToFileURL(file).href)
  const table = mod.default ?? mod.TYPERT_REMOTE
  const pkg = JSON.parse(await readFile(join(dirname(dirname(file)), 'package.json'), 'utf8')).name
  for (const d of table.descriptors) {
    methods.push({
      id: d.id,
      package: pkg,
      namespace: d.namespace,
      method: d.method,
      endpoint: `${d.namespace}/${d.method}`,
      mode: d.mode === 'stream' ? 'stream' : 'unary',
      scope: d.scope ?? null,
      parameters: (d.parameters ?? []).map((p) => ({
        name: p.name,
        wire: p.wire,
        source: p.source,
        lookup: p.lookup ?? null,
        typeSymbol: p.codec?.typeSymbol ?? null,
        optional: !!p.optional,
        schema: p.codec?.create ? toSchema(z, p.codec.create) : null,
      })),
      result: d.result
        ? { typeSymbol: d.result.typeSymbol ?? null, schema: d.result.create ? toSchema(z, d.result.create) : null }
        : null,
      item: d.item?.create ? { typeSymbol: d.item.typeSymbol ?? null, schema: toSchema(z, d.item.create) } : null,
      uplink: d.uplink?.create ? { typeSymbol: d.uplink.typeSymbol ?? null, schema: toSchema(z, d.uplink.create) } : null,
      source: d.sourceLocation ? `${d.sourceLocation.file}:${d.sourceLocation.line}` : relative(dshRoot, file),
    })
  }
}

await mkdir(outDir, { recursive: true })
const dshPkg = JSON.parse(await readFile(join(dshRoot, 'package.json'), 'utf8'))
await writeFile(
  join(outDir, 'dsh-contract.json'),
  JSON.stringify({ dshVersion: dshPkg.version, generatedAt: new Date().toISOString(), methods }, null, 1),
)
await copyFile(join(dshRoot, 'docs/persistence-schema.json'), join(outDir, 'persistence-schema.json'))
console.log(`exported ${methods.length} methods from ${files.length} packages (DSH ${dshPkg.version})`)
