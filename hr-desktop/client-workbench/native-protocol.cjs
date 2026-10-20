// Read only the correlation fields needed by the native bridge. These field
// numbers are checked against the pinned original message builder at runtime.
// This module does not create, send, acknowledge or decrypt messages.
const {TextDecoder} = require('node:util');
const decoder = new TextDecoder('utf-8', {fatal: true});
const MAX_PACKET = 1024 * 1024;

function fields(buffer) {
  let offset = 0, count = 0;
  const result = new Map();
  const varint = () => {
    let value = 0n;
    for (let index = 0; index < 10; index++) {
      if (offset >= buffer.length) throw Error('Incomplete native packet');
      const byte = buffer[offset++];
      if (index === 9 && byte > 1) throw Error('Invalid native integer');
      value |= BigInt(byte & 127) << BigInt(index * 7);
      if (!(byte & 128)) {
        if (index > 0 && byte === 0) throw Error('Noncanonical native integer');
        return value;
      }
    }
    throw Error('Invalid native integer');
  };
  while (offset < buffer.length) {
    if (++count > 4096) throw Error('Native packet field limit');
    const tag = varint(), wire = Number(tag & 7n), number = Number(tag >> 3n);
    if (number < 1 || number >= 2 ** 29) throw Error('Invalid native field');
    let value;
    if (wire === 0) value = varint();
    else if (wire === 2) {
      const length = Number(varint());
      if (!Number.isSafeInteger(length) || length < 0 || offset + length > buffer.length) {
        throw Error('Incomplete native field');
      }
      value = buffer.subarray(offset, offset + length);offset += length;
    } else if (wire === 1 || wire === 5) {
      const length = wire === 1 ? 8 : 4;
      if (offset + length > buffer.length) throw Error('Incomplete native field');
      value = buffer.subarray(offset, offset + length);
      offset += length;
    } else throw Error('Unsupported native wire type');
    const items = result.get(number) || [];
    items.push({wire, value});result.set(number, items);
  }
  return result;
}
function one(map, number, wire, required = false) {
  const values = map.get(number) || [];
  if (values.length > 1 || (required && values.length !== 1) ||
      values.some(value => value.wire !== wire)) throw Error('Ambiguous native field');
  return values[0]?.value;
}
function integer(map, number, required = false) {
  const value = one(map, number, 0, required);
  return value === undefined ? '' : String(value);
}
function enumeration(map, number) {
  const value = BigInt(integer(map, number, true));
  if (value > 0x7fffffffn) throw Error('Invalid native enum');
  return Number(value);
}
function user(value) {
  if (!value) return {uid: ''};
  return {uid: integer(fields(value), 1, true)};
}
function body(value) {
  if (!value) return {type: 0, text: ''};
  const map = fields(value), type = enumeration(map, 1);
  const text = one(map, 3, 2);
  if (text?.length > 16384) throw Error('Native text limit');
  return {type, text: text ? decoder.decode(text) : ''};
}
function decodeNativeProtocol(input) {
  if (input && !ArrayBuffer.isView(input) && typeof input.finish === 'function') input = input.finish();
  if (!ArrayBuffer.isView(input)) throw Error('Native packet bytes required');
  const buffer = Buffer.from(input.buffer, input.byteOffset, input.byteLength);
  if (!buffer.length || buffer.length > MAX_PACKET) throw Error('Native packet size limit');
  const map = fields(buffer), type = enumeration(map, 1);
  if (type !== 1) return {type, messages: []};
  const messages = map.get(3) || [];
  if (messages.length > 128 || messages.some(message => message.wire !== 2)) {
    throw Error('Native message count limit');
  }
  return {type, messages: messages.map(({value}) => {
    const item = fields(value);
    return {from: user(one(item, 1, 2, true)), to: user(one(item, 2, 2, true)),
      mid: integer(item, 4), cmid: integer(item, 11), time: integer(item, 5),
      body: body(one(item, 6, 2, true))};
  })};
}
module.exports = {decodeNativeProtocol};
