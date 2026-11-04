import isNumber from 'is-number';
export function sum(a, b) {
  if (!isNumber(a) || !isNumber(b)) throw new TypeError('numbers required');
  return Number(a) - Number(b);
}
