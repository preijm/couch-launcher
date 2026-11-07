// Installs the newest .ipk from dist/ on the "tv" device (cross-platform, no shell globbing).
const fs = require('fs');
const path = require('path');
const { execSync } = require('child_process');

const dist = path.join(__dirname, '..', 'dist');
const ipks = fs.readdirSync(dist)
  .filter((f) => f.endsWith('.ipk'))
  .map((f) => ({ f, t: fs.statSync(path.join(dist, f)).mtimeMs }))
  .sort((a, b) => b.t - a.t);

if (!ipks.length) {
  console.error('No .ipk in dist/ - run "npm run package" first.');
  process.exit(1);
}
const file = path.join(dist, ipks[0].f);
console.log('Installing ' + ipks[0].f);
execSync('ares-install -d tv "' + file + '"', { stdio: 'inherit' });
