#!/usr/bin/env node
// Generates Kotlin (kotlinx.serialization) types + typed API stubs from contract/dsh-contract.json.
//
// Usage: node tools/codegen/generate.mjs contract/dsh-contract.json shared/src/commonMain/kotlin/dsh/gen
//
// Mapping rules (lenient by design so minor DSH upgrades do not break the client):
//  - object with properties          -> @Serializable data class (optional props nullable, default null)
//  - object with only additionalProps -> Map<String, T>;  bare object -> JsonObject
//  - anyOf of objects sharing a const discriminator (type/kind/...) -> sealed interface + content-based
//    polymorphic serializer with an `Unknown` fallback variant that keeps the raw JSON
//  - anyOf of objects without discriminator -> one merged data class (all props nullable)
//  - anyOf of string consts / branded strings (allOf[string,{}]) -> String
//  - JSON-value unions, $refs to recursive JSON values, mixed unions -> JsonElement
import { readFile, writeFile, mkdir, rm } from 'node:fs/promises'
import { createHash } from 'node:crypto'
import { join } from 'node:path'

const [, , contractPath, outDir] = process.argv
const contract = JSON.parse(await readFile(contractPath, 'utf8'))
const PKG = 'dsh.gen'

const KEYWORDS = new Set(
  'as break class continue do else false for fun if in interface is null object package return super this throw true try typealias typeof val var when while'.split(
    ' ',
  ),
)
const pascal = (s) =>
  String(s)
    .replace(/[^A-Za-z0-9]+(.)?/g, (_, c) => (c ? c.toUpperCase() : ''))
    .replace(/^./, (c) => c.toUpperCase())
    .replace(/^(\d)/, 'N$1') || 'X'
const camel = (s) => {
  const p = pascal(s)
  return p.charAt(0).toLowerCase() + p.slice(1)
}
const ident = (s) => {
  let id = /^[A-Za-z_][A-Za-z0-9_]*$/.test(s) ? s : camel(s)
  if (id.startsWith('_')) id = id.replace(/^_+/, '') || 'value'
  return KEYWORDS.has(id) ? `\`${id}\`` : id
}
const hash = (o) => createHash('sha1').update(JSON.stringify(o)).digest('hex').slice(0, 12)

// ---------- type registry ----------
const classes = new Map() // name -> {code, ns}
const byHash = new Map() // schema hash -> name
const nsOfName = new Map()

function uniqueName(base, ns, h) {
  let name = base
  if (!classes.has(name)) return name
  if (byHash.get(h) === name) return name
  name = pascal(ns) + base
  let i = 2
  while (classes.has(name)) name = `${pascal(ns)}${base}${i++}`
  return name
}

function strip(s) {
  if (!s || typeof s !== 'object') return s
  const { readOnly, $schema, description, ...rest } = s
  return rest
}

function isJsonValueUnion(s) {
  const a = s.anyOf
  if (!Array.isArray(a)) return false
  const types = new Set(a.map((x) => x.type))
  return types.has('null') && types.has('string') && types.has('number') && types.has('array') && types.has('object')
}
const isBrandedString = (s) =>
  Array.isArray(s.allOf) && s.allOf.some((x) => x.type === 'string') && s.allOf.every((x) => x.type === 'string' || Object.keys(x).length === 0)

function nonNullMembers(s) {
  return s.anyOf.filter((x) => !(x.type === 'null'))
}

// Resolve allOf of objects into a single object schema.
function mergeAllOf(s) {
  const parts = s.allOf.map(strip)
  if (!parts.every((p) => p.type === 'object' || Object.keys(p).length === 0)) return null
  const props = {}
  const req = new Set()
  let addl
  for (const p of parts) {
    Object.assign(props, p.properties ?? {})
    for (const r of p.required ?? []) req.add(r)
    if (p.additionalProperties) addl = p.additionalProperties
  }
  return { type: 'object', properties: props, required: [...req], ...(addl ? { additionalProperties: addl } : {}) }
}

function findDiscriminator(members) {
  if (members.length < 2 || !members.every((m) => m.type === 'object' && m.properties)) return null
  const candidates = ['type', 'kind', ...Object.keys(members[0].properties)]
  for (const key of candidates) {
    const values = members.map((m) => {
      const p = m.properties[key]
      return p && (typeof p.const === 'string' ? p.const : null)
    })
    if (values.every((v) => v !== null) && new Set(values).size === values.length) return { key, values }
  }
  return null
}

