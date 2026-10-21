/**
 * 领域 ID 类型。
 *
 * 后端雪花 ID 超出 JS Number 安全整数范围，已统一按字符串下发；
 * 前端仍可能出现来自路由参数 / 表单的数值 ID，故两者都接受。
 * 只做透传与相等比较，禁止参与算术运算。
 */
export type Id = string | number
