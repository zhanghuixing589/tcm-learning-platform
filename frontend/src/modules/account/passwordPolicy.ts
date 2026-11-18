export const PASSWORD_MIN_LENGTH = 10;
export const PASSWORD_MAX_LENGTH = 12;
export const REGISTRATION_PASSWORD_PATTERN = String.raw`(?=.*[A-Z])(?=.*[a-z])(?=.*[^A-Za-z])[\x21-\x7E]{10,12}`;
export const REGISTRATION_PASSWORD_MESSAGE =
  "密码须为10–12位，包含大写字母、小写字母，以及数字或半角特殊符号，不能包含空格或其他语言文字";
const passwordPattern = new RegExp(`^(?:${REGISTRATION_PASSWORD_PATTERN})$`);

export function isValidRegistrationPassword(password: string): boolean {
  return passwordPattern.test(password);
}