/** Returns a Kotlin type expression for schema `s`; may register classes. */
function kt(sIn, nameHint, ns, defs) {
  let s = strip(sIn)
  if (!s || Object.keys(s).length === 0) return 'JsonElement'
  if (s.$ref) {
    const key = s.$ref.replace('#/$defs/', '')
    const def = defs?.[key]
    if (!def || isJsonValueUnion(strip(def))) return 'JsonElement'
    return 'JsonElement' // recursive non-JSON defs are rare; keep lenient
  }
  if (isBrandedString(s)) return 'String'
  if (s.allOf) {
    const merged = mergeAllOf(s)
    return merged ? kt(merged, nameHint, ns, defs) : 'JsonElement'
  }
  if (s.anyOf) {
    if (isJsonValueUnion(s)) return 'JsonElement'
    const members = nonNullMembers(s).map(strip)
    const nullable = members.length !== s.anyOf.length
    const q = nullable ? '?' : ''
    if (members.length === 1) return kt(members[0], nameHint, ns, defs) + q
    if (members.every((m) => m.type === 'string' || isBrandedString(m))) return 'String' + q
    if (members.every((m) => m.type === 'number' || m.type === 'integer')) return 'Double' + q
    if (members.every((m) => m.type === 'boolean')) return 'Boolean' + q
    const objs = members.map((m) => (m.allOf ? mergeAllOf(m) ?? m : m))
    if (objs.every((m) => m.type === 'object' && m.properties)) {
      const disc = findDiscriminator(objs)
      if (disc) return sealed(objs, disc, nameHint, ns, defs) + q
      return dataClass(mergeObjects(objs), nameHint, ns, defs) + q
    }
    return 'JsonElement'
  }
  switch (s.type) {
    case 'string':
      return 'String'
    case 'integer':
      return 'Long'
    case 'number':
      return 'Double'
    case 'boolean':
      return 'Boolean'
    case 'null':
      return 'JsonElement'
    case 'array':
      if (Array.isArray(s.prefixItems)) return 'JsonArray'
      return `List<${kt(s.items, nameHint + 'Item', ns, defs)}>`
    case 'object':
      if (s.properties && Object.keys(s.properties).length) return dataClass(s, nameHint, ns, defs)
      if (s.additionalProperties && typeof s.additionalProperties === 'object')
        return `Map<String, ${kt(s.additionalProperties, nameHint + 'Value', ns, defs)}>`
      return 'JsonObject'
  }
  return 'JsonElement'
}

function mergeObjects(objs) {
  const props = {}
  for (const o of objs) {
    for (const [k, v] of Object.entries(o.properties)) {
      if (!props[k]) props[k] = v
      else if (JSON.stringify(strip(props[k])) !== JSON.stringify(strip(v))) {
        const a = strip(props[k]), b = strip(v)
        const bothStrings = (a.type === 'string' || isBrandedString(a)) && (b.type === 'string' || isBrandedString(b))
        props[k] = bothStrings ? { type: 'string' } : {}
      }
    }
  }
  return { type: 'object', properties: props, required: [] }
}

