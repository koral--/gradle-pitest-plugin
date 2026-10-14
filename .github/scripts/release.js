const assert = require('node:assert/strict')
const fs = require('node:fs')
const { spawnSync } = require('node:child_process')

const changelogPath = 'CHANGELOG.md'
const gradlePropertiesPath = 'gradle.properties'
const readmePath = 'README.md'

function getVersionFromProperties(gradleProperties) {
  const match = gradleProperties.match(/^VERSION_NAME\s*=\s*(.+)$/m)
  assert.ok(match, 'VERSION_NAME must exist in gradle.properties')
  return match[1].trim()
}

function portalUrl(version) {
  return `https://plugins.gradle.org/m2/pl/droidsonroids/pitest/pl.droidsonroids.pitest.gradle.plugin/${version}/pl.droidsonroids.pitest.gradle.plugin-${version}.pom`
}

function centralUrl(version) {
  return `https://repo1.maven.org/maven2/pl/droidsonroids/gradle/gradle-pitest-plugin/${version}/gradle-pitest-plugin-${version}.pom`
}

function validateVersion(mode, version, gradleProperties, ref, defaultBranch) {
  assert.ok(defaultBranch && ref === `refs/heads/${defaultBranch}`, `Run this on ${defaultBranch}`)
  assert.match(version, /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/, 'Use a version like 0.2.28, without a v prefix')
  const currentVersion = getVersionFromProperties(gradleProperties)
  if (mode === 'prepare') {
    const current = currentVersion.split('.').map(BigInt)
    const requested = version.split('.').map(BigInt)
    const changed = requested.findIndex((part, index) => part !== current[index])
    assert.ok(changed >= 0 && requested[changed] > current[changed], `${version} must be greater than ${currentVersion}`)
  } else {
    assert.equal(version, currentVersion, 'Merge the version bump before releasing')
  }
}

function prepareRelease(version, gradleProperties, changelog, readme, notes) {
  assert.ok(notes && notes.trim(), 'Generated release notes are empty')
  assert.ok(!changelog.includes(`\n# ${version}`) && !changelog.includes(`\n## ${version}`) && !changelog.startsWith(`# ${version}`), 'Changelog already contains this version')

  const currentVersion = getVersionFromProperties(gradleProperties)
  const updatedGradleProperties = gradleProperties.replace(/^VERSION_NAME\s*=.*$/m, `VERSION_NAME=${version}`)

  let updatedReadme = readme
  if (readme) {
    updatedReadme = readme
      .replace(new RegExp(`(id\\s*\\(?['"]pl\\.droidsonroids\\.pitest['"]\\)?\\s*version\\s*['"])${currentVersion}(['"])`, 'g'), `$1${version}$2`)
      .replace(new RegExp(`(classpath\\(['"]pl\\.droidsonroids\\.gradle:gradle-pitest-plugin:)${currentVersion}(['"]\\))`, 'g'), `$1${version}$2`)
  }

  const today = new Date().toISOString().slice(0, 10)
  const cleanedNotes = notes.replace(/\r\n/g, '\n').trim()

  let updatedChangelog
  if (changelog.startsWith('# Unreleased')) {
    const lines = changelog.split('\n')
    const nextHeaderIndex = lines.slice(1).findIndex(line => /^#+ \d+\.\d+/.test(line))
    const unreleasedLines = nextHeaderIndex < 0 ? lines.slice(1) : lines.slice(1, nextHeaderIndex + 1)
    const remainingLines = nextHeaderIndex < 0 ? [] : lines.slice(nextHeaderIndex + 1)
    const unreleasedContent = unreleasedLines.join('\n').trim()

    let newSection = `# ${version} - ${today}`
    if (unreleasedContent) {
      newSection += `\n\n${unreleasedContent}`
    }
    if (cleanedNotes && !unreleasedContent.includes(cleanedNotes)) {
      newSection += `\n\n${cleanedNotes}`
    }
    updatedChangelog = `${newSection}\n\n${remainingLines.join('\n').trimStart()}`
  } else {
    updatedChangelog = `# ${version} - ${today}\n\n${cleanedNotes}\n\n${changelog.trimStart()}`
  }

  return {
    gradleProperties: updatedGradleProperties,
    readme: updatedReadme,
    changelog: updatedChangelog
  }
}

function releaseNotes(version, changelog) {
  const lines = changelog.split('\n')
  const start = lines.findIndex(line => line.startsWith(`# ${version}`) || line.startsWith(`## ${version}`))
  assert.ok(start >= 0, `Changelog has no entry for ${version}`)
  const remaining = lines.slice(start + 1)
  const end = remaining.findIndex(line => /^#+ \d+\.\d+/.test(line))
  const notes = remaining.slice(0, end < 0 ? remaining.length : end).join('\n').trim()
  assert.ok(notes, `Changelog entry for ${version} is empty`)
  return notes
}

async function isPublished(url) {
  try {
    const res = await fetch(url, { redirect: 'follow', signal: AbortSignal.timeout(15000) })
    return res.status === 200
  } catch {
    return false
  }
}

async function checkUnpublished(version) {
  const tag = spawnSync('git', ['show-ref', '--verify', '--quiet', `refs/tags/v${version}`])
  assert.equal(tag.status, 1, `Tag v${version} already exists or tags could not be checked`)

  const onPortal = await isPublished(portalUrl(version))
  assert.ok(!onPortal, `Gradle Plugin Portal: version ${version} is already published`)

  const onCentral = await isPublished(centralUrl(version))
  assert.ok(!onCentral, `Maven Central: version ${version} is already published`)
}

async function main() {
  const [command, notesPath] = process.argv.slice(2)
  const version = process.env.VERSION
  const changelog = fs.existsSync(changelogPath) ? fs.readFileSync(changelogPath, 'utf8') : ''
  const gradleProperties = fs.existsSync(gradlePropertiesPath) ? fs.readFileSync(gradlePropertiesPath, 'utf8') : ''
  const readme = fs.existsSync(readmePath) ? fs.readFileSync(readmePath, 'utf8') : ''

  if (command === 'notes') {
    console.log(releaseNotes(version, changelog))
    return
  }
  if (command === 'is-published-portal') {
    const published = await isPublished(portalUrl(version))
    process.exitCode = published ? 0 : 1
    return
  }
  if (command === 'is-published-central') {
    const published = await isPublished(centralUrl(version))
    process.exitCode = published ? 0 : 1
    return
  }

  assert.ok(['check-prepare', 'check-release', 'prepare'].includes(command), 'Unknown release command')

  validateVersion(
    command === 'check-release' ? 'release' : 'prepare',
    version,
    gradleProperties,
    process.env.GITHUB_REF,
    process.env.DEFAULT_BRANCH
  )

  if (command === 'prepare') {
    const notes = fs.readFileSync(notesPath, 'utf8')
    const next = prepareRelease(version, gradleProperties, changelog, readme, notes)
    fs.writeFileSync(gradlePropertiesPath, next.gradleProperties)
    if (next.readme) {
      fs.writeFileSync(readmePath, next.readme)
    }
    fs.writeFileSync(changelogPath, next.changelog)
    return
  }

  if (command === 'check-release') {
    releaseNotes(version, changelog)
  }

  await checkUnpublished(version)
}

module.exports = {
  getVersionFromProperties,
  validateVersion,
  prepareRelease,
  releaseNotes,
  portalUrl,
  centralUrl,
  isPublished,
  checkUnpublished
}

if (require.main === module) {
  main().catch(error => {
    console.error(error.message)
    process.exitCode = 1
  })
}
