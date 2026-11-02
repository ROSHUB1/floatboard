#!/usr/bin/env node
// Rebuilds app/src/main/assets/starter/index.json from the audio files in that folder.
// Existing names in index.json are kept; new files get a name made from the file name.
// Only put sounds in there that you made yourself or have the rights to ship (CC0, or credit CC-BY).
const fs = require('fs');
const path = require('path');
const dir = path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'starter');
const idxFile = path.join(dir, 'index.json');
const old = fs.existsSync(idxFile) ? JSON.parse(fs.readFileSync(idxFile, 'utf8')) : [];
const names = new Map(old.map((o) => [o.file, o.name]));
const files = fs.readdirSync(dir).filter((f) => /\.(wav|mp3|ogg|m4a)$/i.test(f)).sort();
const out = files.map((f) => ({
  file: f,
  name: names.get(f) || path.basename(f, path.extname(f)).replace(/[-_]+/g, ' ').replace(/\b\w/g, (c) => c.toUpperCase()),
}));
fs.writeFileSync(idxFile, JSON.stringify(out, null, 2) + '\n');
console.log(`Wrote ${out.length} sounds to index.json`);
