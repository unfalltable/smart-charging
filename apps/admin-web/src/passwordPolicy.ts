export function evaluatePassword(password: string) {
  return {
    length: password.length >= 12 && new TextEncoder().encode(password).byteLength <= 72,
    upper: /\p{Uppercase}/u.test(password),
    lower: /\p{Lowercase}/u.test(password),
    digit: /\p{Decimal_Number}/u.test(password),
    symbol: /[^\p{Letter}\p{Decimal_Number}]/u.test(password)
  }
}
