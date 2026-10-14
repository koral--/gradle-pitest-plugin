const assert = require('node:assert/strict')
const { test } = require('node:test')
const {
  getVersionFromProperties,
  validateVersion,
  prepareRelease,
  releaseNotes,
  portalUrl,
  centralUrl
} = require('./release')

const sampleGradleProperties = 'VERSION_NAME=0.2.27\nGROUP=pl.droidsonroids.gradle\n'
const sampleReadme = `
\`\`\`groovy
plugins {
  id 'pl.droidsonroids.pitest' version '0.2.27'
}
\`\`\`

\`\`\`kotlin
plugins {
  id("pl.droidsonroids.pitest") version "0.2.27"
}
\`\`\`

\`\`\`kotlin
dependencies {
  classpath("pl.droidsonroids.gradle:gradle-pitest-plugin:0.2.27")
}
\`\`\`
`

test('extracts current version from gradle.properties', () => {
  assert.equal(getVersionFromProperties(sampleGradleProperties), '0.2.27')
  assert.throws(() => getVersionFromProperties('FOO=BAR\n'))
})

test('prepare requires an increasing stable version and default branch', () => {
  for (const version of ['0.2.28', '0.3.0', '1.0.0']) {
    validateVersion('prepare', version, sampleGradleProperties, 'refs/heads/master', 'master')
  }
  for (const version of ['0.2.27', '0.2.26', '0.1.0', 'v0.2.28', '0.2.28-rc1', '0.2', '0.2.28\n']) {
    assert.throws(() => validateVersion('prepare', version, sampleGradleProperties, 'refs/heads/master', 'master'))
  }
  for (const ref of ['refs/heads/release/v0.2.28', 'refs/tags/master']) {
    assert.throws(() => validateVersion('prepare', '0.2.28', sampleGradleProperties, ref, 'master'))
  }
})

test('release rejects an unmerged bump and wrong branch', () => {
  validateVersion('release', '0.2.27', sampleGradleProperties, 'refs/heads/master', 'master')
  assert.throws(() => validateVersion('release', '0.2.28', sampleGradleProperties, 'refs/heads/master', 'master'))
  assert.throws(() => validateVersion('release', '0.2.27', sampleGradleProperties, 'refs/heads/release/v0.2.27', 'master'))
})

test('prepare updates gradle.properties, readme, and unreleased changelog', () => {
  const changelog = `# Unreleased

## Feature
- Add new feature [#100](https://github.com/...)

# 0.2.27 - 2026-03-24
- Older change
`
  const notes = "## What's Changed\r\n* PR #101 by @user in https://github.com/..."
  const result = prepareRelease('0.2.28', sampleGradleProperties, changelog, sampleReadme, notes)

  assert.equal(getVersionFromProperties(result.gradleProperties), '0.2.28')
  assert.ok(result.readme.includes("version '0.2.28'"))
  assert.ok(result.readme.includes('version "0.2.28"'))
  assert.ok(result.readme.includes('gradle-pitest-plugin:0.2.28'))

  const extracted28 = releaseNotes('0.2.28', result.changelog)
  assert.ok(extracted28.includes('## Feature'))
  assert.ok(extracted28.includes('Add new feature'))
  assert.ok(extracted28.includes("## What's Changed"))
  assert.ok(extracted28.includes('PR #101'))

  const extracted27 = releaseNotes('0.2.27', result.changelog)
  assert.equal(extracted27, '- Older change')
})

test('prepare handles changelog without unreleased section', () => {
  const changelog = `# 0.2.27 - 2026-03-24
- Older change
`
  const notes = "## What's Changed\n* First PR"
  const result = prepareRelease('0.2.28', sampleGradleProperties, changelog, sampleReadme, notes)

  const extracted28 = releaseNotes('0.2.28', result.changelog)
  assert.ok(extracted28.includes("## What's Changed"))
  assert.ok(extracted28.includes('First PR'))
})

test('releaseNotes throws on missing or empty notes', () => {
  assert.throws(() => releaseNotes('0.2.28', '# Changelog\n'))
  assert.throws(() => releaseNotes('0.2.28', '# 0.2.28 - 2026-10-14\n\n# 0.2.27 - 2026-03-24\n- Old\n'))
})

test('portal and central URLs match expected coordinates', () => {
  assert.equal(
    portalUrl('0.2.28'),
    'https://plugins.gradle.org/m2/pl/droidsonroids/pitest/pl.droidsonroids.pitest.gradle.plugin/0.2.28/pl.droidsonroids.pitest.gradle.plugin-0.2.28.pom'
  )
  assert.equal(
    centralUrl('0.2.28'),
    'https://repo1.maven.org/maven2/pl/droidsonroids/gradle/gradle-pitest-plugin/0.2.28/gradle-pitest-plugin-0.2.28.pom'
  )
})
