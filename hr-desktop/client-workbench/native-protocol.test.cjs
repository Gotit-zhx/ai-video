const test = require('node:test');
const assert = require('node:assert/strict');
const {decodeNativeProtocol} = require('./native-protocol.cjs');

function varint(value) {
  value = BigInt(value);
  const bytes = [];
  do {
    let byte = Number(value & 127n);value >>= 7n;
    if (value) byte |= 128;
    bytes.push(byte);
  } while (value);
  return Buffer.from(bytes);
}
const scalar = (number, value) => Buffer.concat([varint(number * 8), varint(value)]);
const bytes = (number, value) => Buffer.concat([varint(number * 8 + 2), varint(value.length), value]);
const fixed = (number, width) => Buffer.concat([varint(number * 8 + (width === 4 ? 5 : 1)), Buffer.alloc(width)]);
const user = id => scalar(1, id);
const body = text => Buffer.concat([scalar(1, 1), bytes(3, Buffer.from(text))]);
const message = (extra = Buffer.alloc(0)) => Buffer.concat([
  bytes(1, user('18446744073709551615')), bytes(2, user('9007199254740993')),
  scalar(4, '9007199254740995'), scalar(5, '1792051200000'),
  bytes(6, body('你好，离线回执夹具')), scalar(11, '9007199254740997'), extra,
]);
const protocol = item => Buffer.concat([scalar(1, 1), bytes(3, item)]);

test('reads exact uint64 IDs and UTF-8 without creating or sending a message', () => {
  assert.deepEqual(decodeNativeProtocol(protocol(message())), {type: 1, messages: [{
    from: {uid: '18446744073709551615'}, to: {uid: '9007199254740993'},
    mid: '9007199254740995', cmid: '9007199254740997', time: '1792051200000',
    body: {type: 1, text: '你好，离线回执夹具'},
  }]});
});

test('supports original writer output and byte views with nonzero offsets', () => {
  const packet = protocol(message()), padded = Buffer.concat([Buffer.alloc(7), packet, Buffer.alloc(3)]);
  let reads = 0;
  assert.deepEqual(decodeNativeProtocol({finish() {reads++;return packet;}}), decodeNativeProtocol(packet));
  assert.equal(reads, 1);
  assert.deepEqual(decodeNativeProtocol(new DataView(padded.buffer, padded.byteOffset + 7, packet.length)),
    decodeNativeProtocol(packet));
});

test('unknown supported fields are ignored; non-message protocol types remain non-message', () => {
  const packet = Buffer.concat([protocol(message(Buffer.concat([
    fixed(99, 4), fixed(100, 8), scalar(101, 3), bytes(102, Buffer.from('other')),
  ]))), fixed(99, 8)]);
  assert.equal(decodeNativeProtocol(packet).messages.length, 1);
  assert.deepEqual(decodeNativeProtocol(Buffer.concat([scalar(1, 2), bytes(3, Buffer.from([255]))])),
    {type: 2, messages: []});
});

test('a duplicate or wrong-wire correlation field is rejected instead of silently skipped', () => {
  for (const extra of [scalar(11, 303), fixed(11, 4), fixed(4, 8), bytes(2, user(404))]) {
    assert.throws(() => decodeNativeProtocol(protocol(message(extra))), /Ambiguous/);
  }
  assert.throws(() => decodeNativeProtocol(Buffer.concat([protocol(message()), fixed(1, 4)])), /Ambiguous/);
  assert.throws(() => decodeNativeProtocol(Buffer.concat([scalar(1, 1), scalar(3, 0)])), /message count/);
});

test('missing identity and malformed nested text never produce a correlation result', () => {
  const required = [
    bytes(1, Buffer.alloc(0)), bytes(2, user(202)),
    bytes(6, body('offline')),
  ];
  assert.throws(() => decodeNativeProtocol(protocol(Buffer.concat(required))), /Ambiguous/);
  assert.throws(() => decodeNativeProtocol(protocol(Buffer.concat([
    bytes(1, user(101)), bytes(2, user(202)),
    bytes(6, Buffer.concat([scalar(1, 1), bytes(3, Buffer.from([0xc3, 0x28]))])),
  ]))));
});

test('rejects truncated, overlong and overflowing integers, lengths and unsupported groups', () => {
  const invalid = [
    Buffer.from([0x08, 0x80]), Buffer.from([0x08, 0x81, 0x00]),
    Buffer.from([0x08, ...Array(9).fill(0x80), 0x02]),
    Buffer.from([0x08, ...Array(10).fill(0x80)]),
    Buffer.from([0x08, 1, 0x1a, 10, 0]), Buffer.from([0x08, 1, 0x0b]),
    Buffer.from([0]), Buffer.from([0x08, 1, 0x21, 0]),
    Buffer.concat([scalar(1, 1), varint((1n << 29n) * 8n), varint(0)]),
    scalar(1, '18446744073709551615'),
  ];
  for (const packet of invalid) assert.throws(() => decodeNativeProtocol(packet));
});

test('bounds packet size, field count, message count and text bytes', () => {
  assert.throws(() => decodeNativeProtocol(Buffer.alloc(0)), /size limit/);
  assert.throws(() => decodeNativeProtocol(Buffer.alloc(1024 * 1024 + 1)), /size limit/);
  assert.throws(() => decodeNativeProtocol(Buffer.concat([
    scalar(1, 1), ...Array.from({length: 4096}, () => scalar(99, 0)),
  ])), /field limit/);
  assert.throws(() => decodeNativeProtocol(Buffer.concat([
    scalar(1, 1), ...Array.from({length: 129}, () => bytes(3, message())),
  ])), /message count/);
  assert.throws(() => decodeNativeProtocol(protocol(Buffer.concat([
    bytes(1, user(101)), bytes(2, user(202)), bytes(6, body('a'.repeat(16385))),
  ]))), /text limit/);
  for (const input of [null, {}, 'packet', new ArrayBuffer(8)]) {
    assert.throws(() => decodeNativeProtocol(input), /bytes required/);
  }
});
