#!/usr/bin/env node
// FloatBoard license tool (Node.js only, no packages needed).
//
//   node tools/license.js keygen             -> makes tools/private.pem and prints the PUBLIC key
//                                               to paste into Entitlement.kt (PUBLIC_KEY_B64)
//   node tools/license.js sign "Name" 30     -> prints a license key valid for 30 days
//   node tools/license.js sign "Name" 0      -> prints a lifetime key
//
// NEVER commit or share tools/private.pem. Anyone who has it can make unlimited keys.
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

const KEY_FILE = path.join(__dirname, 'private.pem');
const b64url = (buf) => Buffer.from(buf).toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');

const [, , cmd, name, days] = process.argv;

if (cmd === 'keygen') {
  if (fs.existsSync(KEY_FILE)) {
    console.error('tools/private.pem already exists. Refusing to overwrite it (old keys would stop working).');
    process.exit(1);
  }
  const { publicKey, privateKey } = crypto.generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
  fs.writeFileSync(KEY_FILE, privateKey.export({ type: 'pkcs8', format: 'pem' }), { mode: 0o600 });
  console.log('Private key saved to tools/private.pem (keep it secret, back it up).\n');
  console.log('Paste this into LicenseConfig.kt as PUBLIC_KEY_B64:\n');
  console.log(publicKey.export({ type: 'spki', format: 'der' }).toString('base64'));
} else if (cmd === 'sign') {
  if (!name || days === undefined || isNaN(Number(days))) {
    console.error('Usage: node tools/license.js sign "Buyer name" <days, 0 = lifetime>');
    process.exit(1);
  }
  if (!fs.existsSync(KEY_FILE)) {
    console.error('No tools/private.pem yet. Run: node tools/license.js keygen');
    process.exit(1);
  }
  const priv = crypto.createPrivateKey(fs.readFileSync(KEY_FILE));
  const exp = Number(days) > 0 ? Math.floor(Date.now() / 1000) + Number(days) * 86400 : 0;
  const payload = Buffer.from(JSON.stringify({ v: 1, plan: 'pro', name, exp }));
  const signature = crypto.sign('sha256', payload, priv); // ECDSA P-256, DER encoded
  console.log(`FB1.${b64url(payload)}.${b64url(signature)}`);
} else {
  console.error('Commands: keygen | sign "Name" <days>');
  process.exit(1);
}