function propLines(s, ownerName, ns, defs, skipKey) {
  const required = new Set(s.required ?? [])
  const lines = []
  for (const [key, raw] of Object.entries(s.properties ?? {})) {
    if (key === skipKey) continue
    const id = ident(key)
    let type = kt(raw, ownerName + pascal(key), ns, defs)
    const opt = !required.has(key)
    const ser = id.replace(/`/g, '') !== key ? `@SerialName(${JSON.stringify(key)}) ` : ''
    if (opt && !type.endsWith('?')) type += '?'
    lines.push(`    ${ser}val ${id}: ${type}${type.endsWith('?') ? ' = null' : ''},`)
  }
  return lines
}

function dataClass(s, nameHint, ns, defs) {
  const h = hash(s)
  if (byHash.has(h)) return byHash.get(h)
  const name = uniqueName(nameHint, ns, h)
  byHash.set(h, name)
  classes.set(name, { code: '', ns })
  const lines = propLines(s, name, ns, defs)
  const code = lines.length
    ? `@Serializable\ndata class ${name}(\n${lines.join('\n')}\n)\n`
    : `@Serializable\nclass ${name}\n`
  classes.set(name, { code, ns })
  return name
}

function sealed(members, disc, nameHint, ns, defs) {
  const h = hash({ members, disc: disc.key })
  if (byHash.has(h)) return byHash.get(h)
  const name = uniqueName(nameHint, ns, h)
  byHash.set(h, name)
  classes.set(name, { code: '', ns })
  const variants = []
  const seen = new Set()
  members.forEach((m, i) => {
    let vname = pascal(disc.values[i])
    while (seen.has(vname) || vname === 'Unknown') vname += 'V'
    seen.add(vname)
    const lines = propLines(m, name + vname, ns, defs, disc.key)
    const dprop = `    @SerialName(${JSON.stringify(disc.key)}) val ${ident(disc.key) === disc.key ? disc.key : ident(disc.key)}: String = ${JSON.stringify(disc.values[i])},`
    variants.push({ vname, value: disc.values[i], lines: [...lines, dprop] })
  })
  const dk = JSON.stringify(disc.key)
  let code = `@Serializable(with = ${name}.Serializer::class)\nsealed interface ${name} {\n`
  for (const v of variants) {
    code += `    @Serializable\n    data class ${v.vname}(\n${v.lines.map((l) => '    ' + l).join('\n')}\n    ) : ${name}\n\n`
  }
  code += `    /** Variant not known when this client was generated; raw JSON preserved. */\n`
  code += `    @Serializable(with = ${name}.UnknownSerializer::class)\n    data class Unknown(val raw: JsonObject) : ${name}\n\n`
  code += `    object UnknownSerializer : RawObjectSerializer<Unknown>("${PKG}.${name}.Unknown", ::Unknown, { it.raw })\n\n`
  code += `    object Serializer : JsonContentPolymorphicSerializer<${name}>(${name}::class) {\n`
  code += `        override fun selectDeserializer(element: JsonElement): DeserializationStrategy<${name}> =\n`
  code += `            when ((element as? JsonObject)?.get(${dk})?.let { (it as? JsonPrimitive)?.contentOrNull }) {\n`
  for (const v of variants) code += `                ${JSON.stringify(v.value)} -> ${v.vname}.serializer()\n`
  code += `                else -> UnknownSerializer\n            }\n    }\n}\n`
  classes.set(name, { code, ns })
  return name
}

// ---------- methods ----------
const header = (imports = '') =>
  `// GENERATED by tools/codegen/generate.mjs from DSH ${contract.dshVersion} — do not edit.\n` +
  `@file:Suppress("unused", "PropertyName", "ClassName", "RedundantNullableReturnType")\n\npackage ${PKG}\n\n${imports}`

const typeImports =
  'import kotlinx.serialization.DeserializationStrategy\nimport kotlinx.serialization.SerialName\nimport kotlinx.serialization.Serializable\n' +
  'import kotlinx.serialization.json.JsonArray\nimport kotlinx.serialization.json.JsonContentPolymorphicSerializer\nimport kotlinx.serialization.json.JsonElement\n' +
  'import kotlinx.serialization.json.JsonObject\nimport kotlinx.serialization.json.JsonPrimitive\nimport kotlinx.serialization.json.contentOrNull\n\n'

const byNs = new Map()
for (const m of contract.methods) {
  if (!byNs.has(m.namespace)) byNs.set(m.namespace, [])
  byNs.get(m.namespace).push(m)
}

const serializerOf = (t) => {
  const nullable = t.endsWith('?')
  const base = nullable ? t.slice(0, -1) : t
  let expr
  const list = base.match(/^List<(.+)>$/)
  const map = base.match(/^Map<String, (.+)>$/)
  if (list) expr = `ListSerializer(${serializerOf(list[1])})`
  else if (map) expr = `MapSerializer(String.serializer(), ${serializerOf(map[1])})`
  else if (['String', 'Long', 'Double', 'Boolean'].includes(base)) expr = `${base}.serializer()`
  else if (base === 'JsonElement') expr = 'JsonElement.serializer()'
  else if (base === 'JsonObject') expr = 'JsonObject.serializer()'
  else if (base === 'JsonArray') expr = 'JsonArray.serializer()'
  else expr = `${base}.serializer()`
  return nullable ? `${expr}.nullable` : expr
}

const apiFiles = []
const catalog = []
const aliases = new Map() // alias -> target
function named(t, hint) {
  // Keep the contract's own type names readable even when structurally deduped.
  const nullable = t.endsWith('?')
  const base = nullable ? t.slice(0, -1) : t
  if (!hint || base === hint || !/^[A-Z][A-Za-z0-9]*$/.test(base) || !classes.has(base)) return t
  if (classes.has(hint)) return t
  if (!aliases.has(hint)) aliases.set(hint, base)
  return hint + (nullable ? '?' : '')
}
for (const [ns, methods] of byNs) {
  const cls = `${pascal(ns)}Api`
  let body = ''
  for (const m of methods.sort((a, b) => a.method.localeCompare(b.method))) {
    const base = pascal(ns) + pascal(m.method)
    const params = m.parameters.map((p) => {
      const hint = p.typeSymbol ? pascal(p.typeSymbol.split('#').pop()) : base + pascal(p.wire)
      // `T | undefined` parameters export as anyOf [{}, T]. The gateway rejects an explicit null for
      // them ("failed boundary validation"), so they become optional Kotlin params that are omitted when null.
      let schema = p.schema
      let undefinable = false
      if (Array.isArray(schema?.anyOf) && schema.anyOf.some((x) => Object.keys(strip(x)).length === 0)) {
        undefinable = true
        const rest = schema.anyOf.filter((x) => Object.keys(strip(x)).length > 0)
        schema = rest.length === 1 ? { ...rest[0], $defs: schema.$defs } : { ...schema, anyOf: rest }
      }
      let t = p.source === 'lookup' ? 'String' : named(kt(schema, hint || base + 'Arg', ns, schema?.$defs), hint)
      if ((p.optional || undefinable) && !t.endsWith('?')) t += '?'
      return { p, t, id: ident(p.wire === '_request' ? 'request' : p.wire) }
    })
    const rhint = m.result?.typeSymbol ? pascal(m.result.typeSymbol.split('#').pop()) : base + 'Result'
    const rt = m.result?.schema ? named(kt(m.result.schema, rhint || base + 'Result', ns, m.result.schema.$defs), rhint) : 'JsonElement'
    const sig = params.map(({ t, id }) => `${id}: ${t}${t.endsWith('?') ? ' = null' : ''}`).join(', ')
    const puts = params
      .map(({ p, t, id }) =>
        t.endsWith('?')
          ? `            if (${id} != null) put(${JSON.stringify(p.wire)}, t.json.encodeToJsonElement(${serializerOf(t.slice(0, -1))}, ${id}))`
          : `            put(${JSON.stringify(p.wire)}, t.json.encodeToJsonElement(${serializerOf(t)}, ${id}))`,
      )
      .join('\n')
    const args = params.length ? `buildJsonObject {\n${puts}\n        }` : 'buildJsonObject {}'
    const name = ident(m.method)
    body += `    /** \`${m.endpoint}\`${m.mode === 'stream' ? ' (stream)' : ''} — ${m.source} */\n`
    if (m.mode === 'stream') {
      body += `    fun ${name}(${sig}): Flow<${rt}> =\n        t.stream(${JSON.stringify(m.endpoint)}, ${args}, ${serializerOf(rt)})\n\n`
    } else {
      body += `    suspend fun ${name}(${sig}): ${rt} =\n        t.call(${JSON.stringify(m.endpoint)}, ${args}, ${serializerOf(rt)})\n\n`
    }
    catalog.push(`        ${JSON.stringify(m.endpoint)} to ${m.mode === 'stream' ? 'true' : 'false'},`)
  }
  apiFiles.push({
    file: `${cls}.kt`,
    code:
      header(
        'import dsh.transport.DshTransport\nimport kotlinx.coroutines.flow.Flow\nimport kotlinx.serialization.builtins.ListSerializer\nimport kotlinx.serialization.builtins.MapSerializer\nimport kotlinx.serialization.builtins.nullable\nimport kotlinx.serialization.builtins.serializer\nimport kotlinx.serialization.json.JsonArray\nimport kotlinx.serialization.json.JsonElement\nimport kotlinx.serialization.json.JsonObject\nimport kotlinx.serialization.json.buildJsonObject\n\n',
      ) + `class ${cls}(private val t: DshTransport) {\n${body.trimEnd()}\n}\n`,
  })
}

const rootCode =
  header('import dsh.transport.DshTransport\n\n') +
  `/** Typed facade over every DSH Remote namespace (${contract.methods.length} methods). */\nclass DshApi(val transport: DshTransport) {\n` +
  [...byNs.keys()].map((ns) => `    val ${ident(ns)} = ${pascal(ns)}Api(transport)`).join('\n') +
  `\n\n    companion object {\n        const val DSH_VERSION = ${JSON.stringify(contract.dshVersion)}\n        /** endpoint -> isStream */\n        val ENDPOINTS: Map<String, Boolean> = mapOf(\n${catalog.join('\n')}\n        )\n    }\n}\n`

// group types by namespace of first use
const typeFiles = new Map()
for (const [name, { code, ns }] of classes) {
  if (!typeFiles.has(ns)) typeFiles.set(ns, [])
  typeFiles.get(ns).push(code)
}

await rm(outDir, { recursive: true, force: true })
await mkdir(outDir, { recursive: true })
for (const f of apiFiles) await writeFile(join(outDir, f.file), f.code)
for (const [ns, codes] of typeFiles)
  await writeFile(join(outDir, `${pascal(ns)}Types.kt`), header(typeImports) + codes.join('\n'))
await writeFile(join(outDir, 'DshApi.kt'), rootCode)
await writeFile(
  join(outDir, 'Aliases.kt'),
  header() + [...aliases].sort().map(([a, t]) => `typealias ${a} = ${t}`).join('\n') + '\n',
)
console.log(`generated ${classes.size} types, ${contract.methods.length} methods in ${byNs.size} namespaces -> ${outDir}`)
