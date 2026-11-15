const fs = require('node:fs');
const { spawnSync } = require('node:child_process');
const assert = require('node:assert/strict');
const outside = process.argv[2];
const denied = (name, action) => {
    assert.throws(action, (err) => err.code === 'EACCES' || err.code === 'EPERM', name);
    console.log(name + '=denied');
};
assert.equal(fs.readFileSync('input.txt', 'utf8'), 'synthetic todo\n');
fs.writeFileSync('result.csv', 'task,status\nsynthetic todo,draft\n');
denied('outside_read_absolute', () => fs.readFileSync(outside));
denied('outside_read_relative', () => fs.readFileSync('../outside/canary.txt'));
denied('outside_list', () => fs.readdirSync('../outside'));
denied('outside_overwrite', () => fs.writeFileSync(outside, 'modified'));
denied('outside_create', () => fs.writeFileSync('../outside/created.txt', 'bad'));
denied('outside_delete', () => fs.unlinkSync(outside));
fs.symlinkSync(outside, 'escape-link');
denied('symlink_read', () => fs.readFileSync('escape-link'));
denied('symlink_write', () => fs.writeFileSync('escape-link', 'modified'));
const child = spawnSync(process.execPath, ['-e',
    'try {require("node:fs").readFileSync(process.argv[1]); process.exit(9)} catch (err) {process.exit(err.code === "EACCES" ? 0 : 8)}',
    outside]);
assert.equal(child.status, 0, String(child.error || child.stderr));
console.log('child_outside_read=denied');
assert.equal(fs.readFileSync('result.csv', 'utf8'), 'task,status\nsynthetic todo,draft\n');
console.log('task_read_write=ok node=' + process.version + ' uid=' + process.getuid());
